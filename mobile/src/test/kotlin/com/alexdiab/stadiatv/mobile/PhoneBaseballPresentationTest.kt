package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class PhoneBaseballPresentationTest {
    private fun row(id: Int, hits: String, rbi: String, hr: String = "–") = BoxRow("Player $id", "C", listOf(hits, rbi, hr), athleteId = "mlb:$id")
    private fun bat(id: String, title: String = "Single") = BaseballAtBat(id, id.substringAfter(':').toInt(), 1, "top", null, null, title, title, true, false, "0", "0", emptyList(), 0, null)

    @Test fun battingLeadersRequireActualHitsAndSortRbiThenHitsWithoutInventingMissingStats() {
        val boxes = listOf(TeamBox("One", "ONE", listOf(BoxGroup("Batting", listOf("H", "RBI", "HR"), listOf(row(1, "3", "1"), row(2, "2", "4", "1"), row(3, "0", "6"), row(4, "–", "9")), emptyList()))),
            TeamBox("Two", "TWO", listOf(BoxGroup("Batting", listOf("H", "RBI", "HR"), listOf(row(5, "3", "4"), row(6, "2", "0")), emptyList()))))
        val leaders = baseballBattingLeaders(boxes)
        assertEquals(listOf("mlb:5", "mlb:2", "mlb:1"), leaders.map { it.player.id })
        assertEquals("TWO", leaders.first().team); assertEquals("3 H · 4 RBI", leaders.first().line)
        assertEquals("2 H · 4 RBI · 1 HR", leaders[1].line)
    }
    @Test fun nativeLeadersNeverReinterpretForeignAthleteIds() {
        val rows = listOf(row(1, "2", "1").copy(athleteId = "123"), row(2, "1", "2").copy(athleteId = null))
        assertTrue(baseballBattingLeaders(listOf(TeamBox("One", "ONE", listOf(BoxGroup("Batting", listOf("H", "RBI", "HR"), rows, emptyList()))))).isEmpty())
    }
    @Test fun matchupStatsUseTheActualNativePlayerAndReportedColumnsOnly() {
        val boxes = listOf(TeamBox("One", "ONE", listOf(BoxGroup("Pitching", listOf("IP", "H", "ER", "SO", "P"), listOf(BoxRow("Pitcher", "P", listOf("7.0", "2", "–", "8", "96"), athleteId = "mlb:1")), emptyList()))))
        assertEquals("7.0 IP · 2 H · 8 SO · 96 pitches", baseballMatchupLine(SportPlayer("1", "Pitcher"), boxes, true))
        assertNull(baseballMatchupLine(SportPlayer("2", "Someone else"), boxes, true))
        assertNull(baseballMatchupLine(SportPlayer("1", "Pitcher"), boxes, false))
    }
    @Test fun readingHistoryDefersNewPlaysButAppliesCorrectionsAndRemovals() {
        val start = BaseballTimelineWindow().update(listOf(bat("g:1"), bat("g:2")), true)
        val changed = start.update(listOf(bat("g:1", "Corrected double"), bat("g:3")), false)
        assertEquals(listOf("g:1"), changed.displayed.map { it.id }); assertEquals("Corrected double", changed.displayed.single().title)
        assertEquals(setOf("g:3"), changed.deferredIds)
        val latest = changed.update(listOf(bat("g:1"), bat("g:3")), true)
        assertEquals(2, latest.displayed.size); assertTrue(latest.deferredIds.isEmpty())
    }
    @Test fun savedTimestampIncludesDateOutsideTheCurrentLocalDay() {
        val before = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val now = com.alexdiab.stadiatv.util.Dates.parseIso("2026-10-23T16:00:00Z")!!
            assertFalse(snapshotTime(now - 60_000, now).contains("2026"))
            assertTrue(snapshotTime(now - 86_400_000, now).contains("2026"))
        } finally { TimeZone.setDefault(before) }
    }
}
