package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhoneStandingsPresentationTest {
    @Test fun nativeBasketballUsesReportedConferencePlayoffRankNotItsArrayPosition() {
        val row = StandingRow("nba:1", "Example", "ONE", null, "0-5", mapOf("leaguerank" to "3", "divisionrank" to "1"))
        for (source in listOf("NBA", "WNBA")) {
            val group = StandingGroup("West", listOf(row), source)
            assertEquals("3", PhoneStandingColumn.rank(group, row, 0))
            assertEquals("–", PhoneStandingColumn.rank(group, row.copy(values = mapOf("divisionrank" to "1")), 0))
        }
    }
    @Test fun nativeNflRankNeverUsesAnAlphabeticalArrayIndexOrInventsMissingRank() {
        val row = StandingRow("nfl:one", "Example", "ONE", null, "0-5", mapOf("divisionrank" to "4"))
        val group = StandingGroup("AFC East", listOf(row), "NFL")
        assertEquals("4", PhoneStandingColumn.rank(group, row, 1))
        assertEquals("–", PhoneStandingColumn.rank(group, row.copy(values = emptyMap()), 1))
        assertEquals("2", PhoneStandingColumn.rank(group.copy(source = "ESPN"), row, 1))
    }
    private val nhl = League.find("hockey/nhl")!!
    @Test fun columnsMatchTheIosSportSpecificTables() {
        assertEquals(listOf("GP", "W", "L", "OTL", "PTS", "STK"), PhoneStandingColumn.columns(nhl).map { it.header(nhl) })
        val soccer = League.find("soccer/eng.1")!!
        assertEquals(listOf("GP", "W", "D", "L", "GD", "PTS"), PhoneStandingColumn.columns(soccer).map { it.header(soccer) })
        val nfl = League.find("football/nfl")!!
        assertEquals(listOf("W", "L", "T", "PCT", "STK"), PhoneStandingColumn.columns(nfl).map { it.header(nfl) })
    }
    @Test fun nativeAndEspnKeysAreReadWithoutInventingMissingNumbers() {
        val row = StandingRow("nhl:9", "Ottawa", "OTT", null, "1-2-0", mapOf("otlosses" to "0", "winpctg" to "0.333", "streakcode" to "W", "streakcount" to "2", "points" to "2"))
        assertEquals("0", PhoneStandingColumn.Ties.value(row, nhl)); assertEquals("W2", PhoneStandingColumn.Streak.value(row, nhl))
        assertEquals("0.333", PhoneStandingColumn.Percentage.value(row, nhl)); assertEquals("–", PhoneStandingColumn.Played.value(row, nhl))
        val soccer = League.find("soccer/eng.1")!!
        assertEquals("3", PhoneStandingColumn.Ties.value(row.copy(values = mapOf("draws" to "3")), soccer))
    }
    @Test fun groupingSwitchNeedsBothViewsAndPreservesProviderOrder() {
        val rows = listOf(StandingRow("b", "B", "B", null, "2"), StandingRow("a", "A", "A", null, "10"))
        val conference = StandingGroup("Eastern Conference", rows); val division = StandingGroup("Atlantic Division", rows.asReversed())
        assertTrue(PhoneStandingColumn.hasGroupingChoice(listOf(conference, division)))
        assertEquals(listOf(conference), PhoneStandingColumn.displayed(listOf(conference, division), false))
        assertEquals(rows, PhoneStandingColumn.displayed(listOf(conference, division), false).single().rows)
        assertEquals(listOf(division), PhoneStandingColumn.displayed(listOf(conference, division), true))
        assertFalse(PhoneStandingColumn.hasGroupingChoice(listOf(division)))
        assertEquals(listOf(division), PhoneStandingColumn.displayed(listOf(division), false))
    }
}
