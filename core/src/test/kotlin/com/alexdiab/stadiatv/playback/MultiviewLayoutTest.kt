package com.alexdiab.stadiatv.playback

import com.alexdiab.stadiatv.data.SavedMultiview
import com.alexdiab.stadiatv.live.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiviewLayoutTest {
    private fun channel(id: String) = Channel(
        id = id, name = "Channel $id", streamUrl = "https://e.com/$id.m3u8", logoUrl = null,
        group = "Sports", playlistId = "p", playlistName = "Playlist",
    )

    private val lineup = listOf("1", "2", "3", "4").map(::channel)

    @Test
    fun `the default layout grows with the number of streams`() {
        assertEquals(MultiviewLayout.SideBySide, MultiviewLayout.defaultFor(1))
        assertEquals(MultiviewLayout.SideBySide, MultiviewLayout.defaultFor(2))
        assertEquals(MultiviewLayout.ThreeUp, MultiviewLayout.defaultFor(3))
        assertEquals(MultiviewLayout.FourUp, MultiviewLayout.defaultFor(4))
    }

    @Test
    fun `layout options are limited by what the device can decode`() {
        assertEquals(
            listOf(MultiviewLayout.SideBySide, MultiviewLayout.Stacked, MultiviewLayout.Inset, MultiviewLayout.ThreeUp, MultiviewLayout.FourUp),
            MultiviewLayout.optionsFor(maxStreams = 4),
        )
        assertEquals(
            listOf(MultiviewLayout.SideBySide, MultiviewLayout.Stacked, MultiviewLayout.Inset),
            MultiviewLayout.optionsFor(maxStreams = 2),
        )
    }

    @Test
    fun `low memory devices are limited to two streams`() {
        assertEquals(2, MultiviewCapacity.maxStreams(isLowRamDevice = true))
        assertEquals(4, MultiviewCapacity.maxStreams(isLowRamDevice = false))
    }

    @Test
    fun `more streams mean a lower quality ceiling`() {
        assertEquals(QualityCap.Auto, MultiviewCapacity.qualityFor(1))
        assertEquals(QualityCap.P1080, MultiviewCapacity.qualityFor(2))
        assertEquals(QualityCap.P720, MultiviewCapacity.qualityFor(4))
    }

    @Test
    fun `a saved slot restores the channels that still exist`() {
        val saved = SavedMultiview(listOf("p|1", "p|gone", "p|3"), MultiviewLayout.ThreeUp.name, audioKey = "p|3")
        val restored = saved.restore(lineup, capacity = 4)!!
        assertEquals(listOf("1", "3"), restored.channels.map { it.id })
        // The saved layout still has room for them (the spare slot becomes an "add" tile), so it is kept.
        assertEquals(MultiviewLayout.ThreeUp, restored.layout)
        assertEquals("p|3", restored.audioKey)
    }

    @Test
    fun `restoring respects the device capacity and falls back to the first stream for sound`() {
        val saved = SavedMultiview(lineup.map { it.key }, MultiviewLayout.FourUp.name, audioKey = "p|missing")
        val restored = saved.restore(lineup, capacity = 2)!!
        assertEquals(2, restored.channels.size)
        assertEquals("p|1", restored.audioKey)
        assertEquals(MultiviewLayout.SideBySide, restored.layout)
    }

    @Test
    fun `a saved layout too small for the restored channels is replaced`() {
        val saved = SavedMultiview(listOf("p|1", "p|2", "p|3"), MultiviewLayout.SideBySide.name)
        assertEquals(MultiviewLayout.ThreeUp, saved.restore(lineup, capacity = 4)!!.layout)
    }

    @Test
    fun `a slot whose channels are all gone restores nothing`() {
        assertNull(SavedMultiview(listOf("p|gone"), MultiviewLayout.SideBySide.name).restore(lineup, capacity = 4))
    }
}
