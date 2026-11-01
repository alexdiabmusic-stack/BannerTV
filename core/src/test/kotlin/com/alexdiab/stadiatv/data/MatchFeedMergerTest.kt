package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class MatchFeedMergerTest {
    private val now = 1_800_000_000_000L
    private fun game(id: String = "1", state: GameState = GameState.LIVE, provider: String? = "MLB", time: Long = now, league: String = "baseball/mlb") =
        Match(if (provider == null) id else "${provider.lowercase()}:$id", League.find(league)!!, time, "Away at Home", "A @ H", state, state.label,
            TeamSide("Home", "Home", "WSN", score = "2"), TeamSide("Away", "Away", "NYM", score = "1"),
            nativeReference = provider?.let { NativeGameReference(it, id) })
    @Test fun liveAdvancesToFinalAndAnOlderScheduleCannotRollItBackward() {
        val live = game(); val final = live.copy(state = GameState.FINAL, statusDetail = "Final", broadcasts = emptyList())
        val result = MatchFeedMerger.merge(listOf(live.copy(broadcasts = listOf("Network")), final, live), now).single()
        assertEquals(GameState.FINAL, result.state); assertEquals("2", result.home.score); assertTrue(result.home.isWinner)
        assertEquals(listOf("Network"), result.broadcasts)
    }
    @Test fun scoreCorrectionsReplaceEarlierRichMetadataAndRecomputeTheWinner() {
        val old = game(state = GameState.FINAL).copy(broadcasts = listOf("Network"))
        val corrected = old.copy(home = old.home.copy(score = "0"), away = old.away.copy(score = "3"), broadcasts = emptyList())
        val accepted = MatchFeedMerger.merge(listOf(old, corrected), now).single()
        assertEquals("0", accepted.home.score); assertFalse(accepted.home.isWinner); assertTrue(accepted.away.isWinner)
    }
    @Test fun separatelyIdentifiedSameProviderGamesAndAmbiguousDoubleheadersNeverCollapse() {
        val one = game("1"); val two = game("2")
        assertEquals(2, MatchFeedMerger.merge(listOf(one, two), now).size)
        assertEquals(3, MatchFeedMerger.merge(listOf(one, two, game("espn", provider = null)), now).size)
        assertEquals(2, MatchFeedMerger.merge(listOf(game("e1", provider = null), game("e2", provider = null)), now).size)
    }
    @Test fun uniqueCrossProviderDuplicatesUseScopedAliasesAndNoHalfHourBoundaryGuess() {
        val native = game(time = now - 1)
        val espn = game("espn", provider = null).copy(home = native.home.copy(abbreviation = "WSH"))
        val result = MatchFeedMerger.merge(listOf(espn, native), now).single()
        assertEquals(native.id, result.id); assertEquals("MLB", result.nativeReference!!.provider)
        assertEquals(2, MatchFeedMerger.merge(listOf(native, espn.copy(startMillis = now + 16 * 60_000)), now).size)
    }
    @Test fun aNewerFallbackFinalWinsWithoutBeingMislabelledAsANativeGame() {
        val final = game("espn", GameState.FINAL, null)
        val result = MatchFeedMerger.merge(listOf(game(), final), now).single()
        assertNull(result.nativeReference); assertEquals(final.id, result.id)
    }
    @Test fun unrelatedSportsSessionsUnknownOpponentsAndDifferentLeaguesDoNotFuzzyMerge() {
        val one = game(provider = null, league = "racing/f1")
        assertEquals(2, MatchFeedMerger.merge(listOf(one, one.copy(id = "2", nativeReference = NativeGameReference("F1", "2"))), now).size)
        val unknown = game().copy(away = TeamSide("TBD", "TBD", "TBD"))
        assertEquals(2, MatchFeedMerger.merge(listOf(unknown, unknown.copy(id = "espn", nativeReference = null)), now).size)
        assertEquals(2, MatchFeedMerger.merge(listOf(game(), game(league = "football/nfl", provider = null)), now).size)
    }
    @Test fun knownPostponementReplacesLiveButAPlainPregameRefreshCannot() {
        val live = game()
        val scheduled = live.copy(state = GameState.PRE, statusDetail = "Scheduled", away = live.away.copy(score = null), home = live.home.copy(score = null))
        assertEquals(GameState.LIVE, MatchFeedMerger.merge(listOf(live, scheduled), now).single().state)
        val postponed = scheduled.copy(statusDetail = "Postponed")
        val accepted = MatchFeedMerger.merge(listOf(live, postponed), now).single()
        assertEquals("Postponed", accepted.statusDetail); assertNull(accepted.home.score)
    }
    @Test fun aFinalWithoutReportedTotalsDoesNotPromoteTheLastLiveScoreToAKnownFinal() {
        val live = game()
        val unknownFinal = live.copy(state = GameState.FINAL, away = live.away.copy(score = null), home = live.home.copy(score = null))
        val accepted = MatchFeedMerger.merge(listOf(live, unknownFinal), now).single()
        assertEquals(GameState.FINAL, accepted.state); assertNull(accepted.away.score); assertNull(accepted.home.score)
        assertFalse(accepted.home.isWinner); assertFalse(accepted.away.isWinner)
    }
}
