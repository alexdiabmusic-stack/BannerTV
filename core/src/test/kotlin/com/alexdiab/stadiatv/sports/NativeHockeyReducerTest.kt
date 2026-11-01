package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

/** Constructed competing-resource snapshots; not representations of actual live provider traffic. */
class NativeHockeyReducerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val gameId = "2023020204"
    private val match = Match("nhl:$gameId", League.find("hockey/nhl")!!, 1700000000000, "Minnesota at Buffalo", "MIN @ BUF", GameState.LIVE, "",
        TeamSide("Buffalo Sabres", "Sabres", "BUF", teamId = "nhl:7"), TeamSide("Minnesota Wild", "Wild", "MIN", teamId = "nhl:30"), nativeReference = NativeGameReference("NHL", gameId))
    private fun raw(state: String = "LIVE", period: Int = 2, clock: String = "05:00", seq: Int = 10, id: String = gameId): JsonElement = parseJson("""{
        "id":$id,"gameState":"$state","awayTeam":{"id":30,"abbrev":"MIN","score":2},"homeTeam":{"id":7,"abbrev":"BUF","score":1},
        "periodDescriptor":{"number":$period,"periodType":"REG"},"clock":{"timeRemaining":"$clock"},
        "plays":[{"eventId":$seq,"sortOrder":$seq,"typeDescKey":"goal","periodDescriptor":{"number":1,"periodType":"REG"},"details":{"scoringPlayerId":1,"eventOwnerTeamId":30}}],
        "rosterSpots":[{"playerId":1,"firstName":{"default":"Ada"},"lastName":{"default":"One"}}],
        "summary":{"scoring":[],"gameRecap":{"url":"https://www.nhl.com/video/recap"}},
        "playerByGameStats":{"awayTeam":{"forwards":[{"playerId":1,"name":{"default":"Ada One"},"goals":2,"assists":1,"points":3,"sog":4}]},"homeTeam":{}}
    }""")
    private fun old() = NativeHockey.update(null, raw(), raw(), raw(), null, match, 1000)!!

    @Test fun oldPbpCannotVetoFinalLandingOrBoxAndEachSectionRetainsItsOriginalTime() {
        val result = NativeHockey.update(old(), raw("FINAL", 3), raw("FINAL", 3), raw(period = 1), null, match, 2000)!!
        assertEquals(GameState.FINAL, result.liveMatch!!.state); assertTrue(result.providerStamp!!.final)
        assertEquals("nhl:$gameId:10", result.plays.single().id); assertEquals(1, result.hockey!!.players.size)
        assertEquals(2000L, result.hockey!!.surfaceUpdatedAt["landing"]); assertEquals(2000L, result.hockey!!.surfaceUpdatedAt["boxscore"])
        assertEquals(1000L, result.hockey!!.surfaceUpdatedAt["play-by-play"]); assertTrue(result.hockey!!.errors.containsKey("play-by-play"))
    }

    @Test fun newerBoxAndPbpApplyEvenWhenLandingRegressesAndWholeFailedRefreshDoesNotBecomeFresh() {
        val result = NativeHockey.update(old(), raw(period = 1), raw(clock = "04:00"), raw(clock = "03:00", seq = 11), null, match, 2000)!!
        assertEquals("03:00", result.liveMatch!!.live.clock); assertEquals(11, result.providerStamp!!.sequence)
        assertEquals(1000L, result.hockey!!.surfaceUpdatedAt["landing"]); assertEquals(2000L, result.hockey!!.surfaceUpdatedAt["boxscore"])
        val failed = NativeHockey.update(result, raw(period = 1), null, null, null, match, 3000, mapOf("boxscore" to "Unavailable"))!!
        assertEquals(2000, failed.providerStamp!!.requestedAt); assertEquals(result.plays, failed.plays)
        assertEquals(setOf("boxscore", "landing"), failed.hockey!!.errors.keys)
    }

    @Test fun truncatedEventsCannotReplaceCompleteStreamButSameSequenceCorrectionsCan() {
        val result = NativeHockey.update(old(), null, null, raw(clock = "04:00", seq = 9), null, match, 2000)!!
        assertEquals(10, result.providerStamp!!.sequence); assertEquals(1000L, result.hockey!!.surfaceUpdatedAt["play-by-play"])
        val corrected = JsonObject(raw(clock = "03:00").jsonObject + ("plays" to JsonArray(listOf(parseJson("""{"eventId":10,"sortOrder":10,"typeDescKey":"stoppage","details":{"reason":"video-review"}}""")))))
        val applied = NativeHockey.update(result, null, null, corrected, null, match, 3000)!!
        assertFalse(applied.plays.single().isScoring); assertEquals("Stoppage", applied.plays.single().hockeyEvent!!.title)
    }

    @Test fun missingLandingRetainsRichGoalAnnotationsButAuthoritativeLandingClearsRecap() {
        val goal = parseJson("""[{"periodDescriptor":{"number":1,"periodType":"REG"},"goals":[{"eventId":10,"playerId":1,"name":{"default":"Ada One"},"goalsToDate":12,"strength":"pp","highlightClipSharingUrl":"https://www.nhl.com/video/goal"}]}]""")
        val landing = JsonObject(raw().jsonObject + ("summary" to buildJsonObject { put("scoring", goal); put("gameRecap", buildJsonObject { put("url", "https://www.nhl.com/video/recap") }) }))
        val before = NativeHockey.update(null, landing, raw(), raw(), null, match, 1000)!!
        val after = NativeHockey.update(before, null, null, raw(clock = "04:00"), null, match, 2000)!!
        assertEquals(12, after.plays.single().hockeyEvent!!.seasonGoals); assertEquals(before.plays.single().videoUrl, after.plays.single().videoUrl)
        val noRecap = JsonObject(raw(clock = "03:00").jsonObject - "summary")
        assertNull(NativeHockey.update(after, noRecap, null, null, null, match, 3000)!!.hockey!!.recapUrl)
    }

    @Test fun mismatchedIdsUnknownStatesAndOlderRequestCannotMutateSnapshot() {
        assertNull(NativeHockey.update(null, raw(id = "2023020205"), null, null, null, match, 1000))
        assertNull(NativeHockey.update(null, raw(state = "unknown"), null, null, null, match, 1000))
        assertEquals(old(), NativeHockey.update(old(), raw("FINAL", 3), null, null, null, match, 999))
    }

    @Test fun diskRestoresAcceptedSurfacesInTimeOrderAndLabelsThemSavedNotNewlyChecked(): Unit = runBlocking {
        val cache = NativeHockeySnapshotCache(temporary.newFolder(), { 3000 })
        val before = old()
        cache.save(gameId, 1000, mapOf("landing" to raw(), "boxscore" to raw(), "play-by-play" to raw()), before)
        val after = NativeHockey.update(before, raw("FINAL", 3), null, raw(period = 1), null, match, 2000)!!
        cache.save(gameId, 2000, mapOf("landing" to raw("FINAL", 3), "play-by-play" to raw(period = 1)), after)
        val restored = cache.load(gameId, match)!!
        assertTrue(restored.hockey!!.savedSnapshot); assertEquals(GameState.FINAL, restored.liveMatch!!.state)
        assertEquals(2000L, restored.providerStamp!!.requestedAt); assertEquals(1000L, restored.hockey!!.surfaceUpdatedAt["play-by-play"])
        assertEquals(before.plays.single().id, restored.plays.single().id); assertEquals(1, restored.hockey!!.players.size)
    }

    @Test fun corruptFutureMismatchedAndTraversalCacheInputsAreIgnored(): Unit = runBlocking {
        val directory = temporary.newFolder()
        val cache = NativeHockeySnapshotCache(directory, { 3000 })
        assertNull(cache.load("../outside", match))
        File(directory, "$gameId.json").writeText("not-json")
        assertNull(cache.load(gameId, match))
        File(directory, "$gameId.json").writeText("""{"version":1,"gameId":"$gameId","surfaces":{"landing":{"at":1000,"payload":${raw(id = "2023020205")}}}}""")
        assertNull(cache.load(gameId, match))
        val at = 999_999L
        File(directory, "$gameId.json").writeText("""{"version":1,"gameId":"$gameId","surfaces":{"landing":{"at":$at,"payload":${raw()}}}}""")
        assertNull(cache.load(gameId, match))
    }

    @Test fun diskStorageIsBoundedToTwelveGames(): Unit = runBlocking {
        val directory = temporary.newFolder()
        val cache = NativeHockeySnapshotCache(directory, { 3000 })
        repeat(15) { index ->
            val id = (2023020204L + index).toString(); val game = match.copy(id = "nhl:$id", nativeReference = NativeGameReference("NHL", id))
            val payload = raw(id = id); val result = NativeHockey.update(null, payload, null, null, null, game, 1000)!!
            cache.save(id, 1000, mapOf("landing" to payload), result)
        }
        assertEquals(12, directory.listFiles()!!.size); assertTrue(directory.listFiles()!!.all { it.extension == "json" })
    }
    @Test fun nativeColdLinkCanResolveItsSavedGameDuringProviderFailure(): Unit = runBlocking {
        val payload = parseJson(javaClass.getResource("/parity/nhl-final-landing.recorded.json")!!.readText())
        val game = NativeHockeyProvider.game(payload)!!
        val cache = NativeHockeySnapshotCache(temporary.newFolder(), { 3000 })
        val result = NativeHockey.update(null, payload, null, null, null, game, 1000)!!
        cache.save(gameId, 1000, mapOf("landing" to payload), result)
        assertEquals(game.id, cache.match(gameId)!!.id)
        assertNull(cache.match("2023020205"))
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(503)); server.start()
        try {
            val http = HttpGateway(OkHttpClient())
            val details = NativeHockey(http, server.url("/").toString().trimEnd('/'), { 3000 }, cache)
            val provider = NativeHockeyProvider(http, details, server.url("/").toString().trimEnd('/'), { 3000 })
            val restored = provider.calendarGame(game.id)!!
            assertEquals(game.nativeReference, restored.nativeReference); assertEquals(GameState.FINAL, restored.state)
            assertEquals("2–3", restored.displayScore)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun scoringCorrectionsRecomputeWinnerRatherThanKeepTheScheduleWinner() {
        val wrong = match.copy(home = match.home.copy(isWinner = true))
        val result = NativeHockey.update(null, raw("FINAL", 3), null, null, null, wrong, 1000)!!
        assertTrue(result.liveMatch!!.away.isWinner); assertFalse(result.liveMatch!!.home.isWinner)
    }
}
