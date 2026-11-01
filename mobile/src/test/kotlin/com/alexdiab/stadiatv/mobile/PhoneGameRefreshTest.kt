package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhoneGameRefreshTest {
    @Test fun optionalLaLigaSurfaceFailuresDoNotThrottleHealthyScorePolling() {
        val score = GameCentre(providerStamp = ProviderStamp("LaLiga", "102344", 1), soccer = SoccerDetail(errors = mapOf("pressure" to "No samples", "commentary" to "Not published")))
        assertFalse(phoneNativeRefreshFailed(score))
        assertEquals(10_000L, phoneGamePollDelay(game(league = "soccer/esp.1"), 0, if (phoneNativeRefreshFailed(score)) 1 else 0, score))
        assertTrue(phoneNativeRefreshFailed(score.copy(soccer = score.soccer!!.copy(recoverableCoreFailure = true))))
        assertTrue(phoneNativeRefreshFailed(score.copy(providerStamp = ProviderStamp("Premier League", "1", 1))))
    }
    @Test fun finalSoccerCoreFailuresKeepBoundedRecoveryButSupplementalAbsenceCannotPollForever() {
        val match = game(GameState.FINAL, league = "soccer/esp.1")
        fun data(errors: Map<String, String>) = GameCentre(soccer = SoccerDetail(errors = errors, recoverableCoreFailure = errors.keys.any { it in setOf("official", "fotmob", "score") }))
        assertEquals(60_000L, phoneGamePollDelay(match, 0, 1, data(mapOf("official" to "Constructed timeout"))))
        assertEquals(300_000L, phoneGamePollDelay(match, 0, 6, data(mapOf("fotmob" to "Constructed outage"))))
        assertNull(phoneGamePollDelay(match, 0, 0, data(emptyMap())))
        assertNull(phoneGamePollDelay(match, 0, 3, data(mapOf("commentary" to "Not published", "lineups" to "Not published"))))
        assertEquals(60_000L, phoneGamePollDelay(match, 0, 1, null))
        assertNull(phoneGamePollDelay(match, 0, 3, GameCentre(soccer = SoccerDetail(errors = mapOf("fotmob" to "No confirmed historical mapping")))))
    }
    @Test fun nativeSummaryShotProjectionIsNotClearedByTheLegacyEspnEnrichmentPath() {
        val shot = GameShot("nba:game:1", 0f, 1f, true, true, 1, "Reported", "Shot")
        val native = GameCentre(shots = listOf(shot), shotSource = "NBA", providerStamp = ProviderStamp("NBA", "game", 1))
        assertSame(native, phoneRetainSummaryShots(native, GameCentre.EMPTY))
        val espn = GameCentre(headline = "New summary")
        assertEquals(listOf(shot), phoneRetainSummaryShots(espn, native).shots)
        assertEquals("New summary", phoneRetainSummaryShots(espn, native).headline)
    }
    private fun game(state: GameState = GameState.LIVE, status: String = "Live", starts: Long = 0, league: String = "football/nfl") =
        Match("test", League.find(league)!!, starts, "Test", "Test", state, status, TeamSide("Home", "Home", "HOM", null), TeamSide("Away", "Away", "AWY", null))

    @Test fun eplSourceCadenceHandlesHalfTimePregameBoundariesTerminalStatesAndBoundedFailureBackoff() {
        val match = game(league = "soccer/eng.1")
        assertEquals(10_000L, phoneGamePollDelay(match, 0))
        assertEquals(25_000L, phoneGamePollDelay(match.copy(statusDetail = "Half-time"), 0))
        assertEquals(30_000L, phoneGamePollDelay(match.copy(statusDetail = "Suspended"), 0))
        assertEquals(30_000L, phoneGamePollDelay(match.copy(state = GameState.PRE, statusDetail = "Delayed"), 0))
        assertEquals(25_000L, phoneGamePollDelay(match.copy(state = GameState.PRE, startMillis = 900_000), 0))
        assertEquals(60_000L, phoneGamePollDelay(match.copy(state = GameState.PRE, startMillis = 900_001), 0))
        assertEquals(300_000L, phoneGamePollDelay(match.copy(state = GameState.PRE, startMillis = 3_600_001), 0))
        assertEquals(40_000L, phoneGamePollDelay(match, 0, 2)); assertEquals(300_000L, phoneGamePollDelay(match, 0, Int.MAX_VALUE))
        assertNull(phoneGamePollDelay(match.copy(state = GameState.FINAL), 0))
        for (status in listOf("Postponed", "Cancelled", "Abandoned")) assertNull(phoneGamePollDelay(match.copy(state = GameState.PRE, statusDetail = status), 0))
    }

    @Test fun basketballRefreshUsesLateGameQuarterBoundaryHalftimeAndTerminalRules() {
        val normal = game(league = "basketball/nba").copy(live = LiveContext("5:12", "3"))
        val data = GameCentre(basketball = BasketballDetail(null, null, null, null, emptyList(), period = 3))
        assertEquals(12_000L, phoneGamePollDelay(normal, 0, data = data))
        val late = normal.copy(live = LiveContext("2:00", "4"))
        assertEquals(8000L, phoneGamePollDelay(late, 0, data = data.copy(basketball = data.basketball!!.copy(period = 4))))
        assertEquals(8000L, phoneGamePollDelay(late.copy(live = LiveContext("0:29.8", "4")), 0, data = data.copy(basketball = data.basketball!!.copy(period = 4))))
        assertEquals(20_000L, phoneGamePollDelay(late.copy(live = LiveContext("0:00", "4")), 0))
        assertEquals(60_000L, phoneGamePollDelay(normal.copy(statusDetail = "Halftime"), 0))
        assertEquals(30_000L, phoneGamePollDelay(normal.copy(statusDetail = "Suspended"), 0))
        assertEquals(300_000L, phoneGamePollDelay(normal.copy(state = GameState.PRE, startMillis = 3_600_001), 0))
        assertEquals(40_000L, phoneGamePollDelay(normal, 0, 2, data))
        assertNull(phoneGamePollDelay(normal.copy(state = GameState.FINAL), 0))
        assertNull(phoneGamePollDelay(normal.copy(league = League.find("basketball/wnba")!!, statusDetail = "Cancelled"), 0))
    }

    @Test fun basketballCardsSeparateStartersBenchAndDnpWithoutDuplicatingAnUnplayedStarter() {
        fun row(name: String, starter: Boolean = false, dnp: Boolean = false) = BoxRow(name, "", emptyList(), starter = starter, didNotPlay = dnp)
        val groups = basketballBoxSections(listOf(row("bench"), row("starter", true), row("unplayed starter", true, true), row("unplayed", dnp = true)))
        assertEquals(listOf("Starters", "Bench", "Did not play"), groups.map { it.first })
        assertEquals(listOf("starter", "bench", "unplayed starter", "unplayed"), groups.flatMap { it.second }.map { it.name })
        assertTrue(basketballBoxSections(emptyList()).isEmpty())
    }

    @Test fun nflRefreshUsesSourceStatusIntervalsAndStopsAfterFinalReconciliation() {
        assertEquals(6000L, phoneGamePollDelay(game(), 0))
        assertEquals(25_000L, phoneGamePollDelay(game(status = "Halftime"), 0))
        assertEquals(45_000L, phoneGamePollDelay(game(status = "Suspended"), 0))
        assertEquals(45_000L, phoneGamePollDelay(game(status = "Delayed"), 0))
        assertEquals(180_000L, phoneGamePollDelay(game(GameState.PRE, "Scheduled", 1_800_001), 0))
        assertEquals(45_000L, phoneGamePollDelay(game(GameState.PRE, "Pregame", 1_800_000), 0))
        assertNull(phoneGamePollDelay(game(GameState.FINAL, "Final"), 0))
        assertNull(phoneGamePollDelay(game(GameState.PRE, "Cancelled"), 0))
        assertNull(phoneGamePollDelay(game(GameState.PRE, "Postponed"), 0))
        assertEquals(15_000L, phoneGamePollDelay(game(league = "hockey/nhl"), 0))
    }
    @Test fun nflFailureBackoffUsesTheSourceFloorAndCapWithoutOverflow() {
        assertEquals(20_000L, phoneGamePollDelay(game(), 0, 1))
        assertEquals(40_000L, phoneGamePollDelay(game(), 0, 2))
        assertEquals(300_000L, phoneGamePollDelay(game(), 0, Int.MAX_VALUE))
        assertEquals(180_000L, phoneGamePollDelay(game(GameState.PRE, "Scheduled", 3_600_000), 0, 2))
    }
    @Test fun everySnapshotMutationIsBoundedIncludingNativeAndLateResponses() {
        val store = PhoneGameSnapshotStore(3) { setOf("visible", "playing") }
        store.update { it + ("visible" to PhoneGameSnapshot()) + ("playing" to PhoneGameSnapshot()) }
        repeat(30) { number -> store.update { it + ("native-$number" to PhoneGameSnapshot(nativeAttemptAt = number.toLong())) }; assertTrue(store.value.size <= 3) }
        assertEquals(setOf("visible", "playing", "native-29"), store.value.keys)
        store.update { it + ("late-evicted-game" to PhoneGameSnapshot()) }
        assertEquals(setOf("visible", "playing", "late-evicted-game"), store.value.keys)
    }
    @Test fun touchedGamesAreRetainedBeforeOlderUnprotectedSnapshots() {
        val store = PhoneGameSnapshotStore(2)
        store.update { mapOf("a" to PhoneGameSnapshot(), "b" to PhoneGameSnapshot()) }; store.touch("a")
        store.update { it + ("c" to PhoneGameSnapshot()) }
        assertEquals(setOf("a", "c"), store.value.keys)
        store.touch("not-present"); assertEquals(2, store.value.size)
    }
    @Test fun networkReconnectionRequiresValidationAndIgnoresTheObsoleteDefaultNetwork() {
        val tracker = PhoneDefaultNetwork<String>()
        assertFalse(tracker.state.known)
        assertFalse(tracker.available("wifi").online)
        assertFalse(tracker.capabilities("wifi", true, false).online)
        val online = tracker.capabilities("wifi", true, true)
        assertTrue(online.online); assertEquals(1L, online.generation)
        assertEquals(online, tracker.capabilities("wifi", true, true))
        tracker.available("mobile"); val replacement = tracker.capabilities("mobile", true, true)
        assertEquals(2L, replacement.generation)
        assertEquals(replacement, tracker.lost("wifi"))
        assertEquals(replacement, tracker.capabilities("wifi", true, false))
        assertFalse(tracker.lost("mobile").online)
        tracker.available("wifi"); assertEquals(3L, tracker.capabilities("wifi", true, true).generation)
    }
    @Test fun footballBoxSummaryPreservesMissingStatsAndDoesNotInventUnreportedColumns() {
        assertEquals("20/30 C/ATT · – YDS · 2 TD · 1 INT", footballPlayerStatLine(listOf("C/ATT", "YDS", "TD", "INT"), listOf("20/30", "–", "2", "1")))
        assertEquals("6 REC", footballPlayerStatLine(listOf("REC", "YDS"), listOf("6")))
    }
    @Test fun foregroundConnectionRecoveryRunsOncePerValidatedTransitionNotPerFollowChange() {
        val gate = PhoneConnectionRecovery()
        assertFalse(gate.accept(PhoneConnection()))
        val online = PhoneConnection(true, true, 1)
        assertTrue(gate.accept(online)); assertFalse(gate.accept(online))
        assertFalse(gate.accept(PhoneConnection(true, false, 1)))
        assertTrue(gate.accept(PhoneConnection(true, true, 2)))
        assertFalse(gate.accept(PhoneConnection())) // Lifecycle unsubscription resets replay to unknown.
        assertTrue(gate.accept(online))
    }
    @Test fun historicalNflCalendarUsesAcceptedSeasonStageAndWeekWithoutCurrentDateGuessing() {
        val match = game().copy(nativeReference = NativeGameReference("NFL", "native-game", 2025, "POST", 3))
        val week = phoneNflWeek(match)!!
        assertEquals(2025, week.season); assertEquals(3, week.number)
        assertEquals("2025 · Postseason · Week 3", phoneNflWeekLabel(week))
        assertNull(phoneNflWeek(match.copy(nativeReference = null)))
        assertNull(phoneNflWeek(match.copy(nativeReference = NativeGameReference("NFL", "native-game", 2025, "REG", 0))))
        assertNull(phoneNflWeek(match.copy(league = League.find("basketball/nba")!!)))
    }
    @Test fun delayedNflBadgeUsesOriginalAcceptedSummaryTimeNotTheLatestRetry() {
        val data = GameCentre(providerStamp = ProviderStamp("NFL", "test", 1000), football = FootballDetail(null, 2025, "REG", 1, summaryUpdatedAt = 1000))
        assertFalse(phoneNflLiveDataDelayed(game(), data, 31_000))
        assertTrue(phoneNflLiveDataDelayed(game(), data, 31_001))
        assertFalse(phoneNflLiveDataDelayed(game(GameState.FINAL, "Final"), data, 100_000))
        assertFalse(phoneNflLiveDataDelayed(game(), data.copy(providerStamp = ProviderStamp("ESPN", "test", 1000)), 100_000))
        assertFalse(phoneNflLiveDataDelayed(game(), data.copy(football = data.football!!.copy(summaryUpdatedAt = 0)), 100_000))
    }
}
