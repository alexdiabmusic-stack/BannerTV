package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Finished recordings are real; all live, outage, correction and clock cases below are constructed. */
class NativeLaLigaAuthorityTest {
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun game() = NativeLaLigaMapping.match(fixture("official-matches-finished")["matches"].array.first(), 2026, "verified-2026")!!
    private val now get() = game().startMillis + 3_600_000
    private fun match(state: GameState, score: String = "1", label: String = if (state == GameState.PRE) "Upcoming" else "Live") = game().copy(state = state, statusDetail = label,
        home = game().home.copy(score = score.takeIf { state != GameState.PRE }, isWinner = false), away = game().away.copy(score = "0".takeIf { state != GameState.PRE }, isWinner = false))
    private fun sample(game: Match, at: Long = now) = NativeLaLigaAuthority.Sample(game, at)
    private fun liveState() = NativeLaLigaAuthority.apply(game(), null, sample(match(GameState.LIVE, "0"), now - 5000), sample(match(GameState.LIVE), now - 1000), now)!!
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    @Test fun officialPregameBeforeKickoffOwnsTheScheduleAndDoesNotAcceptAnEarlyLiveScore() {
        val at = game().startMillis - 1000
        val result = NativeLaLigaAuthority.apply(game(), null, sample(match(GameState.PRE), at), sample(match(GameState.LIVE), at), at)!!
        assertEquals(NativeLaLigaAuthority.Owner.Official, result.owner); assertNull(result.accepted.match.home.score)
    }
    @Test fun verifiedStartedSourceCanLeadAfterKickoffEvenWhileOfficialPregameStatusLags() {
        val result = NativeLaLigaAuthority.apply(game(), null, sample(match(GameState.PRE)), sample(match(GameState.LIVE)), now)!!
        assertEquals(NativeLaLigaAuthority.Owner.FotMob, result.owner); assertEquals(GameState.LIVE, result.accepted.match.state)
    }
    @Test fun eitherSourceOutageRetainsTheOtherAndNeverRedatesAnOldScore() {
        val state = liveState()
        val failure = NativeLaLigaAuthority.apply(game(), state, official = sample(match(GameState.LIVE, "0"), now + 1000), now = now + 1000)!!
        assertEquals(state.accepted, failure.accepted); assertEquals(now - 1000, failure.accepted.checkedAt)
        val recovered = NativeLaLigaAuthority.apply(game(), failure, fotmob = sample(match(GameState.LIVE, "2"), now + 2000), now = now + 2000)!!
        assertEquals("2", recovered.accepted.match.home.score); assertEquals(now + 2000, recovered.accepted.checkedAt)
        for (label in listOf("Postponed", "Cancelled", "Abandoned", "Delayed", "Suspended")) {
            val interrupted = NativeLaLigaAuthority.apply(game(), state, official = sample(match(if (label == "Suspended") GameState.LIVE else GameState.PRE, label = label)), now = now)!!
            assertEquals(label, interrupted.accepted.match.statusDetail); assertEquals(NativeLaLigaAuthority.Owner.Official, interrupted.owner)
        }
    }
    @Test fun officialFinalAndLaterOfficialCorrectionAlwaysWinOverConflictingLiveDetail() {
        val first = NativeLaLigaAuthority.apply(game(), liveState(), sample(game(), now + 1000), sample(match(GameState.LIVE, "9"), now + 2000), now + 2000)!!
        assertEquals(NativeLaLigaAuthority.Owner.Official, first.owner); assertEquals("3", first.accepted.match.home.score)
        val corrected = NativeLaLigaAuthority.apply(game(), first, sample(game().copy(home = game().home.copy(score = "2")), now + 3000), now = now + 3000)!!
        assertEquals("2", corrected.accepted.match.home.score)
    }
    @Test fun finalCannotRegressToLiveAndFotmobFinalCorrectionsRemainPossibleUntilOfficialFinal() {
        val final = NativeLaLigaAuthority.apply(game(), liveState(), fotmob = sample(game(), now + 1000), now = now + 1000)!!
        val lateLive = NativeLaLigaAuthority.apply(game(), final, fotmob = sample(match(GameState.LIVE, "0"), now + 2000), now = now + 2000)!!
        assertEquals(final.accepted, lateLive.accepted)
        val corrected = NativeLaLigaAuthority.apply(game(), lateLive, fotmob = sample(game().copy(home = game().home.copy(score = "2")), now + 3000), now = now + 3000)!!
        assertEquals("2", corrected.accepted.match.home.score); assertEquals(GameState.FINAL, corrected.accepted.match.state)
    }
    @Test fun staleForeignFutureAndWrongSeasonSamplesCannotChangeAnAcceptedGame() {
        val state = liveState()
        val stale = NativeLaLigaAuthority.apply(game(), state, fotmob = sample(match(GameState.LIVE, "9"), now - 2000), now = now)!!
        assertEquals(state.accepted, stale.accepted)
        for (bad in listOf(match(GameState.LIVE).copy(home = game().away), match(GameState.LIVE).copy(nativeReference = game().nativeReference!!.copy(season = 2025))))
            assertTrue(runCatching { NativeLaLigaAuthority.apply(game(), state, fotmob = sample(bad), now = now) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeLaLigaAuthority.apply(game(), state, fotmob = sample(match(GameState.LIVE), now + 300_001), now = now) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeLaLigaAuthority.apply(game().copy(id = "102249"), null, official = sample(game()), now = now) }.exceptionOrNull() is IOException)
    }
    @Test fun realFinalMappingKeepsOfficialIdentityAndNeverRetainsAStaleLiveClock() {
        val raw = fixture("fotmob-matchdetails-finished"); val mapping = NativeLaLigaReconciliation.resolve(game(), listOf(fixture("fotmob-day-matches")))!!
        val result = NativeLaLigaAuthority.fotmob(raw, game(), mapping, now)
        assertEquals(game().nativeReference, result.match.nativeReference); assertEquals("laliga:21", result.match.home.teamId)
        assertEquals("3–0", result.match.displayScore); assertEquals(LiveContext.EMPTY, result.match.live)
        val missing = replace(raw, "header", replace(raw["header"], "teams", JsonArray(raw["header"]["teams"].array.map { JsonObject(it.jsonObject - "score") })))
        val unknown = NativeLaLigaAuthority.fotmob(missing, game(), mapping, now).match
        assertNull(unknown.home.score); assertNull(unknown.away.score); assertFalse(unknown.home.isWinner)
    }
    @Test fun constructedLiveClockUsesOnlyReportedFieldsAndUnknownStartedPhaseIsNotFirstHalf() {
        val raw = fixture("fotmob-matchdetails-finished"); val mapping = NativeLaLigaReconciliation.resolve(game(), listOf(fixture("fotmob-day-matches")))!!
        var status: JsonElement = buildJsonObject { put("utcTime", raw["header"]["status"]["utcTime"]!!); put("started", true); put("finished", false) }
        fun mapped() = NativeLaLigaAuthority.fotmob(replace(raw, "header", replace(raw["header"], "status", status)), game(), mapping, now).match
        assertEquals("Live", mapped().statusDetail); assertNull(mapped().live.clock); assertNull(mapped().live.period)
        status = replace(status, "liveTime", buildJsonObject { put("minute", 90); put("added", 4) })
        assertEquals("90+4′", mapped().live.clock)
        status = replace(status, "reason", buildJsonObject { put("shortKey", "halftime_short") })
        assertEquals("Half-time", mapped().statusDetail); assertNull(mapped().live.clock)
        status = replace(replace(status, "started", JsonPrimitive(false)), "reason", buildJsonObject { put("shortKey", "NewProviderState") })
        assertEquals(GameState.PRE, mapped().state); assertEquals("NewProviderState", mapped().statusDetail); assertNull(mapped().live.clock)
    }
    @Test fun actualRescheduleInvalidatesAnOldLiveKickoffButMissingFeedsDoNotChangeFinals() {
        val state = liveState(); val future = match(GameState.PRE).copy(startMillis = game().startMillis + 86_400_000)
        val result = NativeLaLigaAuthority.apply(game(), state, official = sample(future), now = now)!!
        assertEquals(GameState.PRE, result.accepted.match.state); assertNull(result.fotmob)
        val final = NativeLaLigaAuthority.apply(game(), null, official = sample(game()), now = now)!!
        assertEquals(final.accepted, NativeLaLigaAuthority.apply(game(), final, official = sample(match(GameState.LIVE), now + 1000), now = now + 1000)!!.accepted)
    }
}
