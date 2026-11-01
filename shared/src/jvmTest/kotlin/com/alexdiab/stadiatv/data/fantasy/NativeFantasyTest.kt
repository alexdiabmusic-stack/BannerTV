package com.alexdiab.stadiatv.data.fantasy

import org.junit.Assert.*
import org.junit.Test

class NativeFantasyTest {
    private fun player(id: String, position: String = "QB") = NativeFantasyPlayer(id, "Player $id", "TEAM", position, NativeFantasyEngine.eligible(position, "nfl"))
    private fun league(personal: Boolean = true) = NativeFantasyEngine.create("Test", "My team", "nfl", personal, 4, mapOf("QB" to 1, "BN" to 1, "IR" to 1))
    @Test fun snakeDraftReversesEvenRoundsAndCpuPicksAreExplicit() {
        val league = league(false); val teams = league.teams.map { it.id }
        assertEquals(teams, league.picks.take(4).map { it.team }); assertEquals(teams.asReversed(), league.picks.drop(4).map { it.team })
        assertEquals(1, league.teams.count { !it.cpu })
        val pool = (1..12).map { player(it.toString()) }
        val drafted = NativeFantasyEngine.draft(league, pool[0], pool)
        assertEquals(7, drafted.picks.count { it.player != null }); assertEquals(drafted.userTeam.id, drafted.nextPick?.team)
        val complete = NativeFantasyEngine.draft(drafted, pool.first { p -> drafted.teams.flatMap { it.roster }.none { it.player.id == p.id } }, pool)
        assertNull(complete.nextPick); assertEquals(8, complete.teams.flatMap { it.roster }.size)
    }
    @Test fun duplicateDraftAndFailedAddDropAreAtomic() {
        val first = NativeFantasyEngine.draft(league(), player("1"), listOf(player("1"), player("2")))
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.draft(first, player("1"), listOf(player("1"))) }
        val complete = NativeFantasyEngine.draft(first, player("2"), listOf(player("1"), player("2")))
        val drop = complete.userTeam.roster.first().id
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.add(complete, player("2"), drop) }
        assertEquals(2, complete.userTeam.roster.size)
        val updated = NativeFantasyEngine.add(complete, player("3"), drop)
        assertEquals(setOf("2", "3"), updated.userTeam.roster.map { it.player.id }.toSet())
    }
    @Test fun lineupRejectsIneligibleOverCapacityAndGameStartEdits() {
        var league = NativeFantasyEngine.draft(league(), player("1"), emptyList())
        league = NativeFantasyEngine.draft(league, player("2"), emptyList())
        val first = league.userTeam.roster[0].id; val second = league.userTeam.roster[1].id
        val assigned = NativeFantasyEngine.move(league, "2026-10-22", first, "QB")
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.move(assigned, "2026-10-22", second, "QB") }
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.move(assigned, "2026-10-22", first, "IR") }
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.move(assigned, "2026-10-22", first, "BN", setOf("1")) }
        assertEquals("QB", assigned.lineups["2026-10-22"]?.get(first))
    }
    @Test fun dropsRemoveOnlyThatPlayersAssignments() {
        var league = NativeFantasyEngine.draft(league(), player("1"), emptyList()); league = NativeFantasyEngine.draft(league, player("2"), emptyList())
        val one = league.userTeam.roster[0].id; val two = league.userTeam.roster[1].id
        league = NativeFantasyEngine.move(league, "day", one, "QB"); league = NativeFantasyEngine.move(league, "day", two, "BN")
        val dropped = NativeFantasyEngine.drop(league, one)
        assertEquals(mapOf(two to "BN"), dropped.lineups["day"])
    }
    @Test fun exactIosDefaultWeightsAndMissingFeedAreNotZero() {
        val rules = NativeFantasyEngine.points("nfl")
        val score = NativeFantasyEngine.score(mapOf("passingYards" to 250.0, "passingTouchdowns" to 2.0, "interceptions" to 1.0), rules, "Points", rules.keys)
        assertEquals(16.0, score.points!!, .0001)
        assertNull(NativeFantasyEngine.score(null, rules, "Points", rules.keys).points)
        assertEquals(-1, NativeFantasyEngine.categoryWinner(3.0, 2.0, "earnedRunAverage")); assertEquals(1, NativeFantasyEngine.categoryWinner(3.0, 2.0, "goals"))
    }
    @Test fun rosterAndScoringValidation() {
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.create("", "Team", "nfl", true) }
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.create("League", "Team", "nfl", true, roster = mapOf("QB" to 0)) }
        assertThrows(IllegalArgumentException::class.java) { NativeFantasyEngine.create("League", "Team", "nfl", true, rules = mapOf("passingYards" to Double.NaN)) }
        assertEquals(setOf("SP", "P"), NativeFantasyEngine.eligible("SP", "mlb"))
        assertEquals(setOf("PG", "GARD", "UTIL"), NativeFantasyEngine.eligible("PG", "nba"))
    }
}
