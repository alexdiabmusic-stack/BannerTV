package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class PhoneFollowingTest {
    private val nhl = League.find("hockey/nhl")!!
    private val nfl = League.find("football/nfl")!!
    private val nba = League.find("basketball/nba")!!
    private val ottawa = FavoriteTeam(nhl.path, "9", "Ottawa Senators", "OTT")
    private val now = Dates.parseIso("2026-10-23T12:00:00-04:00")!!
    private fun game(id: String, league: League = nhl, team: String = "OTT", start: Long = now + 60_000, state: GameState = GameState.PRE) = Match(
        id, league, start, "Away at Home", "Away @ Home", state, "Status",
        TeamSide("Home", "Home", "HOME"), TeamSide(team, team, team),
    )

    @Test fun `Following without explicit choices does not show the discovery catalog`() {
        assertTrue(followingMatches(listOf(game("a")), UserSettings()).isEmpty())
    }
    @Test fun `favorite team narrows its own league instead of importing every game`() {
        val settings = UserSettings(favoriteTeams = listOf(ottawa), selectedLeagueIds = setOf(nhl.path, nfl.path))
        val games = listOf(game("ott"), game("tor", team = "TOR"), game("nfl", nfl), game("nba", nba))
        assertEquals(listOf("ott", "nfl"), followingMatches(games, settings).map { it.id })
    }
    @Test fun `explicit league without a favorite team includes every fixture`() {
        assertEquals(2, followingMatches(listOf(game("ott"), game("tor", team = "TOR")), UserSettings(selectedLeagueIds = setOf(nhl.path))).size)
    }
    @Test fun `selection is league scoped and removed selections fall back safely`() {
        val other = FavoriteTeam(nfl.path, "9", "Football", "OTT")
        val settings = UserSettings(favoriteTeams = listOf(ottawa, other))
        val games = listOf(game("ott"), game("nfl", nfl))
        assertEquals(listOf("ott"), followingMatches(games, settings, ottawa.key).map { it.id })
        assertEquals(2, followingMatches(games, settings, "removed-team").size)
    }
    @Test fun `duplicate event keys collapse but same ids in different leagues remain`() {
        val settings = UserSettings(selectedLeagueIds = setOf(nhl.path, nfl.path))
        assertEquals(2, followingMatches(listOf(game("1"), game("1"), game("1", nfl)), settings).size)
    }
    @Test fun `summary includes live games from yesterday but only todays non-final games in Today`() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val games = listOf(game("overnight", start = now - 24 * 3_600_000, state = GameState.LIVE), game("today"), game("final", state = GameState.FINAL), game("late", start = Dates.parseIso("2026-10-24T00:01:00-04:00")!!))
            val summary = followingOverview(games, now)
            assertEquals(listOf("overnight"), summary.live.map { it.id }); assertEquals(1, summary.today); assertEquals(2, summary.thisWeek)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun `coming up drops both-TBD fixtures and expired pregame states but preserves real event sports`() {
        val tbd = game("tbd").copy(home = TeamSide("TBD", "", ""), away = TeamSide(" ", "", ""))
        val race = tbd.copy(id = "f1", league = League.find("racing/f1")!!, name = "Grand Prix")
        val result = followingOverview(listOf(tbd, race, game("expired", start = now - 1), game("announced")), now)
        assertEquals(listOf("f1", "announced"), result.upcoming.map { it.id })
    }
    @Test fun `week boundary uses calendar days across daylight savings`() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val before = Dates.parseIso("2026-10-30T12:00:00-04:00")!!
            val end = Dates.parseIso("2026-11-06T12:00:00-05:00")!!
            assertEquals(1, followingOverview(listOf(game("end", start = end)), before).thisWeek)
            assertEquals(0, followingOverview(listOf(game("after", start = end + 1)), before).thisWeek)
        } finally { TimeZone.setDefault(old) }
    }
    private fun group(name: String, from: Int, to: Int) = StandingGroup(name, (from..to).map { StandingRow("$it", "Team $it", "T$it", null, "10-5") })
    @Test fun `standings selects favorite group and preserves the original rank around the team`() {
        val snapshot = followingStandings(listOf(group("East", 1, 5), group("West", 6, 15)), FavoriteTeam(nhl.path, "12", "Team 12"))!!
        assertEquals("West", snapshot.name); assertEquals(listOf(5, 6, 7, 8, 9), snapshot.rows.map { it.rank })
        assertEquals("12", snapshot.rows.single { it.highlighted }.row.teamId)
    }
    @Test fun `bottom team still gets five surrounding rows without relabeling it first`() {
        val rows = followingStandings(listOf(group("All", 1, 10)), FavoriteTeam(nhl.path, "10", "Team 10"))!!.rows
        assertEquals(listOf(6, 7, 8, 9, 10), rows.map { it.rank }); assertTrue(rows.last().highlighted)
    }
    @Test fun `standings without a team shows top five and unavailable group is explicit`() {
        assertEquals(listOf(1, 2, 3, 4, 5), followingStandings(listOf(group("All", 1, 10)), null)!!.rows.map { it.rank })
        assertNull(followingStandings(listOf(StandingGroup("Empty", emptyList())), null))
    }
    @Test fun `native abbreviation fallback highlights equivalent team without cross league target`() {
        val row = StandingRow("native-ott", "Senators", "OTT", null, "10-5")
        assertTrue(followingStandings(listOf(StandingGroup("East", listOf(row))), ottawa)!!.rows.single().highlighted)
        val settings = UserSettings(favoriteTeams = listOf(ottawa), selectedLeagueIds = setOf(nfl.path))
        assertEquals(nfl, followingStandingsTarget(settings, nfl.path)!!.league)
        assertNull(followingStandingsTarget(settings, nfl.path)!!.team)
    }
    @Test fun `news uses actual followed entities with bounded league count not catalog fallback`() {
        assertTrue(followingNewsLeagues(UserSettings(), "All").isEmpty())
        val settings = UserSettings(favoriteTeams = listOf(ottawa), selectedLeagueIds = League.ALL.map { it.path }.toSet())
        assertEquals(listOf(nhl), followingNewsLeagues(settings, ottawa.key))
        assertEquals(3, followingNewsLeagues(settings, "All").size)
    }
    @Test fun `Sports Day prioritizes live then pregame then final and hides every winner badge in spoiler mode`() {
        val final = game("final", state = GameState.FINAL).copy(home = TeamSide("Home", "Home", "HOME", score = "3", isWinner = true))
        val live = game("live", state = GameState.LIVE)
        assertEquals(listOf("live", "pre", "final"), sportsDayMatches(listOf(final, game("pre"), live, live)).map { it.id })
        assertTrue(sportsDayShowsWinner(final, final.home, false)); assertFalse(sportsDayShowsWinner(final, final.home, true))
        assertFalse(sportsDayShowsWinner(live, final.home, false))
    }
}
