package com.alexdiab.stadiatv.ui

import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.lineup.CatalogMatcher
import com.alexdiab.stadiatv.lineup.GuideDirectory
import com.alexdiab.stadiatv.lineup.GuideListing
import com.alexdiab.stadiatv.lineup.GuideRef
import com.alexdiab.stadiatv.lineup.LineupBuilder
import com.alexdiab.stadiatv.lineup.LineupFixture
import com.alexdiab.stadiatv.lineup.LineupPrefs
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.ui.livetv.ChannelShelves
import com.alexdiab.stadiatv.ui.livetv.ChannelShelvesLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelShelvesTest {
    private val builder = LineupBuilder(CatalogMatcher())
    private val now = 1_800_000_000_000L
    private val tsn: Channel = LineupFixture.channel(1, "CA ★ TSN 1 HD", "CA ❖ SPORTS", "TSN1.ca")
    private val cnn: Channel = LineupFixture.channel(2, "US ★ CNN HD", "US ❖ NEWS", "CNN.us")
    private val espn: Channel = LineupFixture.channel(3, "US ★ ESPN HD", "US ❖ SPORTS", "ESPN.us")
    private val lineup = builder.build(
        builder.analyse(listOf(tsn, cnn, espn)),
        GuideDirectory.build(builder.guideMatcher, listOf("TSN1.ca", "CNN.us", "ESPN.us").map { GuideListing(it, listOf(it), 100) }),
    )

    private fun programme(id: String, title: String, category: String) = Programme(id, now - 600_000, now + 600_000, title, null, null, category)

    private val onAir = mapOf(
        "tsn1.ca" to programme("tsn1.ca", "NHL Hockey", "Sports"),
        "cnn.us" to programme("cnn.us", "Situation Room", "News"),
    )

    private fun shelves(settings: UserSettings = UserSettings(homeCountry = "CA"), history: List<Channel> = emptyList()): ChannelShelves =
        ChannelShelvesLogic.build(
            lineup, settings, LineupPrefs(homeCountry = "CA"), history,
            nowAndNext = { ref: GuideRef, _ -> onAir[ref.id.lowercase()] to null }, now = now, timeZone = "America/Toronto",
        )

    @Test
    fun whatWasWatchedLastComesFirstAndEachChannelOnce() {
        val result = shelves(history = listOf(cnn, tsn, cnn))
        assertEquals(listOf("CNN", "TSN 1"), result.recent.map { it.entry.name })
    }

    @Test
    fun aChannelNoLongerInTheLineupIsDropped() {
        val gone = LineupFixture.channel(99, "US ★ GONE HD", "US ❖ NEWS", "Gone.us")
        assertEquals(listOf("TSN 1"), shelves(history = listOf(gone, tsn)).recent.map { it.entry.name })
    }

    @Test
    fun sportsOnNowListsOnlyChannelsThatHaveSomethingOnAir() {
        val sports = shelves().shelves.first { it.key == "sports" }.rows.map { it.entry.name }
        // ESPN has no listing at this moment, so it is not "on now".
        assertEquals(listOf("TSN 1"), sports)
    }

    @Test
    fun favoritesGetTheirOwnShelfAndAnEmptyLineupIsReadyButEmpty() {
        val favorite = lineup.entries.first { it.name == "CNN" }.key
        val withFavorite = shelves(UserSettings(homeCountry = "CA", favoriteEntryKeys = setOf(favorite)))
        assertEquals(listOf("CNN"), withFavorite.shelves.first { it.key == "favorites" }.rows.map { it.entry.name })
        assertFalse(shelves().shelves.any { it.key == "favorites" })
        assertTrue(shelves().ready)
        assertFalse(ChannelShelves().ready)
    }
}
