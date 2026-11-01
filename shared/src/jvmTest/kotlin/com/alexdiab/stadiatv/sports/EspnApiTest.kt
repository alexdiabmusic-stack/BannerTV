package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.Calendar
import org.junit.Test

class EspnApiTest {
    private fun league(path: String) = requireNotNull(League.find(path)) { "unknown league $path" }

    @Test
    fun mapsScoreboardEventToMatch() {
        val payload = parseJson(
            """{"events":[{"id":"401","date":"2026-08-19T23:00Z","name":"Toronto vs New York","shortName":"TOR @ NY",
            "status":{"type":{"state":"in","shortDetail":"Q3 4:21"}},
            "competitions":[{"venue":{"fullName":"Arena"},"broadcasts":[{"names":["ESPN"]}],
            "competitors":[{"homeAway":"home","score":"88","team":{"id":"1","displayName":"New York","abbreviation":"NY","color":"006BB6"}},
            {"homeAway":"away","score":"91","team":{"id":"2","displayName":"Toronto","abbreviation":"TOR"}}]}]}]}""",
        )

        val matches = EspnApi.parseMatches(payload, league("basketball/nba"))

        assertEquals(1, matches.size)
        val match = matches[0]
        assertEquals(GameState.LIVE, match.state)
        assertEquals("TOR", match.away.abbreviation)
        assertEquals("88", match.home.score)
        assertEquals("006BB6", match.home.colorHex)
        assertEquals("Arena", match.venue)
        assertTrue(match.broadcasts.contains("ESPN"))
    }

    @Test
    fun competitionStatusTakesPrecedenceOverStaleEventStatus() {
        val payload = parseJson(
            """{"events":[{"id":"nhl-1","date":"2030-01-01T20:00Z","name":"Toronto at Montreal","status":{"type":{"state":"pre"}},
            "competitions":[{"status":{"type":{"state":"in","shortDetail":"2nd 4:21"}},
            "competitors":[{"homeAway":"home","team":{"id":"1","displayName":"Montreal Canadiens"}},
            {"homeAway":"away","team":{"id":"2","displayName":"Toronto Maple Leafs"}}]}]}]}""",
        )

        val match = EspnApi.parseMatches(payload, league("hockey/nhl"))[0]

        assertEquals(GameState.LIVE, match.state)
        assertEquals("2nd 4:21", match.statusDetail)
    }

    @Test
    fun yearRangeIsSplitIntoThirtyDayScoreboardQueries() {
        val start = Calendar.getInstance().apply { set(2030, Calendar.JANUARY, 1, 12, 0, 0) }.timeInMillis

        val queries = EspnApi.rangeQueries(start, 365, now = 0L)

        assertEquals(13, queries.size)
        assertEquals("20300101-20300130", queries[0])
        assertEquals("20301227-20301231", queries[12])
    }

    @Test
    fun todayIsAlwaysRequestedOnItsOwnSoLiveScoresAreNeverStale() {
        val now = Calendar.getInstance().apply { set(2030, Calendar.JANUARY, 10, 9, 0, 0) }.timeInMillis
        val start = Calendar.getInstance().apply { set(2030, Calendar.JANUARY, 1, 0, 0, 0) }.timeInMillis

        val queries = EspnApi.rangeQueries(start, 30, now)

        assertEquals("20300101-20300130", queries[0])
        assertEquals("20300110", queries.last())
    }

    @Test
    fun golfLeaderboardMapsTopTwoAthletesIntoTelevisionSides() {
        val payload = parseJson(
            """{"events":[{"id":"golf-1","date":"2030-04-01T12:00Z","name":"The Masters","competitions":[{"competitors":[
            {"order":2,"score":"-7","athlete":{"id":"b","displayName":"Second Player"}},
            {"order":1,"score":"-9","athlete":{"id":"a","displayName":"Leader Player"}}]}]}]}""",
        )

        val match = EspnApi.parseMatches(payload, league("golf/pga"))[0]

        assertEquals("Leader Player", match.away.displayName)
        assertEquals("-9", match.away.score)
        assertEquals("#1", match.away.record)
        assertEquals("Second Player", match.home.displayName)
    }

    @Test
    fun parsesNestedStandingsAndRecords() {
        val root = parseJson(
            """{"children":[{"name":"Atlantic","standings":{"entries":[{"team":{"id":"1","displayName":"Toronto","abbreviation":"TOR"},
            "stats":[{"type":"wins","displayValue":"50"},{"type":"losses","displayValue":"20"},{"type":"streak","displayValue":"W3"}]}]}}]}""",
        )

        val groups = EspnApi.parseStandings(root)

        assertEquals("Atlantic", groups[0].name)
        assertEquals("ESPN", groups[0].source)
        assertEquals("50-20", groups[0].rows[0].record)
        assertEquals("W3", groups[0].rows[0].values["streak"])
    }

    @Test
    fun parsesGroupedRoster() {
        val root = parseJson("""{"athletes":[{"position":"offense","items":[{"id":"7","displayName":"Player One","jersey":"12","position":{"abbreviation":"QB"}}]}]}""")

        val groups = EspnApi.parseRoster(root)

        assertEquals("offense", groups[0].title)
        assertEquals("QB", groups[0].athletes[0].position)
    }

    @Test
    fun parsesRacingFieldBeyondTopTwoInFinishingOrder() {
        val root = parseJson(
            """{"events":[{"competitions":[{"type":{"text":"Race"},"competitors":[
            {"order":2,"athlete":{"id":"2","displayName":"Driver B"},"vehicle":{"manufacturer":"Team B"}},
            {"order":1,"athlete":{"id":"1","displayName":"Driver A"},"vehicle":{"manufacturer":"Team A"}}]}]}]}""",
        )

        val field = EspnApi.parseField(root, league("racing/f1"))

        assertEquals(2, field.size)
        assertEquals("Driver A", field[0].athlete.name)
        assertEquals("Team A", field[0].manufacturer)
    }

    @Test
    fun missingOrNullFieldsNeverCrashParsing() {
        val payload = parseJson("""{"events":[{"id":null,"competitions":[{"competitors":[{"homeAway":"home","team":null}]}]},{"nope":1}]}""")

        val matches = EspnApi.parseMatches(payload, league("football/nfl"))

        assertEquals(1, matches.size)
        assertEquals(GameState.PRE, matches[0].state)
    }

    @Test
    fun `US league logos use the dark set and other logos are untouched`() {
        assertEquals("https://a.espncdn.com/i/teamlogos/nhl/500-dark/wsh.png", EspnApi.darkVariant("https://a.espncdn.com/i/teamlogos/nhl/500/scoreboard/wsh.png"))
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500-dark/sa.png", EspnApi.darkVariant("https://a.espncdn.com/i/teamlogos/nba/500/sa.png"))
        val soccer = "https://a.espncdn.com/i/teamlogos/soccer/500/359.png"
        assertEquals(soccer, EspnApi.darkVariant(soccer))
    }
}
