@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import android.app.ActivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.data.SavedMultiview
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.*

@Composable
fun PhoneMultiview(model: MobileViewModel) {
    val billing by model.entitlements.state.collectAsStateWithLifecycle()
    if (!billing.isPremium) { PhonePremiumGate("Multiview", model); return }
    val context = LocalContext.current
    val capacity = remember { MultiviewCapacity.maxStreams(context.getSystemService(ActivityManager::class.java).isLowRamDevice) }
    val controllers = remember { mutableStateMapOf<String, PlaybackController>() }
    val channels = remember { mutableStateListOf<Channel>() }
    var audioKey by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    fun add(channel: Channel) {
        if (channels.size >= capacity || channels.any { it.key == channel.key }) return
        channels += channel
        controllers[channel.key] = PlaybackController(context.applicationContext, BufferProfile.Fast, ownsAudioFocus = false).also { it.load(channel) }
        if (audioKey == null) audioKey = channel.key
    }
    fun remove(channel: Channel) {
        controllers.remove(channel.key)?.release(); channels.remove(channel)
        if (audioKey == channel.key) audioKey = channels.firstOrNull()?.key
    }
    LaunchedEffect(Unit) { model.consumeMultiviewChannel()?.let(::add) }
    DisposableEffect(Unit) { onDispose { controllers.values.forEach { it.release() }; controllers.clear() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controllers.values.forEach { it.player.pause() } }
    // Multiview has no per-tile pause control; foreground entry resumes the tiles paused by ON_STOP.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { controllers.values.forEach { it.player.play() } }
    LaunchedEffect(audioKey, channels.size) { controllers.forEach { (key, value) -> value.setAudioActive(key == audioKey); value.setQualityCap(MultiviewCapacity.qualityFor(channels.size)) } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${channels.size} of $capacity channels · Tap a stream to hear it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picking = true }, enabled = channels.size < capacity) { Icon(Icons.Rounded.Add, null); Text("Add") }
            OutlinedButton(onClick = { model.container.preferences.update { it.copy(multiviewSlots = it.multiviewSlots + ("Phone" to SavedMultiview(channels.map { c -> c.key }, MultiviewLayout.defaultFor(channels.size).name, audioKey))) } }, enabled = channels.isNotEmpty()) { Text("Save") }
            OutlinedButton(onClick = {
                settings.multiviewSlots["Phone"]?.restore(playlist.channels, capacity)?.let { restored -> channels.toList().forEach(::remove); restored.channels.forEach(::add); audioKey = restored.audioKey }
            }, enabled = settings.multiviewSlots.containsKey("Phone")) { Text("Restore") }
        }
        if (channels.isEmpty()) EmptyState("More of the action", "Add up to $capacity channels. Only the selected stream plays audio.", "Add channel", { picking = true })
        for (row in channels.chunked(if (channels.size > 2) 2 else 1)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { channel ->
                    val controller = controllers[channel.key]
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black).clickable { audioKey = channel.key }) {
                        if (controller != null) PlayerVideo(controller, Modifier.fillMaxSize(), showNativeControls = false)
                        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x99000000)).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(channel.canonicalName ?: channel.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.weight(1f))
                            IconButton(onClick = { audioKey = channel.key }) { Icon(if (audioKey == channel.key) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff, "Listen to ${channel.name}", tint = Color.White) }
                            IconButton(onClick = { remove(channel) }) { Icon(Icons.Rounded.Close, "Remove stream", tint = Color.White) }
                        }
                        if (controller != null) {
                            val state by controller.ui.collectAsStateWithLifecycle()
                            if (state.errorMessage != null) TextButton(onClick = controller::restart, modifier = Modifier.align(Alignment.Center)) { Text("Restart") }
                        }
                    }
                }
            }
        }
    }
    if (picking) ModalBottomSheet(onDismissRequest = { picking = false }) {
        Box(Modifier.fillMaxHeight(0.9f)) { ChannelsScreen(model, onSelect = { if (channels.none { c -> c.key == it.key }) { add(it); picking = false } }) }
    }
}
