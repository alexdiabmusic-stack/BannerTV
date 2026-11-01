package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Constructed schedules. These are never runtime fixture/player seeds. */
class NativeFantasyFixturesTest {
    private val now = Dates.parseIso("2026-10-24T16:00:00Z")!!
    private fun player(sport: String = "nhl", team: String = "LA") = NativeFantasyPlayer("espn-$sport-123", "Test player", team, "C", setOf("C"))
    private fun game(id: String, path: String = "hockey/nhl", home: String = "LAK", away: String = "EDM", state: GameState = GameState.PRE,
        at: Long = now + 3_600_000) = Match(id, League.find(path)!!, at, "$away at $home", "$away @ $home", state, "", TeamSide(home, home, home), TeamSide(away, away, away))
    @Test fun aliasesAndOpponentAreLeagueScopedNotCrossProviderNumericIds() {
        val games = listOf(game("same-number"), game("same-number", "baseball/mlb", "LAD", "SFG"))
        val result = NativeFantasyFixtures.games(player(), "nhl", games, now).single()
        assertTrue(result.home); assertEquals("EDM", result.opponent.abbreviation)
        assertEquals("hockey/nhl", result.match.league.path)
        assertEquals("GSW", NativeFantasyFixtures.games(player("nba", "GS"), "nba", listOf(game("nba", "basketball/nba", "GSW", "NYK")), now).single().match.home.abbreviation)
        assertTrue(NativeFantasyFixtures.games(player("nba", "LA"), "nhl", games, now).isEmpty())
    }
    @Test fun doubleheadersRemainDistinctAndLiveThenUpcomingWinsOverAnEarlierFinal() {
        val first = game("game1", "baseball/mlb", "LAD", "SDP", GameState.FINAL, now - 3_600_000)
        val second = game("game2", "baseball/mlb", "LAD", "SDP", GameState.PRE, now + 3_600_000)
        val p = player("mlb", "LAD")
        val result = NativeFantasyFixtures.games(p, "mlb", listOf(first, second), now)
        assertEquals(listOf("game2", "game1"), result.map { it.match.id })
        assertEquals(listOf("game1", "game2"), NativeFantasyFixtures.games(p, "mlb", listOf(first.copy(state = GameState.LIVE), second), now).map { it.match.id })
        assertEquals(setOf(p.id), NativeFantasyFixtures.lockedPlayers(listOf(p), "mlb", listOf(first, second), now))
    }
    @Test fun ambiguousOrMissingTeamOwnershipNeverProducesAnOpponent() {
        assertTrue(NativeFantasyFixtures.games(player(), "nhl", listOf(game("ambiguous", away = "LA")), now).isEmpty())
        assertTrue(NativeFantasyFixtures.games(player(team = ""), "nhl", listOf(game("blank")), now).isEmpty())
        assertTrue(NativeFantasyFixtures.games(player(team = "TBD"), "nhl", listOf(game("tbd", home = "TBD")), now).isEmpty())
        assertTrue(NativeFantasyFixtures.games(player(), "unsupported", listOf(game("wrong-sport")), now).isEmpty())
    }
    @Test fun activeOvernightGamesSurviveButOldFinalsAndOutOfWindowGamesDoNot() {
        val start = Dates.startOfDay(now)
        val overnight = game("overnight", state = GameState.LIVE, at = start - 2 * 3_600_000)
        val games = listOf(overnight, game("old-final", state = GameState.FINAL, at = start - 3_600_000),
            game("too-old-live", state = GameState.LIVE, at = start - 13 * 3_600_000), game("after-window", at = Dates.addDays(start, 8)))
        assertEquals(listOf("overnight"), NativeFantasyFixtures.games(player(), "nhl", games, now).map { it.match.id })
        assertTrue(NativeFantasyFixtures.lockedPlayers(listOf(player()), "nhl", listOf(overnight), now).isEmpty()) // Previous scoring day.
    }
    @Test fun lineupLocksRequireAnActualStartedGameInTheSelectedLeagueAndLocalScoringDay() {
        val p = player()
        assertTrue(NativeFantasyFixtures.lockedPlayers(listOf(p), "nhl", listOf(game("upcoming")), now).isEmpty())
        assertTrue(NativeFantasyFixtures.lockedPlayers(listOf(p), "nhl", listOf(game("other-league", "basketball/nba", "LA", "EDM", GameState.LIVE)), now).isEmpty())
        assertEquals(setOf(p.id), NativeFantasyFixtures.lockedPlayers(listOf(p), "nhl", listOf(game("live", state = GameState.LIVE)), now))
    }
    @Test fun localCalendarWindowIncludesTheEighthDayAcrossDstAndNeverAssumesEightTimes24Hours() {
        val original = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val clock = Dates.parseIso("2026-10-31T12:00:00-04:00")!!
            val eighth = Dates.parseIso("2026-11-07T23:30:00-05:00")!!
            val after = Dates.parseIso("2026-11-08T00:00:00-05:00")!!
            assertEquals(listOf("eighth"), NativeFantasyFixtures.games(player(), "nhl", listOf(game("eighth", at = eighth), game("after", at = after)), clock).map { it.match.id })
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun exactIdentityUpdatesAcceptFinalWithoutRevertingFromLaterStaleScheduleCopies() {
        val live = game("same", state = GameState.LIVE)
        val final = live.copy(state = GameState.FINAL, home = live.home.copy(score = "3"), away = live.away.copy(score = "2"))
        val result = NativeFantasyFixtures.games(player(), "nhl", listOf(live, final, live), now).single()
        assertEquals(GameState.FINAL, result.match.state); assertEquals("2–3", result.match.displayScore)
        assertNull(result.match.nativeReference) // No invented native identity or player/game stat join.
    }
}
