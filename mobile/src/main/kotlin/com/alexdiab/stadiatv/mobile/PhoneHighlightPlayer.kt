@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.alexdiab.stadiatv.domain.GameVideo

/** A finite clip, not an IPTV source or podcast: no live reconnection, queue/history pollution or background audio. */
@Composable
fun PhoneHighlightPlayer(video: GameVideo, dismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var failed by remember(video.url) { mutableStateOf(false) }
    val player = remember(video.url) { ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setMediaItem(MediaItem.fromUri(video.url)); prepare(); play()
    } }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failed = true } }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener); lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column {
                Row(Modifier.padding(start = 18.dp, top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(video.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Close highlight") }
                }
                AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; setShowNextButton(false); setShowPreviousButton(false); keepScreenOn = true } },
                    update = { it.player = player }, onRelease = { it.player = null; it.keepScreenOn = false }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color.Black))
                if (failed) Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("This highlight couldn't play.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { failed = false; player.prepare(); player.play() }) { Text("Retry") }
                }
                Text("Highlights pause when you leave the app. Resume your channel manually after closing.", Modifier.padding(18.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
