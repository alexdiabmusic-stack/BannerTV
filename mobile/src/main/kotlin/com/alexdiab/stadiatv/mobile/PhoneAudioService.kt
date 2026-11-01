@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexdiab.stadiatv.mobile

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PhoneAudioState(val episode: PhoneEpisode? = null, val playing: Boolean = false, val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val error: String? = null)

/** Playback is service-owned, so leaving a screen never tears down podcast audio, headset controls or Android Auto. */
class PhoneAudioService : MediaLibraryService() {
    private lateinit var session: MediaLibrarySession
    private lateinit var player: ExoPlayer
    private lateinit var library: PhonePodcastsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previousId: String? = null
    private var previousPosition = 0L

    override fun onCreate() {
        super.onCreate()
        active = this
        library = PhonePodcastsStore.get(this)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            setPauseAtEndOfMediaItems(!library.state.value.automaticallyAdvance)
            setPlaybackSpeed(library.state.value.speed)
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                val id = player.currentMediaItem?.mediaId
                if (id != previousId) {
                    previousId?.let { library.bookmark(it, previousPosition) }
                    previousId = id
                    id?.let { library.episode(it) }?.let { episode -> library.update { it.copy(history = (listOf(episode) + it.history.filterNot { e -> e.id == episode.id }).take(50)) } }
                }
                if (!player.isPlaying && id != null) library.bookmark(id, player.currentPosition)
                publish()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { uiMutable.value = uiMutable.value.copy(error = "This episode stopped. Retry or select another episode.") }
        })
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(folder("root", "BannerTV Audio"), params))

            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    val children = try { withContext(Dispatchers.IO) {
                        when (parentId) {
                            "root" -> listOf(folder("subscriptions", "Your shows"), folder("queue", "Up next"), folder("downloads", "Downloads"), folder("catalog", "Explore sports"))
                            "subscriptions" -> library.state.value.subscriptions.map { folder("feed:${it.feed}", it.title, it.image) }
                            "catalog" -> library.ensureCatalog().take(100).map { folder("feed:${it.feed}", it.title, it.image) }
                            "queue" -> library.state.value.queue.filter { !it.mime.startsWith("video") }.map(::playable)
                            "downloads" -> { library.refreshDownloads(); library.state.value.downloads.filter { it.complete && !it.episode.mime.startsWith("video") }.map { playable(it.episode) } }
                            else -> {
                                val feed = parentId.removePrefix("feed:")
                                val show = (library.state.value.subscriptions + library.ensureCatalog()).firstOrNull { it.feed == feed }
                                show?.let { library.episodes(it).filter { e -> !e.mime.startsWith("video") }.map(::playable) }.orEmpty()
                            }
                        }
                    } } catch (cancel: CancellationException) { future.cancel(false); throw cancel } catch (_: Exception) { emptyList() }
                    val start = (page.coerceAtLeast(0).toLong() * pageSize.coerceIn(1, 100)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    future.set(LibraryResult.ofItemList(children.drop(start).take(pageSize.coerceIn(1, 100)), params))
                }
                return future
            }

            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                val future = SettableFuture.create<List<MediaItem>>()
                scope.launch {
                    try {
                        val accepted = withContext(Dispatchers.IO) { withTimeout(15_000) { mediaItems.mapNotNull { item ->
                            // Only known IDs or an explicit public-catalog search can select media; never trust a supplied file/URL.
                            val episode = library.episode(item.mediaId) ?: item.requestMetadata.searchQuery?.takeIf(String::isNotBlank)?.take(300)?.let { query ->
                                (library.state.value.queue + library.state.value.history).firstOrNull { searchRank(query, it.title, it.show) > 0 }
                                    ?: library.search(query).firstOrNull()?.let { show -> library.episodes(show).firstOrNull { e -> !e.mime.startsWith("video") } }?.also(library::select)
                            } ?: return@mapNotNull null
                            if (controller.packageName.contains("gearhead") && episode.mime.startsWith("video")) return@mapNotNull null
                            playable(episode)
                        } } }
                        future.set(accepted)
                    } catch (cancel: CancellationException) { future.cancel(false); throw cancel } catch (_: Exception) { future.set(emptyList()) }
                }
                return future
            }
        }).setSessionActivity(android.app.PendingIntent.getActivity(this, 420, Intent(this, MobileActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("banner://audio")), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)).build()
        scope.launch { library.state.collect { state -> player.setPauseAtEndOfMediaItems(!state.automaticallyAdvance); player.setPlaybackSpeed(state.speed) } }
        scope.launch {
            var lastSaved = System.currentTimeMillis()
            while (isActive) {
                delay(1_000); publish()
                if (player.isPlaying && System.currentTimeMillis() - lastSaved > 30_000) { player.currentMediaItem?.mediaId?.let { library.bookmark(it, player.currentPosition) }; lastSaved = System.currentTimeMillis() }
            }
        }
    }

    private fun publish() {
        previousPosition = player.currentPosition.coerceAtLeast(0)
        val episode = player.currentMediaItem?.mediaId?.let(library::episode)
        uiMutable.value = PhoneAudioState(episode, player.isPlaying, player.playbackState == Player.STATE_BUFFERING, previousPosition, player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: (episode?.duration ?: 0) * 1000, player.playbackParameters.speed, if (player.playerError != null) "Episode playback interrupted" else null)
    }
    private fun playable(episode: PhoneEpisode): MediaItem = episodeItem(episode).buildUpon().setUri(library.mediaUri(episode)).build()
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf() }
    override fun onDestroy() {
        player.currentMediaItem?.mediaId?.let { library.bookmark(it, player.currentPosition) }
        scope.cancel(); session.release(); player.release(); active = null; uiMutable.value = PhoneAudioState()
        super.onDestroy()
    }
    companion object {
        private val uiMutable = MutableStateFlow(PhoneAudioState())
        val ui = uiMutable.asStateFlow()
        private var active: PhoneAudioService? = null
        fun pauseActive() { active?.player?.pause() }
        fun toggle() { active?.player?.let { if (it.playWhenReady) it.pause() else { if (it.playerError != null) it.prepare(); it.play() } } }
        fun seek(position: Long) { active?.player?.seekTo(position.coerceAtLeast(0)) }
        fun stop() { active?.player?.stop(); active?.player?.clearMediaItems(); active?.stopSelf() }
        fun folder(id: String, title: String, image: String? = null): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtworkUri(image?.let(Uri::parse)).setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
        fun episodeItem(episode: PhoneEpisode): MediaItem = MediaItem.Builder().setMediaId(episode.id).setUri(episode.media).setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist(episode.show).setArtworkUri(episode.image?.let(Uri::parse)).setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE).build()).build()
    }
}

class PhoneAudioConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    suspend fun connect(): MediaController {
        val target = future ?: MediaController.Builder(context, SessionToken(context, ComponentName(context, PhoneAudioService::class.java))).buildAsync().also { future = it }
        return suspendCancellableCoroutine { continuation -> target.addListener({ if (continuation.isActive) runCatching { target.get() }.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) }) }, ContextCompat.getMainExecutor(context)) }
    }
    suspend fun play(episode: PhoneEpisode) {
        val library = PhonePodcastsStore.get(context)
        library.select(episode)
        val controller = connect()
        val queue = library.state.value.queue
        val bookmark = library.bookmarkPosition(episode.id).takeIf { episode.duration <= 0 || it < episode.duration * 980 } ?: 0
        controller.setMediaItems(queue.map(PhoneAudioService::episodeItem), queue.indexOfFirst { it.id == episode.id }.coerceAtLeast(0), bookmark)
        controller.prepare(); controller.play()
    }
    suspend fun syncQueue() {
        val state = PhoneAudioService.ui.value
        val current = state.episode ?: return
        val controller = connect()
        val queue = PhonePodcastsStore.get(context).state.value.queue
        val index = queue.indexOfFirst { it.id == current.id }
        if (index >= 0) { val playing = controller.playWhenReady; controller.setMediaItems(queue.map(PhoneAudioService::episodeItem), index, controller.currentPosition); controller.prepare(); controller.playWhenReady = playing }
    }
    fun release() { future?.let(MediaController::releaseFuture); future = null }
}
