package com.alexdiab.stadiatv.ui.multiview

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.BufferProfile
import com.alexdiab.stadiatv.playback.MultiviewCapacity
import com.alexdiab.stadiatv.playback.PlaybackController

/**
 * The streams in a multiview and the players behind the ones on screen. A stream outside the
 * current layout keeps its place in [channels] but holds no decoder until the layout shows it.
 */
@Stable
internal class MultiviewSession(
    private val context: Context,
    val capacity: Int,
    private val onAdded: (Channel) -> Unit,
) {
    val channels = mutableStateListOf<Channel>()
    var audioKey by mutableStateOf<String?>(null)
        private set

    private val controllers = mutableStateMapOf<String, PlaybackController>()
    private val audioApplied = HashMap<String, Boolean>()

    val isFull: Boolean get() = channels.size >= capacity

    fun controllerFor(channel: Channel): PlaybackController? = controllers[channel.key]

    fun contains(channel: Channel) = channels.any { it.key == channel.key }

    fun add(channel: Channel) {
        if (isFull || contains(channel)) return
        channels.add(channel)
        if (audioKey == null) audioKey = channel.key
        onAdded(channel)
    }

    fun replace(old: Channel, new: Channel) {
        val index = channels.indexOfFirst { it.key == old.key }
        if (index < 0 || (contains(new) && new.key != old.key)) return
        channels[index] = new
        if (audioKey == old.key) audioKey = new.key
        onAdded(new)
    }

    fun remove(channel: Channel) {
        channels.removeAll { it.key == channel.key }
        if (audioKey == channel.key) audioKey = channels.firstOrNull()?.key
    }

    fun listenTo(channel: Channel) {
        audioKey = channel.key
    }

    fun setAll(newChannels: List<Channel>, audio: String?) {
        channels.clear()
        channels.addAll(newChannels.take(capacity))
        audioKey = audio?.takeIf { key -> channels.any { it.key == key } } ?: channels.firstOrNull()?.key
    }

    /** Makes the running players match what is on screen: start the new, release the gone, retune quality and sound. */
    fun sync(visible: List<Channel>) {
        val keys = visible.map { it.key }.toSet()
        controllers.keys.filter { it !in keys }.forEach { key ->
            controllers.remove(key)?.release()
            audioApplied.remove(key)
        }
        if (audioKey !in keys) audioKey = visible.firstOrNull()?.key
        val quality = MultiviewCapacity.qualityFor(visible.size)
        visible.forEach { channel ->
            val controller = controllers.getOrPut(channel.key) {
                // Fast buffering keeps four streams inside a stick's memory; focus is claimed only by the audible one.
                PlaybackController(context, BufferProfile.Fast, ownsAudioFocus = false).also { it.load(channel) }
            }
            if (controller.ui.value.qualityCap != quality) controller.setQualityCap(quality)
            val audible = channel.key == audioKey
            if (audioApplied[channel.key] != audible) {
                controller.setAudioActive(audible)
                audioApplied[channel.key] = audible
            }
        }
    }

    fun pauseAll() = controllers.values.forEach { it.player.pause() }

    fun resumeAll() = controllers.values.forEach { if (!it.ui.value.isUserPaused) it.player.play() }

    fun releaseAll() {
        controllers.values.forEach { it.release() }
        controllers.clear()
        audioApplied.clear()
    }
}
