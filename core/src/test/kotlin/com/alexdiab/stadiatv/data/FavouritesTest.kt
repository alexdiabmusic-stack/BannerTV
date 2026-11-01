package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.domain.TeamSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouritesTest {
    private val nhl = "hockey/nhl"
    private val nba = "basketball/nba"
    private val wnba = "basketball/wnba"

    @Test
    fun choosingASportSelectsAllItsLeaguesAndRemovingItClearsThem() {
        val on = Favourites.toggleSport(emptySet(), SportGroup.Basketball)
        assertEquals(setOf(nba, wnba), on)
        assertEquals(setOf(nhl), Favourites.toggleSport(setOf(nhl) + on, SportGroup.Basketball))
    }

    @Test
    fun aSportCountsAsChosenWhileAnyOfItsLeaguesIs() {
        assertEquals(setOf(SportGroup.Basketball, SportGroup.Hockey), Favourites.sportsOf(setOf(nba, nhl)))
        // One league left in a sport and the sport is still on; toggling it then switches the whole sport off.
        assertEquals(emptySet<String>(), Favourites.toggleSport(setOf(wnba), SportGroup.Basketball))
    }

    @Test
    fun narrowingLeaguesDropsStarredTeamsFromTheOnesLeft() {
        val teams = listOf(FavoriteTeam(nhl, "1", "Bruins"), FavoriteTeam(nba, "2", "Celtics"))
        assertEquals(listOf("Bruins"), Favourites.pruneTeams(teams, setOf(nhl)).map { it.name })
    }

    @Test
    fun theSettingsSummaryCountsWhatWasChosen() {
        assertEquals("Choose your sports, leagues and teams", Favourites.summary(UserSettings()))
        val settings = UserSettings(selectedLeagueIds = setOf(nba, wnba, nhl), favoriteTeams = listOf(FavoriteTeam(nhl, "1", "Bruins")))
        assertEquals("2 sports · 3 leagues · 1 team", Favourites.summary(settings))
    }

    private fun match(league: String, id: String, home: String, away: String, state: GameState = GameState.PRE, hoursAway: Int = 3, now: Long = NOW) = Match(
        id = id, league = League.find(league)!!, startMillis = now + hoursAway * 3_600_000L, name = "$away at $home", shortName = "$away @ $home",
        state = state, statusDetail = "", home = TeamSide(home, home, home.take(3).uppercase(), teamId = "t-$home", score = if (state == GameState.PRE) null else "1"),
        away = TeamSide(away, away, away.take(3).uppercase(), teamId = "t-$away", score = if (state == GameState.PRE) null else "0"),
    )

    private fun featured(settings: UserSettings, vararg games: Match): Match? =
        FeedSectionsBuilder.build(games.toList(), settings, NOW, { 0 }, false, null).prime

    @Test
    fun aStarredTeamsGameLeadsEvenWhenABiggerOneIsLive() {
        val bruins = FavoriteTeam(nhl, "t-Bruins", "Bruins", "BRU")
        val settings = UserSettings(selectedLeagueIds = setOf(nhl, nba), favoriteTeams = listOf(bruins))
        val liveNba = match(nba, "a", "Lakers", "Celtics", GameState.LIVE)
        val bruinsTonight = match(nhl, "b", "Bruins", "Leafs", hoursAway = 5)
        assertEquals("b", featured(settings, liveNba, bruinsTonight)?.id)
    }

    @Test
    fun aStarredTeamsGameDaysAwayDoesNotBeatAGameInProgressInAFollowedLeague() {
        val bruins = FavoriteTeam(nhl, "t-Bruins", "Bruins", "BRU")
        val settings = UserSettings(selectedLeagueIds = setOf(nhl, nba), favoriteTeams = listOf(bruins))
        val liveNba = match(nba, "a", "Lakers", "Celtics", GameState.LIVE)
        val bruinsNextWeek = match(nhl, "b", "Bruins", "Leafs", hoursAway = 96)
        assertEquals("a", featured(settings, liveNba, bruinsNextWeek)?.id)
    }

    @Test
    fun followedLeaguesBeatOthersAndOthersOnlyFillAnEmptySlot() {
        val settings = UserSettings(selectedLeagueIds = setOf(nhl))
        val liveNba = match(nba, "a", "Lakers", "Celtics", GameState.LIVE)
        val hockeyLater = match(nhl, "b", "Bruins", "Leafs", hoursAway = 6)
        assertEquals("b", featured(settings, liveNba, hockeyLater)?.id)
        // With nothing of theirs on the board, a game from elsewhere is better than an empty card.
        assertEquals("a", featured(settings, liveNba)?.id)
    }

    @Test
    fun withNothingChosenEveryLeagueCountsAndTheLiveGameWins() {
        val liveNba = match(nba, "a", "Lakers", "Celtics", GameState.LIVE)
        val hockeyLater = match(nhl, "b", "Bruins", "Leafs", hoursAway = 6)
        assertEquals("a", featured(UserSettings(), liveNba, hockeyLater)?.id)
    }

    @Test
    fun homeListsHoldOnlyTheViewersGamesWhileTheSportsHubKeepsEverything() {
        val settings = UserSettings(selectedLeagueIds = setOf(nhl))
        val liveNba = match(nba, "a", "Lakers", "Celtics", GameState.LIVE)
        val liveNhl = match(nhl, "b", "Bruins", "Leafs", GameState.LIVE)
        val sections = FeedSectionsBuilder.build(listOf(liveNba, liveNhl), settings, NOW, { 0 }, false, null)
        assertEquals(setOf("a", "b"), sections.liveNow.map { it.id }.toSet())
        assertEquals(listOf("b"), sections.liveForYou.map { it.id })
    }

    @Test
    fun aStarredTeamsGameStaysInScopeAfterItsLeagueIsUnfollowed() {
        val celtics = FavoriteTeam(nba, "t-Celtics", "Celtics", "CEL")
        val settings = UserSettings(selectedLeagueIds = setOf(nhl), favoriteTeams = listOf(celtics))
        val game = match(nba, "a", "Lakers", "Celtics", GameState.LIVE)
        assertTrue(settings.inScope(game))
        assertFalse(UserSettings(selectedLeagueIds = setOf(nhl)).inScope(game))
        assertNotEquals(null, FeedSectionsBuilder.build(listOf(game), settings, NOW, { 0 }, false, null).liveForYou.firstOrNull())
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
