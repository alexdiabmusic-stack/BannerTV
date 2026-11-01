@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexdiab.stadiatv.mobile

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.*
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun PhonePlayer(model: MobileViewModel, activity: MobileActivity) {
    val channel = model.channel ?: return
    val playback by model.controller.ui.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val feed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pip = activity.inPictureInPicture
    val accessibilityManager = remember { activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    var accessible by remember { mutableStateOf(accessibilityManager.isTouchExplorationEnabled) }
    DisposableEffect(accessibilityManager) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { accessible = it }
        accessibilityManager.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibilityManager.removeTouchExplorationStateChangeListener(listener) }
    }
    val view = LocalView.current
    var visible by rememberSaveable { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf(false) }
    var aspect by remember(channel.key) { mutableStateOf(model.container.playerPrefs.aspect(channel.key)) }
    var hud by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val audio = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val originalBrightness = remember { activity.window.attributes.screenBrightness }
    var brightness by remember { mutableFloatStateOf(originalBrightness.takeIf { it >= 0 } ?: 0.5f) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)) }
    val game = model.playingMatch?.let { initial -> feed.all.firstOrNull { it.key == initial.key } ?: initial }
    val scoreBuffer = remember(channel.key) { ScoreBuffer() }
    LaunchedEffect(game) { scoreBuffer.receive(game?.takeIf { it.state == GameState.LIVE }) }
    val score = remember(tick, game, settings.sportsScoreDelaySeconds) { scoreBuffer.snapshot(settings.sportsScoreDelaySeconds) }
    val programme = model.replay?.programme ?: remember(channel.key, guide.revision, tick / 30_000) { model.container.guide.nowAndNext(channel).first }
    val quickActions = remember(library.playerBarActions) { PlayerQuickActions.normalize(library.playerBarActions) }
    val availableSources = model.playerSources()
    val alternateSources = phoneAlternateSources(channel, availableSources, model.replay != null)
    val options = availableSources.filter { source -> source.key == channel.key || alternateSources.any { it.key == source.key } }
        .distinctBy { it.key }.let { ordered -> if (ordered.any { it.key == channel.key }) ordered else listOf(channel) + ordered }
    val presentation = phonePlaybackPresentation(playback)
    LaunchedEffect(channel.key, feed.updatedAt, sources, tick / 30_000, guide.revision) { model.resolvePlayerMatch() }
    LaunchedEffect(channel.key) { visible = true; locked = false; panel = null; interaction++ }
    LaunchedEffect(presentation.recoveryRequired) {
        if (presentation.recoveryRequired) { locked = false; visible = true; interaction++ }
    }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick = System.currentTimeMillis() } }
    LaunchedEffect(hud) { if (hud != null) { delay(1_500); hud = null } }
    LaunchedEffect(visible, interaction, playback.isUserPaused, playback.isBuffering, playback.errorMessage, playback.timeline.ended, panel, accessible, seeking) {
        if (visible && !playback.isUserPaused && !playback.isBuffering && playback.errorMessage == null && !playback.timeline.ended && panel == null && !accessible && !seeking) {
            delay(settings.playerPanelTimeoutSeconds.coerceIn(3, 30) * 1_000L); visible = false
        }
    }
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }; activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(landscape, pip) {
        val systemBars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (landscape || pip) { systemBars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; systemBars.hide(WindowInsetsCompat.Type.systemBars()) }
        onDispose { systemBars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    fun interact() { interaction++; visible = true }
    fun setAspect(value: AspectMode) { aspect = value; model.container.playerPrefs.setAspect(channel.key, value); interact() }
    fun open(value: String) { panel = value; interact() }
    fun doubleTap(right: Boolean) {
        if (locked) return
        if (settings.doubleTapSeeks) {
            if (model.controller.seekBy(if (right) 10_000 else -10_000)) {
                hud = if (right) "+10 seconds" else "−10 seconds"
            } else hud = "This stream doesn't support seeking"
        } else model.adjacent(if (right) 1 else -1)
        interact()
    }
    BackHandler {
        when {
            panel != null -> panel = null
            locked -> { locked = false; interact() }
            phonePlayerBackRotates(landscape, activity.isInMultiWindowMode, configuration.smallestScreenWidthDp) -> activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> model.fullPlayer = false
        }
    }
    val shown = visible || playback.isUserPaused || playback.errorMessage != null || playback.timeline.ended || accessible
    val companionKey = remember(game?.key, channel.key) { phonePlayerCompanionKey(game?.key, channel.key) }
    PhonePlayerLayout(Modifier.fillMaxSize().background(Color.Black).then(if (landscape || pip) Modifier else Modifier.statusBarsPadding()),
        landscape = landscape, pip = pip, attachedMatch = game != null, gamePanel = panel == "Game", companionKey = companionKey, video = {
            Box(Modifier.fillMaxSize()) {
                PlayerVideo(model.controller, Modifier.fillMaxSize(), showNativeControls = false, aspect = aspect)
                if (!pip) {
                    Box(Modifier.fillMaxSize()
                        .semantics { contentDescription = if (locked) "Player controls locked" else "Video player"; if (!presentation.recoveryRequired) onClick("Show player controls") { interact(); true } }
                        .pointerInput(channel.key, locked, presentation.recoveryRequired) { if (!presentation.recoveryRequired) detectTapGestures(onTap = { visible = !visible; interaction++ }, onDoubleTap = { doubleTap(it.x > size.width / 2) }) }
                        .phoneVideoGestures(channel.key, landscape, locked || accessible || presentation.recoveryRequired, onAdjustment = { left, delta ->
                            if (left) { brightness = (brightness - delta).coerceIn(.02f, 1f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }; hud = "Brightness ${(brightness * 100).toInt()}%" }
                            else { volume = (volume - delta).coerceIn(0f, 1f); audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0); hud = "Volume ${(volume * 100).toInt()}%" }
                        }, onMinimize = { model.fullPlayer = false }, onFullscreen = { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE },
                            onPinch = { scale -> PlayerGesturePolicy.pinch(aspect, scale)?.let { setAspect(it) } }, onInteraction = { active -> seeking = active; interact() }))
                    if (!presentation.recoveryRequired && settings.showLiveScoreBadge && !settings.spoilerFreeMode && score != null) Surface(color = Color(0xD9141923), shape = RoundedCornerShape(10.dp), modifier = Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = if (shown && !locked) 70.dp else 12.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val sides = score.displaySides
                            Logo(sides.first().logoUrl, sides.first().abbreviation, 20.dp); Text(sides.joinToString("  ·  ") { "${it.abbreviation} ${it.score ?: "–"}" }, color = Color.White, style = MaterialTheme.typography.labelLarge); Logo(sides.last().logoUrl, sides.last().abbreviation, 20.dp)
                            if (landscape) Text(score.live.situation.joinToString(" · ").ifBlank { score.statusDetail }, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    PlayerChromeVisibility(shown && !locked && !presentation.recoveryRequired) {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xC0000000), Color.Transparent, Color(0xD9000000))))) {
                            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ChromeButton(Icons.AutoMirrored.Rounded.ArrowBack, "Minimize player") { model.fullPlayer = false }
                                if (landscape) Logo(channel.logoUrl, channel.name, 32.dp)
                                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                    Text(channel.canonicalName ?: channel.name, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text((if (model.replay != null) "Replay · " else "") + (programme?.title ?: game?.shortName.orEmpty()), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                PhoneCastButton(model, onDarkVideo = true)
                                ChromeButton(if (landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Toggle landscape") { activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
                                ChromeButton(Icons.Rounded.MoreHoriz, "Player options") { open("Options") }
                            }
                            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (landscape) ChromeButton(Icons.Rounded.SkipPrevious, "Previous channel", enabled = model.adjacentChannel(-1) != null) { model.adjacent(-1); interact() }
                                FilledIconButton(onClick = { model.controller.togglePlayPause(); interact() }, Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White)) { Icon(if (playback.timeline.ended) Icons.Rounded.Replay else if (playback.isUserPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (playback.timeline.ended) "Replay from start" else if (playback.isUserPaused) "Play" else "Pause", Modifier.size(32.dp)) }
                                if (landscape) ChromeButton(Icons.Rounded.SkipNext, "Next channel", enabled = model.adjacentChannel(1) != null) { model.adjacent(1); interact() }
                            }
                            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (landscape) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                                    quickActions.forEach { action -> TextButton(onClick = { if (action == "PiP") activity.enterPlayerPip() else open(action) }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(action, style = MaterialTheme.typography.labelMedium) } }
                                    ChromeButton(Icons.Rounded.BarChart, "Game information") { panel = if (panel == "Game") null else "Game"; interact() }
                                }
                                PhoneSeekBar(playback, model.controller) { seeking = it; interact() }
                                programme?.takeIf { model.replay == null && !playback.timeline.seekable }?.let { p -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(clockTime(p.startMillis), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                                    LinearProgressIndicator(progress = { p.progress(tick) }, Modifier.weight(1f).height(2.dp), color = BannerLive, trackColor = Color.White.copy(alpha = 0.25f))
                                    Text(clockTime(p.endMillis), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                                } }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    if (model.replay != null) TextButton(onClick = { model.returnToLive(); interact() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Return to live", style = MaterialTheme.typography.labelLarge) }
                                    else if (playback.isBehindLive && playback.timeline.live) TextButton(onClick = { model.controller.seekToLiveEdge(); interact() }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Go Live", style = MaterialTheme.typography.labelLarge) }
                                    else Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (presentation.phase == PhonePlaybackPhase.Playing) { Box(Modifier.size(6.dp).background(BannerLive, CircleShape)); Spacer(Modifier.width(6.dp)) }
                                        Text(if (presentation.phase == PhonePlaybackPhase.Playing) "LIVE" else presentation.statusLabel, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                    if (alternateSources.isNotEmpty()) TextButton(onClick = { open("Sources") }, Modifier.weight(1f).pointerInput(channel.key, options, locked) {
                                        var moved = 0f
                                        detectHorizontalDragGestures(onDragStart = { moved = 0f }, onHorizontalDrag = { change, amount -> if (!locked) { change.consume(); moved += amount } }, onDragEnd = {
                                            if (!locked && abs(moved) > 36.dp.toPx()) { val index = options.indexOfFirst { it.key == channel.key }; options.getOrNull(index + if (moved < 0) 1 else -1)?.let { model.switchSource(it) }; interact() }
                                        })
                                    }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Change stream", style = MaterialTheme.typography.labelMedium); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp)) } else Spacer(Modifier.weight(1f))
                                    ChromeButton(if (playback.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (playback.isMuted) "Unmute" else "Mute") { model.controller.setMuted(!playback.isMuted); interact() }
                                }
                            }
                        }
                    }
                    if (locked) ChromeButton(Icons.Rounded.Lock, "Unlock player controls", Modifier.align(Alignment.TopEnd).padding(12.dp)) { locked = false; interact() }
                    if (playback.showsBufferingIndicator && !presentation.recoveryRequired) Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center).padding(top = 100.dp)) { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White); Text(presentation.statusLabel, color = Color.White, style = MaterialTheme.typography.labelMedium) } }
                    if (presentation.recoveryRequired) PhonePlayerRecovery(presentation, channel.canonicalName ?: channel.name,
                        hasAlternate = alternateSources.isNotEmpty(), onRestart = { model.restart(); interact() },
                        onChoose = { open(if (phoneHasAlternateSource(channel, model.playerSources(), model.replay != null)) "Sources" else "Channels") },
                        onMinimize = { model.fullPlayer = false }, onStop = model::stopPlayer)
                    if (!presentation.recoveryRequired) hud?.let { Surface(color = Color(0xD9141923), shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center).padding(top = 100.dp)) { Text(it, Modifier.padding(14.dp), color = Color.White, style = MaterialTheme.typography.labelLarge) } }
                }
            }
    }, companion = { automatic ->
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column {
                if (landscape && !automatic) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Game Centre", Modifier.weight(1f).padding(start = 16.dp), fontWeight = FontWeight.Bold)
                    IconButton(onClick = { panel = null }) { Icon(Icons.Rounded.Close, "Close game information") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        quickActions.forEach { action -> TextButton(onClick = { if (action == "PiP") activity.enterPlayerPip() else open(action) }) { Text(action) } }
                    }
                    IconButton(onClick = { open(if (alternateSources.isNotEmpty()) "Sources" else "Channels") }) { Icon(Icons.Rounded.Layers, if (alternateSources.isNotEmpty()) "Change stream" else "Choose channel") }
                }
                if (game != null) GameDetailScreen(game, model, companion = true) else PlayerChannelPage(channel, model)
            }
        }
    })
    if (panel != null && !(panel == "Game" && landscape) && !pip) ModalBottomSheet(onDismissRequest = { panel = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxHeight(0.9f)) { when (panel) {
            "Guide" -> PhoneGuide(model, onSelect = { model.play(it); panel = null }, onReplay = { source, entry -> if (model.playReplay(source, entry)) panel = null })
            "Channels" -> ChannelsScreen(model, onSelect = { model.play(it); panel = null })
            "Recent" -> HistoryScreen(model)
            "Sources" -> ScreenList {
                item { Text("Change stream", style = MaterialTheme.typography.headlineMedium); Text("Copies of this channel, ordered by your quality preferences. Availability depends on your playlist.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                options.forEach { source -> item(source.key) { ListItem(headlineContent = { Text(source.name) }, supportingContent = { Text(if (source.key == channel.key) "Current stream · ${presentation.statusLabel}" else "Switch to this stream") }, trailingContent = { Icon(if (source.key == channel.key) Icons.Rounded.CheckCircle else Icons.Rounded.PlayCircle, null) }, modifier = Modifier.clickable(enabled = source.key != channel.key) { model.switchSource(source); panel = null }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) } }
                if (alternateSources.isEmpty()) item { EmptyState("No alternate stream", "Restart this stream or choose another channel."); TextButton(onClick = { open("Channels") }) { Text("Choose channel") } }
            }
            else -> ModernPlayerOptions(model, aspect, ::setAspect, brightness, { brightness = it; activity.window.attributes = activity.window.attributes.apply { screenBrightness = it } }, volume, { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), 0) },
                onOpen = ::open, onEnterPip = { panel = null; activity.enterPlayerPip() }, onLock = { panel = null; locked = true })
        } }
    }
}

@Composable
private fun PlayerChromeVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) { content() }
}

@Composable
private fun ChromeButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = modifier.size(48.dp).background(Color.Black.copy(alpha = 0.32f), CircleShape)) { Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun PhonePlayerRecovery(presentation: PhonePlaybackPresentation, channelName: String, hasAlternate: Boolean,
    onRestart: () -> Unit, onChoose: () -> Unit, onMinimize: () -> Unit, onStop: () -> Unit) {
    Surface(color = Color(0xF0090F1B), contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeButton(Icons.AutoMirrored.Rounded.ArrowBack, "Minimize player", action = onMinimize)
                Text(channelName, Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(presentation.failureTitle.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(presentation.failureMessage.orEmpty(), color = Color(0xFFBFC9D7), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = onRestart, Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF79B8FF), contentColor = Color(0xFF071524))) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Restart stream")
                    }
                    OutlinedButton(onClick = onChoose, Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = BorderStroke(1.dp, Color(0xFF718098))) {
                        Text(if (hasAlternate) "Change stream" else "Choose channel")
                    }
                    TextButton(onClick = onStop, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFBFC9D7))) { Text("Stop stream") }
                }
            }
        }
    }
}

@Composable
private fun PlayerChannelPage(channel: Channel, model: MobileViewModel) {
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    var tick by remember(channel.key) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(channel.key) {
        while (true) { delay(30_000); tick = System.currentTimeMillis() }
    }
    // A programme boundary is a clock change, not necessarily a new XMLTV download.
    val now = remember(channel.key, guide.revision, tick) { model.container.guide.nowAndNext(channel) }
    ScreenList {
        model.replay?.let { replay -> item { PhoneCard {
            Overline("Archive replay")
            Text(replay.programme.title, style = MaterialTheme.typography.titleLarge)
            Text("${dateLabel(replay.programme.startMillis)} · ${clockTime(replay.programme.startMillis)} – ${clockTime(replay.programme.endMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            replay.programme.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("Live scores are not attached to archive playback. Replay and seeking depend on this provider.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = model::returnToLive) { Text("Return to live channel") }
        } } }
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(channel.logoUrl, channel.name, 44.dp); Column(Modifier.weight(1f)) { Text(channel.canonicalName ?: channel.name, style = MaterialTheme.typography.titleLarge); Text(channel.group, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
            IconToggleButton(channel.key in settings.favoriteChannelIds, { model.container.preferences.toggleFavoriteChannel(channel.key) }) { Icon(if (channel.key in settings.favoriteChannelIds) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite channel") }
        } }
        now.first?.takeIf { model.replay == null }?.let { programme -> item { PhoneCard {
            Overline("On now"); Text(programme.title, style = MaterialTheme.typography.titleLarge)
            Text("${clockTime(programme.startMillis)} – ${clockTime(programme.endMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(progress = { programme.progress(tick) }, Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            programme.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        } } }
        now.second?.takeIf { model.replay == null }?.let { next -> item { PhoneCard { Overline("Up next · ${clockTime(next.startMillis)}"); Text(next.title, style = MaterialTheme.typography.titleMedium); next.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) } } } }
        if (now.first == null) item { EmptyState("Live television", "There is no current programme listing for this channel in your playlist's guide. A Game Centre connects only after an unambiguous confirmed match.") }
    }
}

@Composable
private fun ModernPlayerOptions(model: MobileViewModel, aspect: AspectMode, onAspect: (AspectMode) -> Unit, brightness: Float, onBrightness: (Float) -> Unit, volume: Float, onVolume: (Float) -> Unit,
    onOpen: (String) -> Unit, onEnterPip: () -> Unit, onLock: () -> Unit) {
    val playback by model.controller.ui.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    ScreenList {
        item { SectionTitle("Playback"); Button(onClick = model::restart, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Restart stream") }; TextButton(onClick = model::stopPlayer, Modifier.fillMaxWidth()) { Text("Stop stream", color = MaterialTheme.colorScheme.error) } }
        item { SectionTitle("Watch"); TextButton(onClick = { onOpen("Guide") }) { Icon(Icons.Rounded.CalendarViewDay, null); Spacer(Modifier.width(8.dp)); Text("TV guide") }; TextButton(onClick = { onOpen("Channels") }) { Icon(Icons.Rounded.Tv, null); Spacer(Modifier.width(8.dp)); Text("Choose channel") }; TextButton(onClick = onEnterPip) { Icon(Icons.Rounded.PictureInPictureAlt, null); Spacer(Modifier.width(8.dp)); Text("Picture in picture") } }
        if (playlists.channels.size > 1) item { OutlinedButton(onClick = model::beginMultiview, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.GridView, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Watch multiple channels") } }
        item { Text("Picture", style = MaterialTheme.typography.titleMedium); ChoiceRow(AspectMode.entries.map { it.label }, aspect.label) { label -> onAspect(AspectMode.entries.first { it.label == label }) }; Text(aspect.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Quality", style = MaterialTheme.typography.titleMedium); ChoiceRow(QualityCap.entries.map { it.label }, playback.qualityCap.label) { label -> model.controller.setQualityCap(QualityCap.entries.first { it.label == label }) } }
        item { Text("Buffer", style = MaterialTheme.typography.titleMedium); ChoiceRow(BufferProfile.entries.map { it.label }, model.profile().label) { label -> model.container.preferences.update { it.copy(playerBufferProfile = BufferProfile.entries.first { p -> p.label == label }.name) } } }
        item { Text("Brightness", style = MaterialTheme.typography.titleMedium); Slider(brightness, onBrightness, valueRange = 0.02f..1f); Text("Volume", style = MaterialTheme.typography.titleMedium); Slider(volume, onVolume) }
        item {
            SectionTitle("Audio")
            Text("Preferred language · ${PlaybackLanguages.label(settings.preferredAudioLanguage)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.controller.useAutomaticAudio(); model.container.preferences.update { it.copy(preferredAudioLanguage = "") } }) { Text("Automatic · device/provider default") }
            playback.audioTracks.forEach { track -> TextButton(onClick = { if (model.controller.selectAudio(track.id)) model.container.preferences.update { it.copy(preferredAudioLanguage = PlaybackLanguages.normalize(track.language)) } }) { Text((if (track.selected) "✓ " else "") + track.label) } }
            Text(if (playback.audioTracks.isEmpty()) "This stream hasn't supplied an audio track." else "Language is remembered across channels and restarts. Missing languages use the provider/device default; same-language track choices apply to this source only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionTitle("Subtitles"); TextButton(onClick = { model.controller.selectText(null); model.container.preferences.update { it.copy(captionsEnabled = false) } }) { Text(if (!playback.captionsOn) "✓ Off" else "Off") }; playback.textTracks.forEach { track -> TextButton(onClick = { model.controller.selectText(track.id); model.container.preferences.update { it.copy(captionsEnabled = true, captionLanguage = track.language.orEmpty()) } }) { Text((if (track.selected) "✓ " else "") + track.label) } }; if (playback.textTracks.isEmpty()) Text("No subtitle track is provided by this stream.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        item { SectionTitle("Controls"); TextButton(onClick = onLock) { Icon(Icons.Rounded.LockOpen, null); Spacer(Modifier.width(8.dp)); Text("Lock player controls") }; SettingSwitch("Double tap seeks 10 seconds", settings.doubleTapSeeks) { value -> model.container.preferences.update { it.copy(doubleTapSeeks = value) } }; Text("When off, double tap left or right switches channels. In landscape, swipe vertically on the left for brightness, on the right for volume. In portrait, pull down from the upper video to minimize or swipe up for landscape. Pinch to crop/fit. Swipe Change stream to change copies.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Text("Hide controls after", style = MaterialTheme.typography.titleMedium); ChoiceRow(listOf("3s", "5s", "10s", "15s"), "${settings.playerPanelTimeoutSeconds}s") { label -> model.container.preferences.update { it.copy(playerPanelTimeoutSeconds = label.removeSuffix("s").toInt()) } }; Text("Controls stay visible while paused, buffering, recovering from an error, using a sheet or using TalkBack.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Text("Quick actions · ordered left to right", style = MaterialTheme.typography.titleMedium)
            val actions = PlayerQuickActions.normalize(library.playerBarActions)
            actions.forEachIndexed { index, action -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. $action", Modifier.weight(1f))
                IconButton(enabled = index > 0, onClick = { model.library.update { it.copy(playerBarActions = PlayerQuickActions.move(it.playerBarActions, action, -1)) } }) { Icon(Icons.Rounded.ArrowUpward, "Move $action earlier") }
                IconButton(enabled = index < actions.lastIndex, onClick = { model.library.update { it.copy(playerBarActions = PlayerQuickActions.move(it.playerBarActions, action, 1)) } }) { Icon(Icons.Rounded.ArrowDownward, "Move $action later") }
                IconButton(enabled = actions.size > 1, onClick = { model.library.update { it.copy(playerBarActions = actions - action) } }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove $action quick action") }
            } }
            PlayerQuickActions.known.filterNot { it in actions }.forEach { action -> TextButton(enabled = actions.size < 3, onClick = { model.library.update { it.copy(playerBarActions = actions + action) } }) { Icon(Icons.Rounded.Add, null); Text("Add $action") } }
            Text("Up to three slots. The last action cannot be removed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.library.update { it.copy(playerBarActions = PlayerQuickActions.defaults) } }) { Text("Reset quick actions") }
        }
        item { SectionTitle("Stream health"); Text("${playback.videoHeight.takeIf { it > 0 }?.let { "${it}p · " }.orEmpty()}${playback.reserveSeconds}s reserve · ${playback.playbackRate}×", style = MaterialTheme.typography.bodyMedium); Text(if (playback.engineActive) "${playback.enginePhase} · ${playback.engineNotice.orEmpty()}" else "Native Media3 playback", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
