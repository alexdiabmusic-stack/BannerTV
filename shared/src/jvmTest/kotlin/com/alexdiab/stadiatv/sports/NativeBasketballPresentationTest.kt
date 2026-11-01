package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeBasketballPresentationTest {
    private fun fixture() = parseJson(javaClass.getResource("/parity/boxscore-live.constructed.json")!!.readText())["game"]!!
    @Test fun sharedDurationSupportsHoursMinutesOnlyAndTenthsWithoutLeakingRawIso() {
        assertEquals(1500.0, NativeBasketballDuration.seconds("PT25M")!!, 0.0)
        assertEquals(3662.5, NativeBasketballDuration.seconds("PT1H1M2.5S")!!, 0.0)
        assertEquals("25:00", NativeBasketball.clock("PT25M")); assertEquals("0:29.8", NativeBasketball.clock("PT29.8S"))
        assertEquals("1:00", NativeBasketball.clock("PT59.99S")); assertEquals("0:00.0", NativeBasketball.clock("PT0S"))
        assertEquals(29.8, NativeBasketballDuration.clockSeconds("0:29.8")!!, 0.0)
        for (raw in listOf("PT", "PTNaNS", "PT-1S", "PT1Mgarbage", "unavailable")) {
            assertNull(NativeBasketballDuration.seconds(raw)); assertEquals("", NativeBasketball.clock(raw))
        }
        assertEquals("Q4", NativeBasketballDuration.period(4)); assertEquals("OT", NativeBasketballDuration.period(5)); assertEquals("16OT", NativeBasketballDuration.period(20))
    }
    @Test fun playSentencesPrioritiesAndContextFollowTheNativeFieldsWithoutInventedPoints() {
        val supplied = parseJson("""{"actionType":"3pt","subType":"Step Back","shotResult":"Made","description":"Reported official sentence","playerName":"Player","teamTricode":"LAL","assistPlayerName":"Helper","scoreAway":4,"scoreHome":7}""")
        val event = NativeBasketballPresentation.play(supplied)
        assertEquals("Reported official sentence", event.title); assertEquals(BasketballPlayPriority.High, event.priority)
        assertEquals("Assist: Helper · LAL · 4–7", event.subtitle)
        val missing = NativeBasketballPresentation.play(parseJson("""{"actionType":"3pt","shotResult":"Made","playerName":"Player"}"""))
        assertEquals("Player makes a shot", missing.title); assertNull(missing.subtitle)
        assertEquals(BasketballPlayPriority.Compact, NativeBasketballPresentation.play(parseJson("""{"actionType":"rebound"}""")).priority)
        val boundary = NativeBasketballPresentation.play(parseJson("""{"actionType":"period","subType":"end","period":2}"""))
        assertEquals("Halftime", boundary.title); assertTrue(boundary.periodBoundary); assertEquals(BasketballPlayPriority.Medium, boundary.priority)
    }
    @Test fun leadChangesStartFromFirstReportedScoreIncludeCorrectionsAndCapAtSix() {
        fun play(id: Int, away: Int, home: Int, scoring: Boolean = true) = Play("$id", "1", "", "", null, "$away", "$home", scoring, periodNumber = 1, sortOrder = id.toDouble())
        val events = listOf(play(1, 2, 0), play(2, 4, 0), play(3, 4, 4), play(4, 4, 6, false))
        assertEquals(listOf("3", "4"), NativeBasketballPresentation.leadChanges(events.asReversed()).map { it.id })
        val many = (1..20).map { play(it, it, it + if (it % 2 == 0) 1 else -1) }
        assertEquals(listOf("15", "16", "17", "18", "19", "20"), NativeBasketballPresentation.leadChanges(many).map { it.id })
        val match = NativeBasketballMapping.game(fixture(), NativeBasketballLeague.NBA)!!
        assertEquals("Game tied", NativeBasketballPresentation.leadHeadline(events[2], match))
        assertEquals("LAL takes the lead", NativeBasketballPresentation.leadHeadline(events[3], match))
    }
    @Test fun combinedReportedLeaderTakesPrecedenceAndUsesNativeIdentityWithoutZeroFilling() {
        val leaders = parseJson("""{"homeLeaders":{"personId":2544,"name":"Reported leader","teamTricode":"LAL","points":25,"assists":7}}""")
        val raw = JsonObject(fixture().jsonObject + ("gameLeaders" to leaders))
        val home = NativeBasketballPresentation.leaders(raw, NativeBasketballLeague.NBA).single { it.team == "LAL" }
        assertEquals("nba:2544", home.player.id); assertEquals("25 PTS · 7 AST", home.statLine)
        assertFalse(home.statLine.contains("REB"))
        val away = NativeBasketballPresentation.leaders(raw, NativeBasketballLeague.NBA).single { it.team == "BOS" }
        assertEquals("Jayson Tatum", away.player.name); assertFalse(away.statLine.contains("AST")) // Box fallback is a points leader, not an invented combined line.
    }
    @Test fun missingOrForeignCombinedLeaderDoesNotBecomeSomeoneElsesGameCard() {
        val raw = JsonObject(fixture().jsonObject + ("gameLeaders" to parseJson("""{"homeLeaders":{"personId":9,"name":"Foreign player","teamTricode":"BOS","points":50}}""")))
        assertTrue(NativeBasketballPresentation.leaders(raw, NativeBasketballLeague.NBA).none { it.player.name == "Foreign player" })
    }
    @Test fun integerGameCellsFormatReportedDecimalsButNeverShowNanOrMissingAsZero() {
        assertEquals("-12", NativeBasketballPresentation.number(JsonPrimitive(-12.0)))
        assertEquals("0", NativeBasketballPresentation.number(JsonPrimitive(0)))
        assertNull(NativeBasketballPresentation.number(null)); assertNull(NativeBasketballPresentation.number(JsonPrimitive("NaN")))
        assertNull(NativeBasketballPresentation.number(JsonPrimitive(1.5)))
    }
    @Test fun curatedKeyStatsRespectPercentageUnitsAndLowerIsBetterTurnovers() {
        // The imported box fixture has player stats only. Team rows here are explicitly constructed.
        val raw = JsonObject(fixture().jsonObject.mapValues { (key, value) ->
            if (key !in setOf("homeTeam", "awayTeam")) value else JsonObject(value.jsonObject + ("statistics" to parseJson("""{"fieldGoalsPercentage":0.5,"threePointersPercentage":0.25,"reboundsTotal":20,"assists":10,"turnovers":5,"pointsInThePaint":14}""")))
        })
        val match = NativeBasketballMapping.game(raw, NativeBasketballLeague.NBA)!!
        val parsed = NativeBasketball.parse(raw, null, match, 1000)!!
        val key = NativeBasketballPresentation.keyStats(parsed.teamStats)
        assertEquals(key.map { it.id }.sortedBy { listOf("fieldGoalsPercentage", "threePointersPercentage", "reboundsTotal", "assists", "turnovers", "pointsInThePaint").indexOf(it) }, key.map { it.id })
        assertTrue(key.first { it.id == "turnovers" }.lowerIsBetter)
        assertEquals("50.0%", key.first { it.id == "fieldGoalsPercentage" }.away)
        parsed.teamStats.filter { it.id?.endsWith("Percentage") == true }.forEach { assertTrue(it.away.endsWith('%')); assertTrue(it.home.endsWith('%')) }
        assertTrue(parsed.gameInfo.any { it.first == "Date" && it.second.contains("2026") })
        assertTrue(parsed.gameInfo.any { it.first == "Venue" && it.second.contains("Los Angeles") })
    }
}
