package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.*
import org.junit.Test

class SleeperFantasyTest {
    @Test fun rosterOwnershipDecimalPointsAndByeDoNotGuessOpponent() {
        val league = SleeperLeague("l", "League", "2026", "in_season", listOf("QB", "RB", "BN"), emptyMap())
        val users = parseJson("""[{"user_id":"u","metadata":{"team_name":"My team"}},{"user_id":"v","display_name":"Other"}]""")
        val rosters = parseJson("""[{"roster_id":1,"owner_id":"u","players":["p","q"],"starters":["p","0"],"reserve":["q"],"settings":{"wins":3,"losses":1,"fpts":123,"fpts_decimal":45}},{"roster_id":2,"owner_id":"v","settings":{"wins":2}}]""")
        val matches = parseJson("""[{"roster_id":1,"matchup_id":null,"points":12.3},{"roster_id":2,"matchup_id":null,"points":99}]""")
        val snapshot = SleeperFantasyApi.snapshot(SleeperAccount("u", "u", "U"), league, 7, users, rosters, matches, 1000)
        assertEquals(1, snapshot.me); assertEquals("My team", snapshot.teams.first().name); assertEquals(123.45, snapshot.teams.first().pointsFor!!, .0001)
        assertNull(snapshot.opponent); assertEquals(setOf("q"), snapshot.teams.first().reserve); assertEquals(listOf("p", "0"), snapshot.teams.first().starters)
    }
    @Test fun matchupCustomPointsAndUnknownScoresArePreserved() {
        val league = SleeperLeague("l", "League", "2026", "in_season", emptyList(), emptyMap())
        val snapshot = SleeperFantasyApi.snapshot(SleeperAccount("u", "u", "U"), league, 1, parseJson("[]"), parseJson("""[{"roster_id":1,"owner_id":"u"},{"roster_id":2}]"""), parseJson("""[{"roster_id":1,"matchup_id":4,"points":10,"custom_points":12,"players_points":{"p":5.5}},{"roster_id":2,"matchup_id":4,"points":null}]"""))
        assertEquals(12.0, snapshot.home!!.points!!, .001); assertEquals(5.5, snapshot.home.playerPoints["p"]!!, .001); assertNull(snapshot.opponent!!.points)
    }
}
