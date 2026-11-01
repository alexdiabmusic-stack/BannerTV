package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class MatchAlertsTest {
    private val now = 1800000000000L
    private val league = League.find("hockey/nhl")!!
    private fun match(id: String, start: Long, state: GameState = GameState.PRE, home: String? = null, away: String? = null) = Match(id, league, start, "Away at Home", "Away @ Home", state, "3rd 2:00", TeamSide("Home", "Home", "H", score = home, teamId = "home"), TeamSide("Away", "Away", "A", score = away, teamId = "away"))
    private val settings = UserSettings(favoriteTeams = listOf(FavoriteTeam(league.path, "home", "Home")))
    @Test fun disabledAlertsAndUnfollowedGamesAreQuiet() {
        assertTrue(MatchAlertPlanner.plan(listOf(match("1", now + 3600000)), settings, MatchAlertOptions(), emptyMap(), emptySet(), now).isEmpty())
        assertTrue(MatchAlertPlanner.plan(listOf(match("1", now + 3600000)), UserSettings(), MatchAlertOptions(enabled = true), emptyMap(), emptySet(), now).isEmpty())
    }
    @Test fun startBudgetHorizonAndManualRemindersAreRespected() {
        val games = (1..60).map { match("$it", now + it * 3600000) } + match("far", now + 8 * 86400000L)
        val plan = MatchAlertPlanner.plan(games, settings, MatchAlertOptions(enabled = true), emptyMap(), setOf(games[0].key), now)
        assertEquals(40, plan.size); assertEquals("2", plan.first().game?.id); assertFalse(plan.any { it.game?.id == "far" })
    }
    @Test fun liveWindowCloseThresholdSpoilerAndLedgerDeduplicate() {
        val game = match("1", now - 600000, GameState.LIVE, "3", "2")
        val options = MatchAlertOptions(enabled = true)
        val plan = MatchAlertPlanner.plan(listOf(game), settings, options, emptyMap(), emptySet(), now)
        assertEquals(2, plan.size)
        assertTrue(MatchAlertPlanner.plan(listOf(game), settings, options, plan.associate { it.key to now }, emptySet(), now).isEmpty())
        assertEquals(1, MatchAlertPlanner.plan(listOf(game), settings.copy(spoilerFreeMode = true), options, emptyMap(), emptySet(), now).size)
        assertTrue(MatchAlertPlanner.plan(listOf(game.copy(startMillis = now - 3600000, home = game.home.copy(score = "5"))), settings, options, emptyMap(), emptySet(), now).isEmpty())
    }
    @Test fun soccerHomeFirstDoesNotMutateCanonicalScores() {
        val game = match("soccer", now, GameState.FINAL, "0", "2").copy(league = League.find("soccer/eng.1")!!)
        assertEquals("0–2", game.displayScore); assertEquals("Home", game.displaySides.first().displayName); assertEquals("2", game.away.score)
    }
}
