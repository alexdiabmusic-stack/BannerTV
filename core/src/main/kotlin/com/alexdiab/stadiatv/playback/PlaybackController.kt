package com.alexdiab.stadiatv.playback

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.AudioAttributes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.StreamHeaders
import com.alexdiab.stadiatv.playback.engine.EnginePhase
import com.alexdiab.stadiatv.playback.engine.PlaybackPaceController
import com.alexdiab.stadiatv.playback.engine.ResilientTsDataSource
import com.alexdiab.stadiatv.playback.engine.StreamEngineConfiguration
import com.alexdiab.stadiatv.playback.engine.StreamEngineListener
import com.alexdiab.stadiatv.playback.engine.StreamEngineSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BufferProfile(
    val label: String,
    val detail: String,
    val minMs: Int,
    val maxMs: Int,
    val startMs: Int,
    val rebufferMs: Int,
    val startupTimeoutMs: Long,
    val targetBufferBytes: Int,
    val engine: StreamEngineConfiguration,
) {
    Fast(
        "Fast", "Closest to live · 8-second resilience target", 4_000, 16_000, 800, 1_500, 8_000, 24 * 1024 * 1024,
        StreamEngineConfiguration(8.0, 4.0, 16.0, 0.995f, 0.998f, 0.985f),
    ),
    Balanced(
        "Balanced", "20-second resilience target (default)", 8_000, 35_000, 1_500, 3_000, 10_000, 32 * 1024 * 1024,
        StreamEngineConfiguration(20.0, 8.0, 35.0, 0.99f, 0.995f, 0.98f),
    ),
    Stable(
        "Stable", "35-second target · higher memory use", 15_000, 55_000, 4_000, 6_000, 12_000, 48 * 1024 * 1024,
        StreamEngineConfiguration(35.0, 15.0, 50.0, 0.99f, 0.995f, 0.98f),
    ),
}

enum class QualityCap(val label: String, val maxHeight: Int) {
    Auto("Auto", Int.MAX_VALUE), P1080("1080p", 1080), P720("720p", 720), P480("480p", 480)
}

// Keep legacy persisted names: Fill was always stretch, Zoom was always crop.
enum class AspectMode(val label: String, val detail: String) {
    Fit("Fit", "Whole picture, may letterbox"), Zoom("Fill", "Fill the screen, may crop edges"),
    Fill("Stretch", "Fill the screen, may distort"), Original("Original", "Native display-pixel size, reduced only to fit")
}

@Immutable
data class TrackOption(val id: String, val label: String, val selected: Boolean, val language: String? = null)

@Immutable
data class PlaybackUi(
    val channelKey: String? = null,
    val isLoading: Boolean = false,
    val firstFrameRendered: Boolean = false,
    val isBuffering: Boolean = false,
    /** Buffering has lasted long enough to be worth showing a spinner for. */
    val showsBufferingIndicator: Boolean = false,
    val isUserPaused: Boolean = false,
    val isMuted: Boolean = false,
    val reconnectAttempt: Int = 0,
    val isBehindLive: Boolean = false,
    val videoHeight: Int = 0,
    val videoWidth: Int = 0,
    val videoPixelRatio: Float = 1f,
    val frameRate: Float = 0f,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    /** Subtitles are showing. */
    val captionsOn: Boolean = false,
    val qualityCap: QualityCap = QualityCap.Auto,
    val engineActive: Boolean = false,
    val enginePhase: EnginePhase = EnginePhase.OFF,
    val engineNotice: String? = null,
    val reserveSeconds: Int = 0,
    val playbackRate: Float = 1f,
    val errorMessage: String? = null,
    val timeline: PlaybackTimeline = PlaybackTimeline(),
    val isArchive: Boolean = false,
)

/**
 * Owns the one ExoPlayer for a player presentation. Mirrors the iOS `PlaybackController`:
 *
 * - provider headers on every request (playlist, variants, segments);
 * - a start-up watchdog and fallback URLs (an Xtream `.m3u8` is retried as `.ts`);
 * - stall recovery: reconnect with backoff, then report failure so the owner can fail over;
 * - live-edge tracking ("Go Live") and a spinner that only appears if buffering lasts.
 *
 * Must be created and used on the main thread.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    profile: BufferProfile = BufferProfile.Balanced,
    /** Several players on screen at once must not all claim audio focus, or each pauses the others. */
    private var ownsAudioFocus: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _ui = MutableStateFlow(PlaybackUi())
    val ui: StateFlow<PlaybackUi> = _ui.asStateFlow()

    var profile: BufferProfile = profile
        private set
    var player: ExoPlayer = buildPlayer(profile)
        private set

    /** Reported when the current stream can't be played after fallbacks and reconnects are exhausted. */
    var onFailure: ((String) -> Unit)? = null
    var onFirstFrame: ((Long) -> Unit)? = null

    private var channel: Channel? = null
    private var candidates: List<String> = emptyList()
    private var candidateIndex = 0
    private var generation = 0
    private var sourceGeneration = 0
    private var loadedAt = 0L
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var stallJob: Job? = null
    private var bufferingJob: Job? = null
    private var monitorJob: Job? = null
    private var hasPlayedItem = false
    private var pausedAt = 0L
    private var engineActive = false
    private var forceDirectPlayback = false
    private var archive = false
    private var recoveryPosition: Long? = null
    private var lastMonitorAt = System.currentTimeMillis()
    private val paceController = PlaybackPaceController()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) = handleState(state)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Native phone controls and audio-focus interruptions must stay in sync with the custom controls.
            if (!playWhenReady) pausedAt = System.currentTimeMillis()
            _ui.update { it.copy(isUserPaused = !playWhenReady) }
            if (playWhenReady && !_ui.value.firstFrameRendered) armWatchdog()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                hasPlayedItem = true
                _ui.update { it.copy(isBuffering = false, showsBufferingIndicator = false, reconnectAttempt = 0) }
                stallJob?.cancel()
            }
        }

        override fun onRenderedFirstFrame() {
            watchdogJob?.cancel()
            bufferingJob?.cancel()
            stallJob?.cancel()
            val elapsed = System.currentTimeMillis() - loadedAt
            _ui.update { it.copy(firstFrameRendered = true, isLoading = false, isBuffering = false, showsBufferingIndicator = false, errorMessage = null) }
            onFirstFrame?.invoke(elapsed)
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            _ui.update { it.copy(videoHeight = size.height, videoWidth = size.width, videoPixelRatio = size.pixelWidthHeightRatio) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val text = options(tracks, C.TRACK_TYPE_TEXT)
            _ui.update { it.copy(audioTracks = options(tracks, C.TRACK_TYPE_AUDIO), textTracks = text, captionsOn = text.any { t -> t.selected }, frameRate = frameRate(tracks)) }
            applyCaptionPreference(text)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!archive && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player.isCurrentMediaItemLive) {
                player.seekToDefaultPosition(); player.prepare(); armWatchdog(); return
            }
            if (engineActive && candidates.size == 1 && error.errorCode in RAW_ENGINE_PARSE_ERRORS) {
                // Some extensionless M3U endpoints are actually HLS or another container. Hand the same URL back to
                // Media3's normal source inference once rather than repeatedly forcing the MPEG-TS extractor.
                forceDirectPlayback = true
                engineActive = false
                hasPlayedItem = false
                prepareCurrent()
                return
            }
            recover(error.errorCodeName, rejected = isHttpRejection(error))
        }
    }

    private val metrics: PlaybackMetrics? = if (com.alexdiab.stadiatv.core.BuildConfig.DEBUG) PlaybackMetrics() else null

    init {
        player.addListener(listener)
        metrics?.let { player.addAnalyticsListener(it) }
        startMonitor()
    }

    // region Public API

    fun load(channel: Channel, isArchive: Boolean = false) {
        generation++
        this.channel = channel
        archive = isArchive
        recoveryPosition = null
        candidates = if (archive) listOf(channel.streamUrl) else urlCandidates(channel)
        candidateIndex = 0
        forceDirectPlayback = false
        hasPlayedItem = false
        metrics?.reset()
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        stallJob?.cancel()
        bufferingJob?.cancel()
        // Explicitly release the old media source. This matters for a manual restart: seeking or replacing an item can
        // otherwise leave the provider's stalled HTTP connection alive until ExoPlayer's loader times it out.
        player.stop()
        player.clearMediaItems()
        _ui.update {
            PlaybackUi(channelKey = channel.key, isLoading = true, isMuted = it.isMuted, qualityCap = it.qualityCap, isArchive = archive)
        }
        prepareCurrent()
    }

    /**
     * Starts the current channel from a completely fresh media request.
     *
     * This is intentionally different from play/pause and "Go Live": providers sometimes leave a connection open
     * after it has stopped delivering frames, so seeking or resuming keeps the same dead request alive. A manual
     * restart tears that request down, clears the recovery state, and rebuilds the source from its first URL.
     */
    fun restart() {
        channel?.let { load(it, archive) }
    }

    /** Debug builds: rebuffers and dropped frames so far for the current stream. */
    fun debugSummary(): String? = metrics?.summary()

    fun togglePlayPause() {
        if (archive && player.playbackState == Player.STATE_ENDED) { player.seekTo(0); player.play(); _ui.update { it.copy(isUserPaused = false, timeline = readTimeline()) }; return }
        if (player.isPlaying || _ui.value.isUserPaused.not() && player.playWhenReady) {
            pausedAt = System.currentTimeMillis()
            player.pause()
            _ui.update { it.copy(isUserPaused = true) }
        } else {
            // An advertised DVR window can be much longer than our normal live buffer. Preserve that intentional
            // delay; Media3 reports BEHIND_LIVE_WINDOW if the paused position really expires.
            if (readTimeline().shouldResetLiveOnResume(System.currentTimeMillis() - pausedAt)) player.seekToDefaultPosition()
            player.play()
            _ui.update { it.copy(isUserPaused = false) }
        }
    }

    fun seekToLiveEdge() {
        if (archive || !player.isCurrentMediaItemLive) return
        player.seekToDefaultPosition()
        player.play()
        _ui.update { it.copy(isBehindLive = false, isUserPaused = false) }
    }

    fun seekTo(position: Long, snapshot: PlaybackTimeline? = null): Boolean {
        val timeline = readTimeline()
        val target = if (snapshot == null) timeline.target(position) else timeline.targetFrom(snapshot, position)
        target ?: return false
        player.seekTo(target)
        _ui.update { it.copy(timeline = readTimeline()) }
        return true
    }

    fun seekBy(delta: Long): Boolean = readTimeline().relativeTarget(delta)?.let { seekTo(it) } ?: false

    private fun readTimeline(): PlaybackTimeline {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        val window = if (player.currentTimeline.isEmpty) null else player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        return PlaybackTimeline(player.currentPosition.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }, duration,
            player.isCurrentMediaItemSeekable && duration > 0, !archive && player.isCurrentMediaItemLive,
            player.currentLiveOffset.takeIf { it != C.TIME_UNSET && it >= 0 && !archive }, window?.windowStartTimeMs?.takeIf { it != C.TIME_UNSET && it >= 0 }, player.playbackState == Player.STATE_ENDED,
            window?.defaultPositionMs?.takeIf { it != C.TIME_UNSET && it >= 0 && duration > 0 })
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        _ui.update { it.copy(isMuted = muted) }
    }

    /** Whether this player is the one the viewer is listening to: it alone holds audio focus and sound. */
    fun setAudioActive(active: Boolean) {
        ownsAudioFocus = active
        player.setAudioAttributes(AUDIO_ATTRIBUTES, active)
        setMuted(!active)
    }

    fun setQualityCap(cap: QualityCap) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, cap.maxHeight)
            .build()
        _ui.update { it.copy(qualityCap = cap) }
    }

    private var audioLanguage = ""

    fun setAudioPreference(language: String) {
        val wanted = PlaybackLanguages.normalize(language)
        if (wanted == audioLanguage) return
        audioLanguage = wanted
        applyAudioParameters()
    }

    fun useAutomaticAudio() { audioLanguage = ""; applyAudioParameters() }

    private fun applyAudioParameters() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).setPreferredAudioLanguage(audioLanguage.takeIf { it.isNotEmpty() })
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
    }

    fun selectAudio(id: String): Boolean {
        val track = _ui.value.audioTracks.firstOrNull { it.id == id } ?: return false
        if (TrackSelectionIdentity.indices(id, sourceGeneration) == null) return false
        setAudioPreference(track.language.orEmpty())
        return select(C.TRACK_TYPE_AUDIO, id)
    }

    /**
     * What the viewer last chose for subtitles. When they are wanted, the best track is switched on as soon as a stream
     * offers any: the one in their language, else the first.
     */
    fun setCaptionPreference(enabled: Boolean, language: String) {
        val wanted = PlaybackLanguages.normalize(language)
        if (captionsWanted == enabled && captionLanguage == wanted) return
        captionsWanted = enabled
        captionLanguage = wanted
        applyTextParameters()
        applyCaptionPreference(_ui.value.textTracks)
    }

    private var captionsWanted = false
    private var captionLanguage = ""

    private fun applyCaptionPreference(tracks: List<TrackOption>) {
        if (!captionsWanted || tracks.isEmpty()) return
        val pick = PlaybackLanguages.preferred(tracks, captionLanguage) ?: return
        if (!pick.selected) select(C.TRACK_TYPE_TEXT, pick.id)
    }

    private fun applyTextParameters() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsWanted)
            .setPreferredTextLanguage(captionLanguage.takeIf { it.isNotEmpty() }).setSelectUndeterminedTextLanguage(captionsWanted && captionLanguage.isEmpty()).build()
    }

    /** `null` turns subtitles off. */
    fun selectText(id: String?): Boolean {
        if (id == null) {
            captionsWanted = false
            applyTextParameters()
        } else {
            val track = _ui.value.textTracks.firstOrNull { it.id == id } ?: return false
            if (TrackSelectionIdentity.indices(id, sourceGeneration) == null) return false
            captionsWanted = true
            captionLanguage = PlaybackLanguages.normalize(track.language)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            return select(C.TRACK_TYPE_TEXT, id)
        }
        return true
    }

    fun setProfile(newProfile: BufferProfile) {
        if (newProfile == profile) return
        val current = channel
        val wasMuted = _ui.value.isMuted
        val cap = _ui.value.qualityCap
        val position = player.currentPosition
        val wasPaused = _ui.value.isUserPaused
        player.removeListener(listener)
        player.release()
        profile = newProfile
        player = buildPlayer(newProfile)
        player.addListener(listener)
        metrics?.let { player.addAnalyticsListener(it) }
        setMuted(wasMuted)
        setQualityCap(cap)
        current?.let { load(it, archive); if (archive) player.seekTo(position); if (wasPaused) player.pause() }
    }

    fun stop() {
        generation++
        sourceGeneration++
        reconnectJob?.cancel(); watchdogJob?.cancel(); stallJob?.cancel(); bufferingJob?.cancel()
        player.stop()
        player.clearMediaItems()
        paceController.reset()?.let { player.playbackParameters = PlaybackParameters(it) }
        engineActive = false
        archive = false; recoveryPosition = null
        _ui.update { it.copy(isLoading = false, isBuffering = false, showsBufferingIndicator = false, engineActive = false, enginePhase = EnginePhase.OFF, engineNotice = null, reserveSeconds = 0, playbackRate = 1f, timeline = PlaybackTimeline(), isArchive = false) }
    }

    fun release() {
        onFailure = null
        onFirstFrame = null
        player.removeListener(listener)
        scope.cancel()
        player.release()
    }

    // endregion

    // region Internals

    private fun buildPlayer(profile: BufferProfile): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.minMs, profile.maxMs, profile.startMs, profile.rebufferMs)
            // Fire TV sticks share 1-2 GB with the video decoder: cap the buffer by size too.
            .setTargetBufferBytes(profile.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10_000, false)
            .build()
        return ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(AUDIO_ATTRIBUTES, ownsAudioFocus)
            .build()
            .apply {
                playWhenReady = true
                // Captions start off (a stream's default track would otherwise draw over the picture);
                // the Options panel turns them on.
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
    }

    private fun prepareCurrent() {
        val channel = this.channel ?: return
        val url = candidates.getOrNull(candidateIndex) ?: return
        val headers = StreamHeaders.merged(channel.headers, null)
        val userAgent = StreamHeaders.userAgent(headers) ?: DEFAULT_USER_AGENT
        val requestHeaders = headers.filterKeys { !it.equals(StreamHeaders.USER_AGENT, ignoreCase = true) }
        val token = generation
        val route = candidateIndex
        val sourceToken = ++sourceGeneration
        // Overrides describe a particular source's groups. Only language preferences cross a reload/reconnect.
        applyAudioParameters()
        applyTextParameters()
        // A server that answers a never-played stream with an error status (403, 404, 503 ...) will answer the same way
        // again: skip ExoPlayer's three retries so the next copy of the channel is tried in about a second, not seconds.
        val policy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val error = loadErrorInfo.exception
                if (!hasPlayedItem && error is HttpDataSource.InvalidResponseCodeException && error.responseCode in 400..599 && error.responseCode != 429) return C.TIME_UNSET
                return super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        val source = if (!archive && isRawTransport(url) && !forceDirectPlayback) {
            engineActive = true
            _ui.update { it.copy(engineActive = true, enginePhase = EnginePhase.STARTING, engineNotice = "Starting resilience engine") }
            val dataSource = ResilientTsDataSource.Factory(requestHeaders, userAgent, profile.engine, object : StreamEngineListener {
                override fun onSnapshot(snapshot: StreamEngineSnapshot) {
                    if (token != generation || route != candidateIndex || sourceToken != sourceGeneration) return
                    _ui.update {
                        it.copy(
                            engineActive = true,
                            enginePhase = snapshot.phase,
                            engineNotice = snapshot.notice,
                            reconnectAttempt = snapshot.reconnectAttempt,
                        )
                    }
                }
            })
            val extractors = DefaultExtractorsFactory().setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            ProgressiveMediaSource.Factory(dataSource, extractors)
                .setLoadErrorHandlingPolicy(policy)
                .createMediaSource(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build())
        } else {
            engineActive = false
            paceController.reset()?.let { player.playbackParameters = PlaybackParameters(it) }
            _ui.update { it.copy(engineActive = false, enginePhase = EnginePhase.OFF, engineNotice = null, playbackRate = 1f) }
            val dataSource = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(12_000)
                .setDefaultRequestProperties(requestHeaders)
            DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(policy).createMediaSource(MediaItem.fromUri(url))
        }
        loadedAt = System.currentTimeMillis()
        player.setMediaSource(source)
        recoveryPosition?.let { player.seekTo(it) }
        player.prepare()
        player.playWhenReady = !_ui.value.isUserPaused
        armWatchdog()
    }

    private fun armWatchdog() {
        watchdogJob?.cancel()
        val token = generation
        watchdogJob = scope.launch {
            delay(profile.startupTimeoutMs)
            if (token == generation && PlaybackStartupPolicy.shouldRecover(_ui.value.firstFrameRendered, player.isPlaying, _ui.value.isUserPaused)) recover("startup timeout")
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                _ui.update { it.copy(isBuffering = true) }
                bufferingJob?.cancel()
                bufferingJob = scope.launch {
                    delay(BUFFERING_INDICATOR_MILLIS)
                    _ui.update { it.copy(showsBufferingIndicator = true) }
                }
                if (hasPlayedItem) {
                    stallJob?.cancel()
                    val token = generation
                    stallJob = scope.launch {
                        delay(if (engineActive) profile.engineStarvedFailoverMillis else STALL_RECONNECT_MILLIS)
                        if (token == generation && player.playbackState == Player.STATE_BUFFERING) recover("stalled")
                    }
                }
            }
            Player.STATE_READY -> {
                bufferingJob?.cancel(); stallJob?.cancel()
                _ui.update { it.copy(isBuffering = false, showsBufferingIndicator = false, isLoading = false) }
            }
            Player.STATE_ENDED -> if (!archive && (player.isCurrentMediaItemLive || hasPlayedItem)) recover("ended") else {
                watchdogJob?.cancel(); bufferingJob?.cancel(); stallJob?.cancel()
                _ui.update { it.copy(isLoading = false, isBuffering = false, showsBufferingIndicator = false, timeline = readTimeline()) }
            }
            else -> Unit
        }
    }

    /** Fallback URL first, then reconnect with backoff, then give up and tell the owner. */
    private fun recover(reason: String, rejected: Boolean = false) {
        if (isReleased) return
        val token = generation
        watchdogJob?.cancel(); stallJob?.cancel()
        if (archive && hasPlayedItem) recoveryPosition = player.currentPosition.coerceAtLeast(0)
        if (!hasPlayedItem && candidateIndex + 1 < candidates.size) {
            candidateIndex++
            prepareCurrent()
            return
        }
        // The server answered "no" (403, 404, 503 ...) to a stream that never played: retrying it will not help, and
        // the owner has other copies of the channel to try, so hand over now instead of waiting out four reconnects.
        if (rejected && !hasPlayedItem) {
            _ui.update { it.copy(isLoading = false, isBuffering = false, showsBufferingIndicator = false, reconnectAttempt = 0, errorMessage = "This stream isn't available.") }
            onFailure?.invoke("This stream isn't available.")
            return
        }
        val attempt = _ui.value.reconnectAttempt + 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            _ui.update { it.copy(isLoading = false, isBuffering = false, showsBufferingIndicator = false, reconnectAttempt = 0, errorMessage = "This stream isn't responding.") }
            onFailure?.invoke("This stream isn't responding.")
            return
        }
        _ui.update { it.copy(reconnectAttempt = attempt, showsBufferingIndicator = true) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay((RECONNECT_DELAYS_MILLIS[minOf(attempt - 1, RECONNECT_DELAYS_MILLIS.lastIndex)]))
            if (token != generation) return@launch
            candidateIndex = 0
            hasPlayedItem = false
            prepareCurrent()
        }
    }

    /** True when the provider refused the request (an HTTP error status), as opposed to the network dropping or stalling. */
    private fun isHttpRejection(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode in 400..599 && cause.responseCode != 429) return true
            cause = cause.cause
        }
        return false
    }

    private val isReleased: Boolean get() = !scope.coroutineContext[Job]!!.isActive

    private fun startMonitor() {
        monitorJob = scope.launch {
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastMonitorAt) / 1_000.0
                lastMonitorAt = now
                val reserve = (player.totalBufferedDuration / 1_000L).toInt().coerceAtLeast(0)
                val timeline = readTimeline()
                if (_ui.value.timeline != timeline) _ui.update { it.copy(timeline = timeline) }
                if (_ui.value.reserveSeconds != reserve) _ui.update { it.copy(reserveSeconds = reserve) }
                if (engineActive && player.playbackState != Player.STATE_IDLE && !_ui.value.isUserPaused) {
                    val phase = _ui.value.enginePhase
                    val outage = phase == EnginePhase.RECONNECTING || phase == EnginePhase.WAITING
                    paceController.update(reserve.toDouble(), outage, profile.engine, elapsed)?.let { rate ->
                        player.playbackParameters = PlaybackParameters(rate)
                        _ui.update { it.copy(playbackRate = rate) }
                    }
                } else if (!engineActive) {
                    paceController.reset()?.let { rate ->
                        player.playbackParameters = PlaybackParameters(rate)
                        _ui.update { it.copy(playbackRate = rate) }
                    }
                }
                if (!archive && player.isCurrentMediaItemLive) {
                    val behind = timeline.behindLive
                    if (behind != _ui.value.isBehindLive) _ui.update { it.copy(isBehindLive = behind) }
                }
            }
        }
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val closedCaption = format.sampleMimeType == MimeTypes.APPLICATION_CEA608 || format.sampleMimeType == MimeTypes.APPLICATION_CEA708
                val language = format.language?.takeIf { it != "und" }
                val label = listOfNotNull(
                    format.label?.takeIf { it.isNotBlank() },
                    language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.replaceFirstChar(Char::uppercase) },
                    if (closedCaption) "Closed captions" + format.accessibilityChannel.takeIf { it > 0 }?.let { " CC$it" }.orEmpty() else null,
                    format.channelCount.takeIf { type == C.TRACK_TYPE_AUDIO && it > 2 }?.let { "$it ch" },
                ).distinct().joinToString(" · ").ifBlank { if (type == C.TRACK_TYPE_TEXT) "Subtitles ${out.size + 1}" else "Track ${out.size + 1}" }
                out += TrackOption(TrackSelectionIdentity.encode(sourceGeneration, groupIndex, trackIndex), label, group.isTrackSelected(trackIndex), language)
            }
        }
        return out
    }

    private fun frameRate(tracks: Tracks): Float {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) if (group.isTrackSelected(i)) return group.getTrackFormat(i).frameRate.takeIf { it > 0f } ?: 0f
        }
        return 0f
    }

    private fun select(type: Int, id: String): Boolean {
        val (groupIndex, trackIndex) = TrackSelectionIdentity.indices(id, sourceGeneration) ?: return false
        val group = player.currentTracks.groups.getOrNull(groupIndex)?.takeIf { it.type == type } ?: return false
        if (trackIndex !in 0 until group.length || !group.isTrackSupported(trackIndex)) return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
        return true
    }

    /** Raw Xtream transport is the resilient primary; HLS remains the standards-based fallback. */
    private fun urlCandidates(channel: Channel): List<String> {
        val url = channel.streamUrl
        if (channel.xtreamStreamId != null) {
            return when {
                url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) -> listOf(url.replacePathExtension("m3u8", "ts"), url)
                url.substringBefore('?').endsWith(".ts", ignoreCase = true) -> listOf(url, url.replacePathExtension("ts", "m3u8"))
                else -> listOf(url)
            }.distinct()
        }
        return listOf(url)
    }

    /** Live M3U feeds are often extensionless PHP endpoints; only known adaptive/finite formats bypass the TS engine. */
    private fun isRawTransport(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return RAW_ENGINE_EXCLUSIONS.none { path.endsWith(".$it") }
    }

    private fun String.replacePathExtension(from: String, to: String): String {
        val query = substringAfter('?', missingDelimiterValue = "").takeIf { '?' in this }
        val path = substringBefore('?').removeSuffix(".$from") + ".$to"
        return if (query == null) path else "$path?$query"
    }

    // endregion

    companion object {
        const val MAX_RECONNECT_ATTEMPTS = 4
        private val RECONNECT_DELAYS_MILLIS = longArrayOf(500, 1_000, 2_000, 4_000)
        private const val STALL_RECONNECT_MILLIS = 6_000L
        private const val BUFFERING_INDICATOR_MILLIS = 700L
        private const val DEFAULT_USER_AGENT = "StadiaTV-FireTV/2.0 (Android TV)"
        private val RAW_ENGINE_EXCLUSIONS = setOf("m3u8", "m3u", "mpd", "ism", "f4m", "mp4", "mkv", "avi", "mov", "m4v", "webm", "mp3", "aac", "m4a", "flac", "ogg", "wav")
        private val RAW_ENGINE_PARSE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
    }
}

private val BufferProfile.engineStarvedFailoverMillis: Long
    get() = when (this) {
        BufferProfile.Fast -> 15_000L
        BufferProfile.Balanced -> 25_000L
        BufferProfile.Stable -> 40_000L
    }
