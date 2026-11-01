package com.alexdiab.stadiatv.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.AspectMode
import com.alexdiab.stadiatv.playback.BufferProfile
import com.alexdiab.stadiatv.playback.PlaybackController
import com.alexdiab.stadiatv.playback.ScoreBuffer
import com.alexdiab.stadiatv.playback.VideoSizing
import com.alexdiab.stadiatv.playback.PlaybackLanguages
import com.alexdiab.stadiatv.ui.fantasy.FantasyPanel
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs

internal enum class PlayerPanelKind { Channels, Guide, Recents, Options, Sports, Fantasy }

/** What each direction does while the controls are hidden. Select shows the controls; Back closes whatever is open first. */
internal object PlayerRemote {
    /** Up is "what is this": the scoreboard when a game is live, otherwise what is on and next. Down is the channel switcher. */
    fun panelFor(key: Key, hasLiveGame: Boolean): PlayerPanelKind? = when (key) {
        Key.DirectionUp -> if (hasLiveGame) PlayerPanelKind.Sports else PlayerPanelKind.Guide
        Key.DirectionDown, Key.DirectionLeft -> PlayerPanelKind.Channels
        Key.DirectionRight -> PlayerPanelKind.Guide
        else -> null
    }
}

/**
 * The full-screen player, the centrepiece of the app. With the controls hidden: OK shows them, up shows the scoreboard
 * (or what is on), down opens the channel switcher; Back closes a panel, then the controls, then leaves.
 */
@Composable
fun PlayerScreen(initialChannel: Channel, initialMatch: Match?) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val raw by container.playlists.state.collectAsStateWithLifecycle()
    val lineupState by container.lineup.lineup.collectAsStateWithLifecycle()
    val lineupPrefs by container.lineup.prefs.collectAsStateWithLifecycle()
    val fantasy by container.fantasy.state.collectAsStateWithLifecycle()

    val controller = remember { PlaybackController(context, BufferProfile.entries.firstOrNull { it.name == settings.playerBufferProfile } ?: BufferProfile.Balanced) }
    val playback by controller.ui.collectAsStateWithLifecycle()

    var channel by remember { mutableStateOf(initialChannel) }
    // The channels to flip through: what the viewer was browsing in the guide, else the whole playlist.
    val zap = remember {
        container.lineup.zapChannels.takeIf { list -> list.any { it.key == initialChannel.key } } ?: raw.channels
    }
    val lineup = remember(zap, raw) { raw.copy(channels = zap) }
    val entry = remember(channel.key, lineupState) { lineupState.entryFor(channel.key) }
    val tried = remember { mutableSetOf<String>() }
    val currentChannel by rememberUpdatedState(channel)
    var previousChannel by remember { mutableStateOf<Channel?>(null) }
    var panel by remember { mutableStateOf<PlayerPanelKind?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableStateOf(container.playerPrefs.aspect(initialChannel.key)) }
    var liveMatch by remember { mutableStateOf<Match?>(null) }
    var scoreTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scoreBuffer = remember { ScoreBuffer() }
    val rootFocus = remember { FocusRequester() }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (!playback.isUserPaused) controller.player.play() }

    fun reveal() {
        chromeVisible = true
        interaction++
    }

    fun switchChannel(selected: Channel) {
        if (selected.key == channel.key) return
        tried.clear()
        tried += selected.key
        previousChannel = channel
        channel = selected
        aspect = container.playerPrefs.aspect(selected.key)
        liveMatch = null
        scoreBuffer.clear()
        controller.load(selected)
        container.watchHistory.record(selected)
        panel = null
        reveal()
    }

    fun adjacent(offset: Int) {
        val index = lineup.channels.indexOfFirst { it.key == channel.key }
        lineup.channels.getOrNull(index + offset)?.takeIf { index >= 0 }?.let(::switchChannel)
    }

    fun restartStream() {
        // A manual restart should also make every backup eligible again if this copy fails afterwards.
        tried.clear()
        tried += channel.key
        controller.restart()
        reveal()
    }

    LaunchedEffect(Unit) {
        controller.load(initialChannel)
        container.watchHistory.record(initialChannel)
        tried += initialChannel.key
    }
    // A stream that stops responding is replaced by the next copy of the same channel (the other quality, the other
    // country's feed, a backup), so the viewer rarely sees a failure while any copy works.
    DisposableEffect(controller) {
        controller.onFailure = {
            val current = currentChannel
            val next = lineupState.entryFor(current.key)?.streamsInOrder(lineupPrefs)?.firstOrNull { it.channel.key !in tried }?.channel
            if (next != null) {
                tried += next.key
                channel = next
                controller.load(next)
            }
        }
        onDispose { controller.onFailure = null }
    }
    LaunchedEffect(settings.captionsEnabled, settings.captionLanguage) {
        controller.setCaptionPreference(settings.captionsEnabled, settings.captionLanguage)
    }
    LaunchedEffect(settings.preferredAudioLanguage) { controller.setAudioPreference(settings.preferredAudioLanguage) }
    LaunchedEffect(settings.playerBufferProfile) {
        BufferProfile.entries.firstOrNull { it.name == settings.playerBufferProfile }?.let(controller::setProfile)
    }
    LaunchedEffect(aspect, channel.key) { container.playerPrefs.setAspect(channel.key, aspect) }

    // Controls auto-hide after a quiet period, unless a panel is open or the stream is paused.
    LaunchedEffect(chromeVisible, interaction, panel, locked) {
        if (chromeVisible && panel == null && !locked) {
            delay(settings.playerPanelTimeoutSeconds * 1000L)
            chromeVisible = false
        }
    }
    // Keep the remote working: focus lives on the root while the controls are hidden.
    LaunchedEffect(chromeVisible, panel, locked) {
        if ((!chromeVisible && panel == null) || locked) runCatching { rootFocus.requestFocus() }
    }

    // Live score for the channel being watched.
    val delaySeconds = settings.sportsScoreDelaySeconds
    LaunchedEffect(channel.key) {
        val feed = container.matchFeed.sections.value
        val explicit = initialMatch?.takeIf { channel.key == initialChannel.key && it.state == GameState.LIVE && abs(it.startMillis - System.currentTimeMillis()) < TWELVE_HOURS }
        val confirmed = explicit ?: feed.liveNow.filter { match ->
            abs(match.startMillis - System.currentTimeMillis()) < TWELVE_HOURS &&
                container.streams.sourcesFor(match).sources.any { it.channel.key == channel.key && it.isConfirmed }
        }.singleOrNull()
        var match = confirmed ?: return@LaunchedEffect
        liveMatch = match
        scoreBuffer.receive(match)
        while (true) {
            delay(SCORE_POLL_MILLIS)
            try {
                val updated = container.sports.scoreboard(match.league).firstOrNull { it.id == match.id } ?: continue
                if (updated.state != GameState.LIVE) { liveMatch = null; scoreBuffer.receive(null); return@LaunchedEffect }
                match = container.sports.enrich(updated)
                liveMatch = match
                scoreBuffer.receive(match)
                scoreTick = System.currentTimeMillis()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // Keep showing the last score; the next poll tries again.
            }
        }
    }
    LaunchedEffect(delaySeconds) {
        if (delaySeconds > 0) while (true) { scoreTick = System.currentTimeMillis(); delay(5_000) }
    }
    val displayedMatch = if (settings.spoilerFreeMode || !settings.showLiveScoreBadge) null
    else remember(liveMatch, scoreTick, delaySeconds) { scoreBuffer.snapshot(delaySeconds) }

    BackHandler {
        when {
            locked -> { locked = false; reveal() }
            panel != null -> { panel = null; reveal() }
            chromeVisible -> chromeVisible = false
            else -> navigator.pop()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                if (event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause) {
                    if (!locked) { controller.togglePlayPause(); reveal() }
                    return@onPreviewKeyEvent true
                }
                if (locked) return@onPreviewKeyEvent true
                if (!chromeVisible && panel == null) {
                    val directed = PlayerRemote.panelFor(event.key, hasLiveGame = liveMatch != null && !settings.spoilerFreeMode)
                    if (directed != null) { panel = directed; return@onPreviewKeyEvent true }
                    when (event.key) {
                        Key.ChannelUp -> adjacent(-1)
                        Key.ChannelDown -> adjacent(1)
                        else -> reveal()
                    }
                    return@onPreviewKeyEvent true
                }
                false
            },
    ) {
        VideoSurface(controller, aspect)
        PlaybackStatus(playback, onRetry = ::restartStream, onChannels = { panel = PlayerPanelKind.Channels })

        if (displayedMatch != null) ScoreBadge(displayedMatch, delaySeconds, Modifier.align(Alignment.TopCenter))

        AnimatedVisibility(visible = chromeVisible && !locked && panel == null, enter = fadeIn(), exit = fadeOut()) {
            PlayerChrome(
                channel = channel,
                playback = playback,
                liveMatch = displayedMatch,
                hasPrevious = previousChannel != null,
                hasMatch = liveMatch != null && !settings.spoilerFreeMode,
                hasFantasy = fantasy.isConnected && !settings.spoilerFreeMode,
                onBack = { navigator.pop() },
                onTogglePlay = { controller.togglePlayPause(); reveal() },
                onPrevious = { adjacent(-1) },
                onNext = { adjacent(1) },
                onRestart = ::restartStream,
                onOpen = { panel = it },
                onGoLive = { controller.seekToLiveEdge(); reveal() },
                onLast = { previousChannel?.let(::switchChannel) },
                onMute = { controller.setMuted(!playback.isMuted); reveal() },
                onCaptions = {
                    if (playback.captionsOn) {
                        controller.selectText(null)
                        container.preferences.update { it.copy(captionsEnabled = false) }
                    } else {
                        val track = playback.textTracks.firstOrNull { t -> settings.captionLanguage.isNotBlank() && t.language?.startsWith(settings.captionLanguage, ignoreCase = true) == true } ?: playback.textTracks.firstOrNull()
                        if (track != null) {
                            controller.selectText(track.id)
                            container.preferences.update { it.copy(captionsEnabled = true, captionLanguage = track.language ?: it.captionLanguage) }
                        }
                    }
                    reveal()
                },
                onInteract = { interaction++ },
            )
        }

        if (locked) UnlockHint(onUnlock = { locked = false; reveal() })

        AnimatedVisibility(
            visible = panel != null,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            when (panel ?: PlayerPanelKind.Channels) {
                PlayerPanelKind.Channels -> ChannelsPanel(channel, lineup.channels, onSelect = ::switchChannel, onClose = { panel = null })
                PlayerPanelKind.Guide -> GuidePanel(channel, lineup.channels, onSelect = ::switchChannel, onClose = { panel = null })
                PlayerPanelKind.Recents -> RecentsPanel(channel, lineup.channels, onSelect = ::switchChannel, onClose = { panel = null })
                PlayerPanelKind.Options -> OptionsPanel(
                    channel = channel, playback = playback, aspect = aspect, profile = settings.playerBufferProfile,
                    isFavorite = channel.key in settings.favoriteChannelIds,
                    onAspect = { aspect = it },
                    onQuality = controller::setQualityCap,
                    onAudio = { id -> if (controller.selectAudio(id)) container.preferences.update { it.copy(preferredAudioLanguage = PlaybackLanguages.normalize(playback.audioTracks.firstOrNull { track -> track.id == id }?.language)) } },
                    onText = { id ->
                        controller.selectText(id)
                        val chosen = playback.textTracks.firstOrNull { it.id == id }
                        container.preferences.update { it.copy(captionsEnabled = id != null, captionLanguage = chosen?.language ?: it.captionLanguage) }
                    },
                    entry = entry,
                    prefs = lineupPrefs,
                    onStream = { stream -> tried.clear(); tried += stream.channel.key; channel = stream.channel; controller.load(stream.channel); panel = null; reveal() },
                    onPreferHd = { value ->
                        container.preferences.update { it.copy(preferHdStreams = value) }
                        entry?.pick(lineupPrefs.let { p -> com.alexdiab.stadiatv.lineup.LineupPrefs(value, p.preferUhd, p.homeCountry) })?.channel?.takeIf { it.key != channel.key }?.let { next ->
                            tried.clear(); tried += next.key; channel = next; controller.load(next)
                        }
                    },
                    onProfile = { profile -> container.preferences.update { it.copy(playerBufferProfile = profile.name) } },
                    onFavorite = { container.preferences.toggleFavoriteChannel(channel.key) },
                    onLock = { panel = null; locked = true; chromeVisible = false },
                    // Hand over rather than stack: two playing screens would double the decoders in use.
                    onMultiview = { navigator.replaceTop(Route.Multiview(listOf(channel))) },
                    onClose = { panel = null },
                )
                PlayerPanelKind.Fantasy -> FantasyPanel()
                PlayerPanelKind.Sports -> liveMatch?.let { match ->
                    SportsPanel(match, container.streams.sourcesFor(match).sources, currentKey = channel.key, onSelect = { switchChannel(it) }, onClose = { panel = null })
                } ?: ChannelsPanel(channel, lineup.channels, onSelect = ::switchChannel, onClose = { panel = null })
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun VideoSurface(controller: PlaybackController, aspect: AspectMode, modifier: Modifier = Modifier.fillMaxSize()) {
    val playback by controller.ui.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
    val frame = VideoSizing.original(playback.videoWidth, playback.videoHeight, playback.videoPixelRatio, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
    val videoModifier = if (aspect == AspectMode.Original) with(density) { Modifier.size(frame.width.toDp(), frame.height.toDp()) } else Modifier.fillMaxSize()
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                isFocusable = false
                isFocusableInTouchMode = false
                this.player = controller.player
            }
        },
        update = { view ->
            if (view.player !== controller.player) view.player = controller.player
            view.resizeMode = when (aspect) {
                AspectMode.Fit, AspectMode.Original -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.Fill -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        },
        onRelease = { it.player = null },
        modifier = videoModifier,
    )
    }
}

private const val TWELVE_HOURS = 12 * 3600_000L
private const val SCORE_POLL_MILLIS = 30_000L
