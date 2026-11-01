package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class NativeFantasyPoolTest {
    private fun league(sport: String = "nhl") = NativeFantasyEngine.create("Constructed league", "Test team", sport, true, roster = mapOf("BN" to 1))
    private fun player(id: String) = NativeFantasyPlayer(id, "Test player", "ANA", "LW", setOf("LW", "UTIL"))
    private fun team(id: String, name: String = id) = Team(id, name, name, name, null, League.find("hockey/nhl")!!)
    private val key = NativeFantasyPoolKey("nhl", NativeFantasyPoolSource.Native)
    @Test fun emptyLeaguesUseIosProviderChoiceButExistingAndDroppedDraftHistoryKeepTheirOriginalNamespace() {
        for (sport in listOf("nhl", "mlb", "nba")) assertEquals(NativeFantasyPoolSource.Native, NativeFantasyPoolPolicy.key(league(sport)).source)
        assertEquals(NativeFantasyPoolSource.ESPN, NativeFantasyPoolPolicy.key(league("nfl")).source)
        val legacy = player("espn-nhl-123")
        val drafted = NativeFantasyEngine.draft(league(), legacy, listOf(legacy))
        assertEquals(NativeFantasyPoolSource.ESPN, NativeFantasyPoolPolicy.key(drafted).source)
        val dropped = NativeFantasyEngine.drop(drafted, drafted.userTeam.roster.single().id)
        assertTrue(dropped.userTeam.roster.isEmpty()); assertEquals(NativeFantasyPoolSource.ESPN, NativeFantasyPoolPolicy.key(dropped).source)
    }
    @Test fun nativeNamespaceAndProfileIdentityStayQualifiedWhileLegacyProfilesKeepRealEspnNumbers() {
        val native = player("nhl:123")
        val drafted = NativeFantasyEngine.draft(league(), native, listOf(native))
        assertEquals(NativeFantasyPoolSource.Native, NativeFantasyPoolPolicy.key(drafted).source)
        assertEquals("nhl:123", NativeFantasyPoolPolicy.athlete(native, "nhl")!!.id)
        assertEquals("123", NativeFantasyPoolPolicy.athlete(player("espn-nhl-123"), "nhl")!!.id)
        assertNull(NativeFantasyPoolPolicy.athlete(native, "mlb")); assertNull(NativeFantasyPoolPolicy.athlete(player("123"), "nhl"))
        assertNull(NativeFantasyPoolPolicy.playerId(key, "123")); assertNull(NativeFantasyPoolPolicy.playerId(key, "nba:123"))
    }
    @Test fun mixedOrUnknownSavedIdentitiesAreRejectedRatherThanRenumberedOrSilentlyChangingProviders() {
        val base = league()
        val mixed = base.copy(teams = listOf(base.userTeam.copy(roster = listOf(NativeFantasyEntry("a", player("nhl:1"), 1), NativeFantasyEntry("b", player("espn-nhl-2"), 1)))))
        assertTrue(runCatching { NativeFantasyPoolPolicy.key(mixed) }.isFailure)
        val unknown = base.copy(picks = base.picks.map { it.copy(player = "unqualified-123") })
        assertTrue(runCatching { NativeFantasyPoolPolicy.key(unknown) }.isFailure)
        assertTrue(base.userTeam.roster.isEmpty())
    }
    @Test fun nativePositionCodesAndExplicitCombinationRolesDoNotBecomeBenchOnlyOrGuessedSpecialistRoles() {
        assertEquals(setOf("LW", "F", "UTIL"), NativeFantasyEngine.eligible("L", "nhl"))
        assertEquals(setOf("RW", "F", "UTIL"), NativeFantasyEngine.eligible("R", "nhl"))
        assertEquals(setOf("GARD", "F", "UTIL"), NativeFantasyEngine.eligible("G-F", "nba"))
        assertEquals(setOf("F", "C", "UTIL"), NativeFantasyEngine.eligible("F-C", "nba"))
        assertFalse("PG" in NativeFantasyEngine.eligible("G", "nba"))
        assertEquals(setOf("UTIL"), NativeFantasyEngine.eligible("DH", "mlb"))
        assertEquals(setOf("C", "UTIL"), NativeFantasyEngine.eligible("C", "mlb"))
        assertEquals(setOf("P"), NativeFantasyEngine.eligible("P", "mlb"))
        assertEquals(setOf("BN"), NativeFantasyEngine.eligible("New role", "nba"))
    }
    @Test fun roleNormalizationIsIndependentOfDeviceLocaleAndWhitespace() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(setOf("UTIL"), NativeFantasyEngine.eligible(" util ", "mlb"))
            assertEquals(setOf("LW", "F", "UTIL"), NativeFantasyEngine.eligible(" l ", "nhl"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun customHockeyForwardSlotIsAvailableWithoutChangingStandardRosterCounts() {
        assertFalse(NativeFantasyEngine.slots("nhl").containsKey("F"))
        assertEquals(0, NativeFantasyEngine.configurableSlots("nhl")["F"])
        val league = NativeFantasyEngine.create("Forward league", "Test", "nhl", true, roster = mapOf("F" to 1))
        val p = player("nhl:123").copy(eligible = NativeFantasyEngine.eligible("L", "nhl"))
        val drafted = NativeFantasyEngine.draft(league, p, listOf(p))
        val moved = NativeFantasyEngine.move(drafted, "2026-10-24", drafted.userTeam.roster.single().id, "F")
        assertEquals("F", moved.lineups.getValue("2026-10-24").values.single())
    }
    @Test fun adapterUsesTwoRosterRequestsAtMostAndPreservesImagesAndKnownInjuryWithoutInventingPoints() = runTest {
        val loader = NativeFantasyPoolLoader(); var active = 0; var peak = 0
        val teams = (1..5).map { team("nhl:$it", "Team $it") }
        val result = loader.load(key, { teams }) { team ->
            active++; peak = maxOf(active, peak); delay(100); active--
            listOf(RosterGroup("Forwards", listOf(Athlete("nhl:${team.id.substringAfter(':')}", "Player ${team.name}", position = "L", headshotUrl = "https://images.example/player.png", injured = team.id == "nhl:1"))))
        }
        assertEquals(2, peak); assertEquals(5, result.players.size); assertTrue(result.complete)
        assertTrue(result.players.all { it.id.startsWith("nhl:") && it.priorPoints == null && it.image != null })
        assertEquals(1, result.players.count { it.injury != null })
    }
    @Test fun unavailableTeamsAndAmbiguousCurrentClubMembershipAreExplicitAndCannotProduceAWrongTeamPick() = runTest {
        val clubs = listOf(team("nhl:1", "ANA"), team("nhl:2", "PHI"), team("nhl:3", "EDM"))
        val result = NativeFantasyPoolLoader().load(key, { clubs }) { club ->
            if (club.id == "nhl:3") throw IOException("Unavailable")
            listOf(RosterGroup("Players", listOf(Athlete("nhl:99", "Conflicting player", position = "C"), Athlete("nhl:${club.id.substringAfter(':')}", "Known ${club.name}", position = "L"))))
        }
        assertFalse(result.complete); assertEquals(listOf("EDM"), result.unavailableTeams)
        assertEquals(1, result.ambiguousPlayers); assertFalse(result.players.any { it.id == "nhl:99" })
        assertEquals(2, result.players.size)
    }
    @Test fun partialRefreshCannotReplaceACompletePoolOrRedateItsOriginalSuccessfulLoad() = runTest {
        var clock = 1000L; var fail = false
        val loader = NativeFantasyPoolLoader { clock }
        val clubs = listOf(team("nhl:1", "ANA"), team("nhl:2", "PHI"))
        suspend fun roster(club: Team): List<RosterGroup> {
            if (fail && club.id == "nhl:2") throw IOException("Unavailable")
            return listOf(RosterGroup("Players", listOf(Athlete("nhl:${club.id.substringAfter(':')}", "Known ${club.name}", position = "C"))))
        }
        val full = loader.load(key, { clubs }, ::roster)
        fail = true; clock = 2000
        val retained = loader.load(key, { clubs }, ::roster)
        assertTrue(retained.retainedPrevious); assertFalse(retained.complete)
        assertEquals(full.players, retained.players); assertEquals(1000L, retained.loadedAt)
        assertEquals(listOf("PHI"), retained.unavailableTeams)
    }
    @Test fun foreignProviderNumbersAreNotAcceptedAsNativeIdsAndTotalFailureIsNotAnEmptySuccess() = runTest {
        val failure = runCatching { NativeFantasyPoolLoader().load(key, { listOf(team("nhl:1")) }) {
            listOf(RosterGroup("Wrong namespace", listOf(Athlete("123", "Player", position = "C"))))
        } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(runCatching { NativeFantasyPoolLoader().load(key, { listOf(team("123")) }) { emptyList() } }.isFailure)
    }
    @Test fun callerCancellationStopsRosterWorkRatherThanReturningAnIncompleteSuccess() = runTest {
        val error = runCatching { withTimeout(50) { NativeFantasyPoolLoader().load(key, { listOf(team("nhl:1")) }) { delay(1000); emptyList() } } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
    }
    @Test fun duplicateTeamAndOversizedDirectoriesAreRejectedBeforeMakingRosterCalls() = runTest {
        var calls = 0; val loader = NativeFantasyPoolLoader()
        assertTrue(runCatching { loader.load(key, { listOf(team("nhl:1"), team("nhl:1")) }) { calls++; emptyList() } }.isFailure)
        assertTrue(runCatching { loader.load(key, { (1..65).map { team("nhl:$it") } }) { calls++; emptyList() } }.isFailure)
        assertEquals(0, calls)
    }
    @Test fun legacyAdapterKeepsEspnNamespaceAndProfileRouteWithoutInventingPriorSeasonPoints() = runTest {
        val legacy = NativeFantasyPoolKey("nhl", NativeFantasyPoolSource.ESPN)
        val result = NativeFantasyPoolLoader().load(legacy, { listOf(team("1", "ANA")) }) {
            listOf(RosterGroup("Players", listOf(Athlete("123", "Known player", position = "LW", injured = true))))
        }
        val player = result.players.single()
        assertEquals("espn-nhl-123", player.id); assertNull(player.priorPoints)
        assertEquals("123", NativeFantasyPoolPolicy.athlete(player, "nhl")!!.id)
        assertTrue(NativeFantasyPoolPolicy.athlete(player, "nhl")!!.injured)
    }
    @Test fun emptyOrUnidentifiedRostersAreUnavailableNotACompleteZeroPlayerPool() = runTest {
        val clubs = listOf(team("nhl:1", "ANA"), team("nhl:2", "PHI"))
        val partial = NativeFantasyPoolLoader().load(key, { clubs }) { club ->
            if (club.id == "nhl:1") listOf(RosterGroup("Players", listOf(Athlete("nhl:123", "Known", position = "C")))) else emptyList()
        }
        assertEquals(1, partial.players.size); assertEquals(listOf("PHI"), partial.unavailableTeams); assertFalse(partial.complete)
        assertTrue(runCatching { NativeFantasyPoolLoader().load(key, { clubs }) { listOf(RosterGroup("Players", listOf(Athlete("nhl:123", "   ")))) } }.isFailure)
    }
    @Test fun sharedAbbreviationCannotHideContradictoryOwningTeamIds() = runTest {
        val clubs = listOf(team("nhl:1", "SAME"), team("nhl:2", "SAME"))
        val result = NativeFantasyPoolLoader().load(key, { clubs }) { listOf(RosterGroup("Players", listOf(Athlete("nhl:123", "Same player", position = "C")))) }
        assertEquals(1, result.ambiguousPlayers); assertTrue(result.players.isEmpty()); assertFalse(result.complete)
    }
    @Test fun retainedPoolCannotReintroduceAPlayerWhoseNewClubAssignmentIsConflicting() = runTest {
        val loader = NativeFantasyPoolLoader { 100L }; var conflict = false
        val clubs = listOf(team("nhl:1", "ANA"), team("nhl:2", "PHI"))
        suspend fun roster(club: Team): List<RosterGroup> {
            val id = if (conflict) "nhl:1" else club.id
            return listOf(RosterGroup("Players", listOf(Athlete(id, "Known player", position = "C"))))
        }
        assertEquals(2, loader.load(key, { clubs }, ::roster).players.size)
        conflict = true
        val retained = loader.load(key, { clubs }, ::roster)
        assertTrue(retained.retainedPrevious); assertEquals(1, retained.ambiguousPlayers)
        assertEquals(listOf("nhl:2"), retained.players.map { it.id }); assertEquals(100L, retained.loadedAt)
    }
}
