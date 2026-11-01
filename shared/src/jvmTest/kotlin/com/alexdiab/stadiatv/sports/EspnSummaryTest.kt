package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnSummaryTest {
    private val match = Match(
        id = "401", league = League.find("hockey/nhl")!!, startMillis = 0, name = "", shortName = "MTL @ WSH",
        state = GameState.LIVE, statusDetail = "12:24 - 2nd",
        home = TeamSide("Washington Capitals", "Capitals", "WSH", teamId = "23", score = "3"),
        away = TeamSide("Montreal Canadiens", "Canadiens", "MTL", teamId = "10", score = "2"),
    )

    private val summary = parseJson(
        """
        {
          "header": {"competitions": [{"competitors": [
            {"homeAway": "home", "score": "3", "linescores": [{"displayValue": "1"}, {"displayValue": "2"}]},
            {"homeAway": "away", "score": "2", "linescores": [{"displayValue": "1"}, {"displayValue": "1"}, {"displayValue": "0"}]}
          ]}]},
          "boxscore": {
            "teams": [
              {"team": {"id": "10"}, "statistics": [
                {"name": "shotsTotal", "label": "Shots", "displayValue": "24"},
                {"name": "hits", "label": "Hits", "displayValue": "8"},
                {"name": "shotsTotal", "label": "Shots", "displayValue": "24"},
                {"name": "faceoffPercent", "label": "Faceoff %", "displayValue": "55.5"},
                {"name": "onlyAway", "label": "Away only", "displayValue": "1"}]},
              {"team": {"id": "23"}, "statistics": [
                {"name": "shotsTotal", "label": "Shots", "displayValue": "31"},
                {"name": "hits", "label": "Hits", "displayValue": "2"},
                {"name": "faceoffPercent", "label": "Faceoff %", "displayValue": "44.5"}]}
            ],
            "players": [{"team": {"displayName": "Montreal Canadiens", "abbreviation": "MTL"}, "statistics": [
              {"name": "forwards", "labels": ["G", "A"], "athletes": [
                {"athlete": {"displayName": "Cole Caufield", "position": {"abbreviation": "RW"}}, "stats": ["1", "0"]},
                {"athlete": {}, "stats": ["0", "0"]}], "totals": ["2", "1"]},
              {"name": "empty", "labels": [], "athletes": []}]}]
          },
          "leaders": [
            {"team": {"id": "10"}, "leaders": [{"displayName": "Goals", "leaders": [{"displayValue": "1", "athlete": {"displayName": "Cole Caufield", "position": {"abbreviation": "RW"}}}]}]},
            {"team": {"id": "23"}, "leaders": [{"displayName": "Goals", "leaders": [{"displayValue": "2", "summary": "2 G", "athlete": {"displayName": "Alex Ovechkin"}}]},
                                               {"displayName": "Assists", "leaders": [{"displayValue": "1", "athlete": {"displayName": "John Carlson"}}]}]}
          ],
          "plays": [
            {"id": "1", "text": "Faceoff won by Washington", "period": {"number": 1}, "clock": {"displayValue": "20:00"}, "type": {"text": "Faceoff"}},
            {"id": "2", "text": "Caufield scores", "period": {"number": 2}, "clock": {"displayValue": "14:10"}, "team": {"id": "10"}, "awayScore": "2", "homeScore": "2", "scoringPlay": true, "type": {"text": "Goal"}},
            {"id": "3", "period": {"number": 3}, "clock": {"displayValue": "1:00"}}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun `line score keeps each side's periods in step and labels hockey periods`() {
        val line = EspnSummary.parse(summary, match).lineScore!!
        assertEquals(listOf("1", "2", "3"), line.periods)
        assertEquals(listOf("1", "1", "0"), line.away)
        // The home side hasn't played a third period yet, so it reads as a dash rather than being dropped.
        assertEquals(listOf("1", "2", "-"), line.home)
        assertEquals("2", line.awayTotal)
        assertEquals("3", line.homeTotal)
        assertEquals("MTL", line.awayLabel)
    }

    @Test
    fun `overtime and shootout get their own period labels`() {
        assertEquals(listOf("1", "2", "3", "OT", "SO"), EspnSummary.periodLabels(SportGroup.Hockey, 5))
        assertEquals(listOf("1", "2", "3", "4", "OT", "OT2"), EspnSummary.periodLabels(SportGroup.Basketball, 6))
        assertEquals(listOf("1H", "2H", "ET1"), EspnSummary.periodLabels(SportGroup.Soccer, 3))
    }

    @Test
    fun `team stats pair the sides by name, skip stats one side lacks and never repeat a label`() {
        val stats = EspnSummary.parse(summary, match).teamStats
        assertEquals(listOf("Shots", "Hits", "Faceoff %"), stats.map { it.label })
        assertEquals("24", stats[0].away)
        assertEquals("31", stats[0].home)
        assertEquals(24f / 55f, stats[0].awayShare!!, 0.001f)
    }

    @Test
    fun `leaders pair each category across the teams`() {
        val leaders = EspnSummary.parse(summary, match).leaders
        assertEquals(listOf("Goals", "Assists"), leaders.map { it.category })
        assertEquals("Cole Caufield", leaders[0].away!!.name)
        assertEquals("Alex Ovechkin", leaders[0].home!!.name)
        assertEquals("2 G", leaders[0].home!!.detail)
        assertNull(leaders[1].away)
        assertEquals("John Carlson", leaders[1].home!!.name)
    }

    @Test
    fun `plays run newest first, flag scoring and drop entries with no description`() {
        val plays = EspnSummary.parse(summary, match).plays
        assertEquals(listOf("2", "1"), plays.map { it.id })
        assertTrue(plays[0].isScoring)
        assertFalse(plays[1].isScoring)
        assertEquals("2", plays[0].period)
        assertEquals("14:10", plays[0].clock)
        assertEquals("2", plays[0].awayScore)
    }

    @Test
    fun `the box score keeps players with names and groups with rows`() {
        val box = EspnSummary.parse(summary, match).boxScore.single()
        assertEquals("MTL", box.abbreviation)
        val forwards = box.groups.single()
        assertEquals("Forwards", forwards.title)
        assertEquals(listOf("G", "A"), forwards.labels)
        assertEquals(listOf("Cole Caufield"), forwards.rows.map { it.name })
        assertEquals(listOf("2", "1"), forwards.totals)
    }

    @Test
    fun `a missing or empty payload is an empty game centre rather than a failure`() {
        assertTrue(EspnSummary.parse(null, match).isEmpty)
        assertTrue(EspnSummary.parse(parseJson("{}"), match).isEmpty)
    }

    @Test fun `full play history is retained and a review replaces rather than duplicates a stable event`() {
        val plays = (1..150).joinToString(",") { """{"id":"$it","text":"Play $it","period":{"number":1}}""" }
        val root = parseJson("""{"plays":[$plays,{"id":"120","text":"Reviewed and corrected","period":{"number":1}}]}""")
        val result = EspnSummary.parse(root, match).plays
        assertEquals(150, result.size)
        assertEquals("Reviewed and corrected", result.first { it.id == "120" }.text)
        assertEquals(1, result.first().periodNumber)
    }

    @Test fun `drives current revision replaces previous and probability rejects malformed or out of range values`() {
        val root = parseJson("""{"drives":{"previous":[{"id":"1","result":"Active"}],"current":{"id":"1","result":"Touchdown","offensivePlays":8,"yards":75,"timeElapsed":{"displayValue":"4:10"}}},"winprobability":[{"homeWinPercentage":0.6},{"homeWinPercentage":2},{"homeWinPercentage":"bad"},{"homeWinPercentage":0.9}]}""")
        val result = EspnSummary.parse(root, match)
        assertEquals("Touchdown", result.drives.single().result)
        assertEquals(8, result.drives.single().plays)
        assertEquals(listOf(0.6f, 0.9f), result.winProbability)
        assertFalse(result.isEmpty)
    }

    @Test fun `player identities starter and DNP flags remain available for phone box score`() {
        val root = parseJson("""{"boxscore":{"players":[{"team":{"abbreviation":"NE"},"statistics":[{"labels":["PTS"],"athletes":[{"athlete":{"id":"42","displayName":"Player","headshot":{"href":"https://example.com/head.png"}},"starter":true,"didNotPlay":true,"stats":["0"]}]}]}]}}""")
        val player = EspnSummary.parse(root, match).boxScore.single().groups.single().rows.single()
        assertEquals("42", player.athleteId); assertTrue(player.starter); assertTrue(player.didNotPlay)
        assertEquals("https://example.com/head.png", player.headshot)
    }
}
