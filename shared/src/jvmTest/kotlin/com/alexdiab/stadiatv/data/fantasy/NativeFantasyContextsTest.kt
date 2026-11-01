package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test

class NativeFantasyContextsTest {
    private val now = Dates.parseIso("2026-10-24T16:00:00Z")!!
    private fun player(id: String = "nhl:1", team: String = "LA") = NativeFantasyPlayer(id, "Constructed player $id", team, "L", setOf("LW", "F", "UTIL"))
    private fun league(players: List<NativeFantasyPlayer> = listOf(player())): NativeFantasyLeague {
        var result = NativeFantasyEngine.create("Constructed local league", "Team", "nhl", true, roster = mapOf("F" to 1, "BN" to 2))
        for (person in players) result = NativeFantasyEngine.draft(result, person, players)
        return result
    }
    private fun game(id: String = "game", home: String = "LAK", away: String = "EDM", at: Long = now + 3_600_000, state: GameState = GameState.PRE) =
        Match(id, League.find("hockey/nhl")!!, at, "$away at $home", "$away @ $home", state, "Reported", TeamSide(home, home, home), TeamSide(away, away, away))
    @Test fun teammatesShareOneEventContextWithoutPretendingTheyAreConfirmedGameParticipants() {
        val league = league(listOf(player(), player("nhl:2")))
        val index = NativeFantasyContexts.build(league, listOf(game()), now)
        assertEquals(1, index.contexts.size); assertEquals(2, index.contexts.single().players.size)
        assertEquals(setOf("Unassigned"), index.contexts.single().players.map { it.lineupLabel }.toSet())
        assertEquals(league.id, index.owner!!.leagueId)
    }
    @Test fun sameCountRosterReplacementChangesTheFullOwnerStamp() {
        val first = league()
        val second = first.copy(teams = listOf(first.userTeam.copy(roster = first.userTeam.roster.map { it.copy(player = player("nhl:2")) })), picks = first.picks.map { if (it.player != null) it.copy(player = "nhl:2") else it })
        assertNotEquals(NativeFantasyContexts.stamp(first, now), NativeFantasyContexts.stamp(second, now))
        assertEquals(first.userTeam.roster.size, second.userTeam.roster.size)
    }
    @Test fun correctionsRejectAnOldAssociationBeforeItsBackgroundIndexIsRebuilt() {
        val match = game(); val index = NativeFantasyContexts.build(league(), listOf(match), now)
        assertNotNull(index.contextFor(match))
        assertNull(index.contextFor(match.copy(startMillis = match.startMillis + 3_600_000)))
        assertNull(index.contextFor(match.copy(away = match.away.copy(abbreviation = "ANA"))))
        assertNull(index.contextFor(match.copy(league = League.find("basketball/nba")!!)))
    }
    @Test fun scoreClockAndFinalUpdatesUseTheCurrentMatchNotAnOldLiveCopy() {
        val match = game(state = GameState.LIVE); val index = NativeFantasyContexts.build(league(), listOf(match), now)
        val final = match.copy(state = GameState.FINAL, statusDetail = "Final", home = match.home.copy(score = "3"))
        assertEquals(final, index.contextFor(final)!!.match)
    }
    @Test fun doubleheaderEventsRemainDistinctAndTheirPlayersAreNotCountedAsDifferentPeople() {
        val first = game("one", at = now, state = GameState.FINAL); val second = game("two", at = now + 4 * 3_600_000)
        val index = NativeFantasyContexts.build(league(), listOf(first, second), now)
        assertEquals(listOf("two", "one"), index.contexts.map { it.match.id })
        assertEquals(1, index.contexts.flatMap { it.players }.distinctBy { it.entry.id }.size)
    }
    @Test fun fantasyLineupRoleIsTheSelectedLocalDayAndDoesNotClaimRealStartingStatus() {
        val base = league(); val entry = base.userTeam.roster.single()
        val moved = NativeFantasyEngine.move(base, Dates.format("yyyy-MM-dd", now), entry.id, "F")
        assertEquals("Fantasy lineup · F", NativeFantasyContexts.build(moved, listOf(game()), now).contexts.single().players.single().lineupLabel)
        assertEquals("Unassigned", NativeFantasyContexts.build(moved, listOf(game(at = now + 24 * 3_600_000)), now + 24 * 3_600_000).contexts.single().players.single().lineupLabel)
        assertEquals("Unassigned", NativeFantasyContexts.build(moved, listOf(game(at = now + 24 * 3_600_000)), now).contexts.single().players.single().lineupLabel)
    }
    @Test fun unknownMixedAndForeignSportPlayerIdentitiesCannotProduceCrossScreenBadges() {
        val base = league()
        val invalid = base.copy(teams = listOf(base.userTeam.copy(roster = base.userTeam.roster.map { it.copy(player = player("nba:1")) })))
        assertNull(NativeFantasyContexts.build(invalid, listOf(game()), now).owner)
        assertTrue(NativeFantasyFixtures.games(player("nba:1"), "nhl", listOf(game()), now).isEmpty())
        assertTrue(NativeFantasyFixtures.games(player("1"), "nhl", listOf(game()), now).isEmpty())
    }
    @Test fun selectionFallsBackOnlyWithinTheActualSavedLibraryAndDoesNotBlendLeagues() {
        val first = league(); val second = league(listOf(player("nhl:2", "ANA")))
        assertEquals(second, NativeFantasyContexts.selected(NativeFantasyLibrary(selected = second.id, leagues = listOf(first, second))))
        assertEquals(first, NativeFantasyContexts.selected(NativeFantasyLibrary(selected = "missing", leagues = listOf(first, second))))
        assertNull(NativeFantasyContexts.selected(NativeFantasyLibrary()))
    }
}
