package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone
import java.util.Locale

class PhonePresentationTest {
    @Test fun `all three native soccer providers use incident aware overview and timeline not generic scoring only rows`() {
        for (provider in listOf("Premier League", "MLS", "LaLiga")) {
            assertTrue(phoneUsesNativeSoccerEvents(GameCentre(providerStamp = ProviderStamp(provider, "123", 1), soccer = SoccerDetail())))
            assertFalse(phoneUsesNativeSoccerEvents(GameCentre(providerStamp = ProviderStamp(provider, "123", 1))))
        }
        assertFalse(phoneUsesNativeSoccerEvents(GameCentre(soccer = SoccerDetail())))
        assertFalse(phoneUsesNativeSoccerEvents(GameCentre(providerStamp = ProviderStamp("ESPN", "123", 1), soccer = SoccerDetail())))
    }
    @Test fun `shot labels are readable without claiming a saved attempt was definitely a goalkeeper save`() {
        assertEquals("Saved or blocked attempt", soccerShotLabel("AttemptSaved"))
        assertEquals("Left foot", soccerShotLabel("LeftFoot")); assertEquals("Open play", soccerShotLabel("RegularPlay"))
        assertEquals("New Provider Token", soccerShotLabel("NewProviderToken"))
    }
    @Test fun `native NFL schedule dates include the year and respect local kickoff day`() {
        val kickoff = Dates.parseIso("2025-09-05T00:20:00Z")!!
        val eastern = nflScheduleDate(kickoff, Locale.US, TimeZone.getTimeZone("America/New_York"))
        val utc = nflScheduleDate(kickoff, Locale.US, TimeZone.getTimeZone("UTC"))
        assertTrue(eastern.contains("Sep 4, 2025")); assertTrue(eastern.contains("8:20")); assertTrue(eastern.contains("PM"))
        assertTrue(utc.contains("Sep 5, 2025")); assertTrue(utc.contains("12:20")); assertNotEquals(eastern, utc)
    }
    @Test fun `weekend means actual upcoming Saturday and Sunday not the next seven days`() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val monday = Dates.parseIso("2026-10-19T12:00:00-04:00")!!
            val range = scheduleRange("Weekend", monday)
            assertEquals(Dates.parseIso("2026-10-24T00:00:00-04:00"), range.first)
            assertEquals(Dates.parseIso("2026-10-26T00:00:00-04:00")!! - 1, range.last)
            val sunday = scheduleRange("Weekend", Dates.parseIso("2026-10-25T12:00:00-04:00")!!)
            assertEquals(24 * 3_600_000L, sunday.last - sunday.first + 1)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun `tomorrow uses local calendar boundaries across daylight saving`() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val range = scheduleRange("Tomorrow", Dates.parseIso("2026-10-31T12:00:00-04:00")!!)
            assertEquals(25 * 3_600_000L, range.last - range.first + 1)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun `efficiency values use real denominators and invalid stats do not produce misleading bars`() {
        assertEquals(0.5f to 0.75f, statBarValues("10-20", "15-20"))
        assertEquals(0.6f to 0.4f, statBarValues("60%", "40%"))
        assertEquals(0.25f to 0.75f, statBarValues("10", "30"))
        assertNull(statBarValues("10:02", "5:55")); assertNull(statBarValues("–", "20"))
    }
    @Test fun `basketball scoring timeline retains only ties and lead changes in source chronological order`() {
        fun play(id: Int, a: Int, h: Int) = Play("$id", "1", "", "", null, "$a", "$h", true, periodNumber = 1, sortOrder = id.toDouble())
        val timeline = scoringTimeline(listOf(play(4, 4, 6), play(3, 4, 4), play(2, 4, 0), play(1, 2, 0)), true)
        assertEquals(listOf("3", "4"), timeline.map { it.id }) // The first reported lead is not an invented change from 0–0.
    }
    @Test fun `sport tabs expose real drives only and native shot charts only on configured providers`() {
        assertFalse("Drives" in gameTabs(SportGroup.Football, GameCentre.EMPTY, false))
        assertTrue("Drives" in gameTabs(SportGroup.Football, GameCentre(drives = listOf(GameDrive("1", "NE", "TD", "", 8, "75", "4:10"))), false))
        assertTrue("Shot map" in gameTabs(SportGroup.Hockey, GameCentre.EMPTY, true))
        assertFalse(gameTabs(SportGroup.Soccer, GameCentre.EMPTY, false).any { it.startsWith("Shot") })
    }
    @Test fun `countdown handles kickoff and upcoming intervals without negative time`() {
        assertEquals("Starting now", countdownLabel(100, 200))
        assertEquals("Starts in 1h 30m", countdownLabel(90 * 60_000, 0))
    }
    @Test fun `native EPL uses the soccer timeline and never advertises an unsupported player box score`() {
        val data = GameCentre(providerStamp = ProviderStamp("Premier League", "123", 1), soccer = SoccerDetail())
        assertEquals(listOf("Overview", "Timeline", "Lineups", "Team stats", "Commentary", "Live table"), gameTabs(SportGroup.Soccer, data, false))
        assertFalse("Box score" in gameTabs(SportGroup.Soccer, data, false))
    }
    @Test fun `announced native soccer keeps correct tabs before any detailed feed is published`() {
        assertEquals(listOf("Overview", "Timeline", "Lineups", "Team stats", "Commentary", "Live table"),
            gameTabs(SportGroup.Soccer, GameCentre.EMPTY, false, nativeSoccer = true))
        assertFalse("Box score" in gameTabs(SportGroup.Soccer, GameCentre(soccer = SoccerDetail(overviewNotPublished = true)), false, nativeSoccer = true))
        assertTrue("Box score" in gameTabs(SportGroup.Soccer, GameCentre.EMPTY, false))
    }
    @Test fun `La Liga exposes verified enrichment and a published league table rather than a manufactured live table`() {
        val expected = listOf("Overview", "Timeline", "Lineups", "Team stats", "Shots", "Commentary", "League table")
        assertEquals(expected, gameTabs(SportGroup.Soccer, GameCentre.EMPTY, false, nativeLaLiga = true))
        assertEquals(expected, gameTabs(SportGroup.Soccer, GameCentre(providerStamp = ProviderStamp("LaLiga", "102249", 1)), false))
        assertFalse("Box score" in expected); assertFalse("Live table" in expected)
    }
    @Test fun `universal search ranks exact names before prefixes and requires every normalized token`() {
        assertTrue(searchRank("Senators", "Senators") > searchRank("Senators", "Senators game"))
        assertTrue(searchRank("Senators", "Senators game") > searchRank("Senators", "Ottawa Senators"))
        assertTrue(searchRank("montreal hockey", "Montréal Canadiens", "hockey NHL") > 0)
        assertEquals(0, searchRank("montreal soccer", "Montréal Canadiens", "hockey NHL"))
        assertEquals(0, searchRank("a", "Any team"))
    }
}
