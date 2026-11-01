package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.playback.PlaybackController
import com.alexdiab.stadiatv.playback.PlaybackTimeline
import com.alexdiab.stadiatv.playback.PlaybackUi

@Composable
fun PhoneSeekBar(playback: PlaybackUi, controller: PlaybackController, onSeeking: (Boolean) -> Unit) {
    val timeline = playback.timeline
    if (!timeline.seekable || timeline.duration <= 0) return
    var preview by remember(playback.channelKey, playback.isArchive) { mutableStateOf<Long?>(null) }
    var captured by remember(playback.channelKey, playback.isArchive) { mutableStateOf<PlaybackTimeline?>(null) }
    DisposableEffect(playback.channelKey, playback.isArchive) { onDispose { onSeeking(false) } }
    val shown = preview ?: timeline.position
    // Keep the preview label anchored to the same window as the eventual seek while the live window moves.
    val displayStart = captured?.windowStart ?: timeline.windowStart
    val label = if (timeline.live && displayStart != null) clockTime(displayStart + shown) else playbackTime(shown)
    Column {
        Slider(value = shown.toFloat().coerceIn(0f, timeline.duration.toFloat()), valueRange = 0f..timeline.duration.toFloat(),
            onValueChange = { if (preview == null) captured = timeline; preview = it.toLong(); onSeeking(true) },
            onValueChangeFinished = { preview?.let { controller.seekTo(it, captured) }; preview = null; captured = null; onSeeking(false) },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = BannerBlue, inactiveTrackColor = Color.White.copy(alpha = .25f)), modifier = Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = if (playback.isArchive) "Replay position" else "DVR position"; stateDescription = label })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(if (timeline.live) "DVR · ${playbackTime(timeline.duration)} available" else playbackTime(timeline.duration), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

fun playbackTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
