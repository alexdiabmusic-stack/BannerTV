package com.alexdiab.stadiatv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.PlaybackController
import com.alexdiab.stadiatv.playback.PlaybackUi
import com.alexdiab.stadiatv.playback.engine.EnginePhase
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.components.ChannelLogo
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.LiveBadge
import com.alexdiab.stadiatv.ui.components.Spinner
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type

private val ChromeFill = Brush.verticalGradient(listOf(Color(0xCC0B0E15), Color(0xCC0B0E15)))

@Composable
internal fun PlayerChrome(
    channel: Channel,
    playback: PlaybackUi,
    liveMatch: Match?,
    hasPrevious: Boolean,
    hasMatch: Boolean,
    hasFantasy: Boolean,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onOpen: (PlayerPanelKind) -> Unit,
    onGoLive: () -> Unit,
    onLast: () -> Unit,
    onMute: () -> Unit,
    onCaptions: () -> Unit,
    onInteract: () -> Unit,
) {
    val guide = LocalContainer.current.guide
    val programme = remember(channel.key, playback.isLoading) { guide.nowAndNext(channel).first?.title }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent))))
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB000000)))),
        )

        Row(
            Modifier.fillMaxWidth().padding(start = 64.dp, end = 64.dp, top = 44.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChromeButton(onClick = onBack, icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back")
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB8101319))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ChannelLogo(channel.logoUrl, 48.dp)
                    Column(Modifier.padding(end = 4.dp)) {
                        Text(channel.name, style = Type.Headline, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val subtitle = liveMatch?.let { "${it.league.shortName} · ${it.shortName}" } ?: programme
                        if (!subtitle.isNullOrBlank()) Text(subtitle, style = Type.Caption, color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    LiveBadge()
                    if (playback.videoHeight > 0) Chip("${playback.videoHeight}p")
                    if (playback.frameRate > 0f) Chip("${playback.frameRate.toInt()} fps")
                    if (playback.engineActive) {
                        val label = when (playback.enginePhase) {
                            EnginePhase.RECONNECTING, EnginePhase.WAITING -> "Shield recovering"
                            EnginePhase.STARTING, EnginePhase.BUILDING -> "Shield filling"
                            else -> "Shield ${playback.reserveSeconds}s"
                        }
                        Chip(label)
                    }
                }
            }
            FocusCard(
                onClick = onTogglePlay,
                modifier = Modifier.size(64.dp).initialFocus(),
                shape = CircleShape,
                focusedScale = 1.1f,
                background = ChromeFill,
            ) {
                Icon(
                    if (playback.isUserPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = if (playback.isUserPaused) "Play" else "Pause",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(34.dp),
                )
            }
        }

        LazyRow(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 36.dp),
            contentPadding = PaddingValues(horizontal = 64.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasMatch) item("sports") { ChromeButton({ onOpen(PlayerPanelKind.Sports) }, Icons.Rounded.SportsScore, "Match") }
            if (hasFantasy) item("fantasy") { ChromeButton({ onOpen(PlayerPanelKind.Fantasy) }, Icons.Rounded.EmojiEvents, "Fantasy") }
            item("prev") { ChromeButton(onPrevious, Icons.Rounded.ChevronLeft, null, description = "Previous channel") }
            item("next") { ChromeButton(onNext, Icons.Rounded.ChevronRight, null, description = "Next channel") }
            item("restart") { ChromeButton(onRestart, Icons.Rounded.Refresh, "Restart stream") }
            item("channels") { ChromeButton({ onOpen(PlayerPanelKind.Channels) }, Icons.Rounded.LiveTv, "Channels") }
            item("guide") { ChromeButton({ onOpen(PlayerPanelKind.Guide) }, Icons.Rounded.GridView, "Guide") }
            item("recent") { ChromeButton({ onOpen(PlayerPanelKind.Recents) }, Icons.Rounded.History, "Recent") }
            item("options") { ChromeButton({ onOpen(PlayerPanelKind.Options) }, Icons.Rounded.Tune, "Options") }
            if (playback.isBehindLive) item("live") { ChromeButton(onGoLive, Icons.Rounded.Sensors, "Go Live", emphasized = true) }
            if (hasPrevious) item("last") { ChromeButton(onLast, Icons.Rounded.Replay, "Last channel") }
            if (playback.textTracks.isNotEmpty()) item("captions") {
                ChromeButton(onCaptions, Icons.Rounded.ClosedCaption, if (playback.captionsOn) "CC on" else "CC", emphasized = playback.captionsOn)
            }
            item("mute") { ChromeButton(onMute, if (playback.isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, if (playback.isMuted) "Unmute" else "Mute") }
            item("group") {
                Text(
                    channel.group,
                    style = Type.Caption,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x80000000))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    // Any focus movement inside the controls keeps them on screen.
    LaunchedEffect(Unit) { onInteract() }
}

@Composable
internal fun ChromeButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    emphasized: Boolean = false,
) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        focusedScale = 1.07f,
        background = if (emphasized) Brush.horizontalGradient(listOf(Palette.Accent, Palette.ActionFill)) else ChromeFill,
        restingBorder = Color(0x33FFFFFF),
    ) {
        Row(
            Modifier.padding(horizontal = if (label == null) 18.dp else 24.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = description ?: label, tint = Color.White, modifier = Modifier.size(28.dp))
            if (label != null) Text(label, style = Type.BodyStrong, color = Color.White, maxLines = 1)
        }
    }
}

@Composable
internal fun ScoreBadge(match: Match, delaySeconds: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(top = 56.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xB8000000))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (delaySeconds == 0) LiveBadge() else Text("Delayed ${delaySeconds}s", style = Type.Caption, color = Color.White.copy(alpha = 0.75f))
        Text(
            "${match.away.abbreviation} ${match.away.score ?: "–"} – ${match.home.score ?: "–"} ${match.home.abbreviation}",
            style = Type.BodyStrong,
            color = Color.White,
            maxLines = 1,
        )
        match.statusDetail.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.Caption, color = Color.White.copy(alpha = 0.7f), maxLines = 1) }
    }
}

/** Spinner until the first frame, a reconnect label, and a retry card on failure. */
@Composable
internal fun PlaybackStatus(playback: PlaybackUi, onRetry: () -> Unit, onChannels: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playback.errorMessage != null) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE6101319))
                    .padding(horizontal = 56.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Palette.Live, modifier = Modifier.size(56.dp))
                Text(playback.errorMessage.orEmpty(), style = Type.Title3, color = Color.White, textAlign = TextAlign.Center)
                Text("Try again, or pick another channel.", style = Type.Body, color = Color.White.copy(alpha = 0.7f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ChromeButton(onRetry, Icons.Rounded.Replay, "Try Again", Modifier.initialFocus())
                    ChromeButton(onChannels, Icons.Rounded.LiveTv, "Channels")
                }
            }
        } else if (!playback.firstFrameRendered || playback.showsBufferingIndicator) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Spinner(color = Color.White)
                if (playback.engineActive && playback.engineNotice != null) {
                    Text(playback.engineNotice.orEmpty(), style = Type.Callout, color = Color.White)
                } else if (playback.reconnectAttempt > 0) {
                    Text(
                        "Reconnecting (${playback.reconnectAttempt}/${PlaybackController.MAX_RECONNECT_ATTEMPTS})…",
                        style = Type.Callout,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
internal fun UnlockHint(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ChromeButton(onUnlock, Icons.Rounded.LockOpen, "Unlock controls", Modifier.padding(bottom = 64.dp).initialFocus())
    }
}
