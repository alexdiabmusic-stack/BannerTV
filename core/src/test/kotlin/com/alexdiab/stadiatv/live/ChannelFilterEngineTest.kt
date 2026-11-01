package com.alexdiab.stadiatv.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFilterEngineTest {
    private fun channel(id: String, name: String, group: String, playlist: String) =
        Channel(id = id, name = name, streamUrl = "https://x/$id.m3u8", logoUrl = null, group = group, playlistId = playlist, playlistName = playlist)

    @Test
    fun combinesDimensionsWithAndSemantics() {
        val english = channel("a", "EN: ESPN 4K", "Sports", "p1")
        val french = channel("b", "FR: RDS HD", "Sports", "p1")
        val movie = channel("c", "EN: HBO 4K", "Movies", "p2")
        val filter = ChannelFilter(languages = setOf("en"), categories = setOf("Sports"), qualities = setOf("4K"), playlistIds = setOf("p1"))

        val result = ChannelFilterEngine.apply(ChannelFilterEngine.index(listOf(movie, french, english)), filter, "espn", emptySet())

        assertEquals(listOf("a"), result.map { it.id })
    }

    @Test
    fun favoritesOnlyUsesSavedChannelIds() {
        val one = channel("a", "ESPN", "Sports", "p1")
        val two = channel("b", "TSN", "Sports", "p1")

        val result = ChannelFilterEngine.apply(ChannelFilterEngine.index(listOf(one, two)), ChannelFilter(favoritesOnly = true), "", setOf("b"))

        assertEquals(listOf(two), result)
    }

    @Test
    fun favoritesMatchPlaylistScopedKeysToo() {
        val one = channel("a", "ESPN", "Sports", "p1")

        val result = ChannelFilterEngine.apply(ChannelFilterEngine.index(listOf(one)), ChannelFilter(favoritesOnly = true), "", setOf(one.key))

        assertEquals(listOf(one), result)
    }

    @Test
    fun detectsCategoryAndSharpestQuality() {
        val channel = channel("a", "Sportsnet UHD 4K HD", "Canadian Sports", "p1")

        assertEquals("4K", ChannelFilterEngine.quality(channel))
        assertTrue("Sports" in ChannelFilterEngine.categories(channel))
    }

    @Test
    fun resultsAreSortedAlphabeticallyIgnoringCase() {
        val result = ChannelFilterEngine.apply(
            ChannelFilterEngine.index(listOf(channel("1", "zeta", "x", "p"), channel("2", "Alpha", "x", "p"), channel("3", "beta", "x", "p"))),
            ChannelFilter(), "", emptySet(),
        )

        assertEquals(listOf("Alpha", "beta", "zeta"), result.map { it.name })
    }

    @Test
    fun searchMatchesAcrossNameGroupAndPlaylistIgnoringPunctuation() {
        val channel = channel("1", "Sky Sports F1", "UK | Sports", "Main")

        val hit = ChannelFilterEngine.apply(ChannelFilterEngine.index(listOf(channel)), ChannelFilter(), "uk sports", emptySet())
        val miss = ChannelFilterEngine.apply(ChannelFilterEngine.index(listOf(channel)), ChannelFilter(), "bein", emptySet())

        assertEquals(1, hit.size)
        assertTrue(miss.isEmpty())
    }
}
