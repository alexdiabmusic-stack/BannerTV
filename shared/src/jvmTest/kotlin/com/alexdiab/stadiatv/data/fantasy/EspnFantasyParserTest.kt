package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class EspnFantasyParserTest {
    // The league document the iOS app's own tests use, extended with a second matchup week.
    private val league = parseJson(
        """
        {
          "id": 12345, "seasonId": 2026, "scoringPeriodId": 12,
          "status": {"currentMatchupPeriod": 4, "latestScoringPeriod": 12, "isActive": true},
          "settings": {"name": "Hockey League", "size": 2},
          "members": [{"id": "{OWNER}", "displayName": "Alex"}],
          "teams": [
            {"id": 1, "name": "Banner Skaters", "abbrev": "STAD", "owners": ["{OWNER}"],
             "record": {"overall": {"wins": 3, "losses": 1, "ties": 0, "pointsFor": 240.5, "pointsAgainst": 220.0, "rankCalculatedFinal": 1}},
             "roster": {"entries": [
               {"lineupSlotId": 7, "playerPoolEntry": {"player": {"id": 3897, "fullName": "Connor McDavid", "proTeamId": 6, "injuryStatus": "ACTIVE"}}},
               {"lineupSlotId": 0, "playerPoolEntry": {"appliedStatTotal": 12.5, "player": {"id": 3114, "fullName": "Auston Matthews", "proTeamId": 22, "injuryStatus": "DAY_TO_DAY"}}},
               {"lineupSlotId": 8, "playerPoolEntry": {"player": {"id": 1, "fullName": "Hurt Player", "proTeamId": 1}}}
             ]}},
            {"id": 2, "location": "Opponent", "nickname": "Wings", "owners": ["{OTHER}"],
             "record": {"overall": {"wins": 1, "losses": 3, "ties": 1, "pointsFor": 200.0, "pointsAgainst": 230.0, "rankCalculatedFinal": 2}},
             "roster": {"entries": []}}
          ],
          "schedule": [
            {"id": 98, "matchupPeriodId": 3, "home": {"teamId": 1, "totalPoints": 70.0}, "away": {"teamId": 2, "totalPoints": 99.0}},
            {"id": 99, "matchupPeriodId": 4, "home": {"teamId": 2, "totalPoints": 88.0}, "away": {"teamId": 1, "totalPoints": 91.5}}
          ]
        }
        """.trimIndent(),
    )

    private val connection = FantasyConnection(FantasySport.Hockey.code, 2026, "12345", teamId = 1)

    @Test
    fun `teams carry names records and rank`() {
        val teams = EspnFantasyParser.teams(league)
        assertEquals(listOf("Banner Skaters", "Opponent Wings"), teams.map { it.name })
        assertEquals("3-1", teams[0].record)
        assertEquals("1-3-1", teams[1].record)
        assertEquals(1, teams[0].rank)
        assertEquals(240.5, teams[0].pointsFor!!, 0.0)
    }

    @Test
    fun `the roster lists starters first then bench then injured reserve`() {
        val roster = EspnFantasyParser.roster(league, teamId = 1, sport = FantasySport.Hockey)
        assertEquals(listOf("Auston Matthews", "Connor McDavid", "Hurt Player"), roster.map { it.name })
        assertEquals(listOf("C", "BN", "IR"), roster.map { it.slot })
        assertEquals(listOf(SlotKind.Starter, SlotKind.Bench, SlotKind.Reserve), roster.map { it.kind })
        assertEquals(12.5, roster.first().points!!, 0.0)
        assertNull(roster[1].points)
    }

    @Test
    fun `an active player has no injury tag and a questionable one keeps it`() {
        val roster = EspnFantasyParser.roster(league, 1, FantasySport.Hockey).associateBy { it.id }
        assertNull(roster["3897"]!!.injuryStatus)
        assertEquals("DAY_TO_DAY", roster["3114"]!!.injuryStatus)
    }

    @Test
    fun `the matchup is the current period with the user on either side`() {
        val matchup = EspnFantasyParser.matchup(league, teamId = 1)!!
        assertEquals(4, matchup.week)
        assertEquals(91.5, matchup.me.points, 0.0)
        assertEquals("Opponent Wings", matchup.opponent!!.name)
        assertEquals(88.0, matchup.opponent!!.points, 0.0)
        assertTrue(matchup.isWinning)
        assertEquals(3.5, matchup.margin, 0.0)
    }

    @Test
    fun `an owner's SWID finds their team regardless of braces and case`() {
        assertEquals(1, EspnFantasyParser.ownerTeamId(league, "owner"))
        assertEquals(1, EspnFantasyParser.ownerTeamId(league, "{owner}"))
        assertNull(EspnFantasyParser.ownerTeamId(league, "{NOBODY}"))
    }

    @Test
    fun `a snapshot ties it all together`() {
        val snapshot = EspnFantasyParser.snapshot(league, connection, nowMillis = 5L)
        assertEquals("Hockey League", snapshot.leagueName)
        assertEquals("Banner Skaters", snapshot.myTeam!!.name)
        assertEquals(listOf(1, 2), snapshot.standings.map { it.id })
        assertEquals(3, snapshot.roster.size)
        assertEquals(5L, snapshot.refreshedAtMillis)
    }

    @Test
    fun `without a team id there is a league but no roster or matchup`() {
        val snapshot = EspnFantasyParser.snapshot(league, connection.copy(teamId = null), 0L)
        assertNull(snapshot.myTeam)
        assertTrue(snapshot.roster.isEmpty())
        assertNull(snapshot.matchup)
    }

    @Test
    fun `lineup slot ids read as ESPN position labels per sport`() {
        assertEquals("QB", FantasySport.Football.slotLabel(0))
        assertEquals("D", FantasySport.Hockey.slotLabel(4))
        assertEquals("SS", FantasySport.Baseball.slotLabel(4))
        assertEquals("BN", FantasySport.Basketball.slotLabel(null))
        assertEquals("IL", FantasySport.Baseball.slotLabel(14))
    }

    @Test
    fun `winter sports are numbered by the year they end and football by the year it starts`() {
        val october2026 = GregorianCalendar(2026, Calendar.OCTOBER, 14)
        val january2027 = GregorianCalendar(2027, Calendar.JANUARY, 20)
        assertEquals(2027, FantasySport.Hockey.currentSeason(october2026))
        assertEquals(2027, FantasySport.Hockey.currentSeason(january2027))
        assertEquals(2026, FantasySport.Football.currentSeason(october2026))
        assertEquals(2026, FantasySport.Football.currentSeason(january2027))
        assertEquals(2026, FantasySport.Baseball.currentSeason(october2026))
    }

    @Test
    fun `a connection key identifies the league across seasons and sports`() {
        assertEquals("espn:fhl:2026:12345", connection.key)
        assertEquals(FantasySport.Hockey, connection.sport)
    }
}
