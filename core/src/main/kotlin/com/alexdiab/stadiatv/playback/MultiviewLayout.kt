package com.alexdiab.stadiatv.playback

import com.alexdiab.stadiatv.data.SavedMultiview
import com.alexdiab.stadiatv.live.Channel

/** How the screen is divided between streams. Mirrors the iOS `MultiScreenLayout`. */
enum class MultiviewLayout(val label: String, val capacity: Int) {
    SideBySide("Side by side", 2),
    Stacked("Stacked", 2),
    Inset("Picture in picture", 2),
    ThreeUp("3-up", 3),
    FourUp("4-up", 4);

    companion object {
        fun defaultFor(count: Int): MultiviewLayout = when {
            count >= 4 -> FourUp
            count == 3 -> ThreeUp
            else -> SideBySide
        }

        /** Every arrangement the device can drive; slots without a stream show an "add" tile. */
        fun optionsFor(maxStreams: Int): List<MultiviewLayout> = entries.filter { it.capacity <= maxStreams }

        fun fromName(name: String?): MultiviewLayout? = entries.firstOrNull { it.name == name }
    }
}

object MultiviewCapacity {
    /**
     * Every extra stream is another hardware video decoder and another buffer. Sticks flagged as
     * low-RAM (1 GB Fire TV models) manage two; everything else handles four.
     */
    fun maxStreams(isLowRamDevice: Boolean): Int = if (isLowRamDevice) 2 else 4

    /** Quality ceiling per stream so the combined decode load stays within a device's means. */
    fun qualityFor(visibleStreams: Int): QualityCap = when {
        visibleStreams <= 1 -> QualityCap.Auto
        visibleStreams == 2 -> QualityCap.P1080
        else -> QualityCap.P720
    }
}

/** Turns a saved slot back into channels that still exist in the lineup, with a layout that fits them. */
fun SavedMultiview.restore(lineup: List<Channel>, capacity: Int): Restored? {
    val byKey = lineup.associateBy { it.key }
    val channels = channelKeys.mapNotNull(byKey::get).take(capacity)
    if (channels.isEmpty()) return null
    val saved = MultiviewLayout.fromName(layout)
    val layout = saved?.takeIf { it.capacity >= channels.size && it.capacity <= capacity } ?: MultiviewLayout.defaultFor(channels.size)
    return Restored(channels, layout, audioKey?.takeIf { key -> channels.any { it.key == key } } ?: channels.first().key)
}

data class Restored(val channels: List<Channel>, val layout: MultiviewLayout, val audioKey: String)
