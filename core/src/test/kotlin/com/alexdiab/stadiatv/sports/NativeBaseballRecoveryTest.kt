package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Recorded iOS feed plus explicitly constructed failures/corrections; no live network probes. */
class NativeBaseballRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun feed() = parseJson(javaClass.getResource("/parity/mlb-live-late.json")!!.readText())
    private fun replace(raw: JsonElement, key: String, value: JsonElement) = JsonObject(raw.jsonObject + (key to value))
    private fun snapshot(root: JsonElement = feed(), at: Long = 1000) = NativeBaseballSnapshotCache.Snapshot(root, at, NativeBaseballSnapshotCache.SURFACES.associateWith { at })
    private fun status(root: JsonElement = feed(), detail: String = "Final", revision: String = "20240704_180000") = buildJsonObject {
        put("gamePk", root["gamePk"]!!); put("metaData", buildJsonObject { put("timeStamp", revision) })
        put("gameData", buildJsonObject { put("status", buildJsonObject { put("detailedState", detail); put("abstractGameState", if (detail == "Final") "Final" else "Live") }) })
    }

    @Test fun acceptedPublicSnapshotRestoresNativeGamePlaysAndOriginalTimes(): Unit = runBlocking {
        val cache = NativeBaseballSnapshotCache(temporary.newFolder(), { 3000 })
        cache.save("744834", snapshot())
        val saved = cache.load("744834")!!
        val match = saved.game()!!; val data = saved.details(match)!!
        assertEquals("mlb:744834", match.id); assertEquals("mlb:120", match.home.teamId)
        assertEquals(61, data.baseball!!.atBats.size); assertTrue(data.baseball!!.savedSnapshot)
        assertEquals(1000L, data.providerStamp!!.requestedAt); assertTrue(data.baseball!!.surfaceUpdatedAt.values.all { it == 1000L })
        assertTrue(data.boxScore.isNotEmpty()); assertTrue(data.baseball!!.atBats.any { it.pitches.isNotEmpty() })
        assertEquals("1", data.lineScore!!.awayHits); assertEquals("5", data.lineScore!!.homeHits)
        assertEquals("0", data.lineScore!!.awayErrors); assertEquals("0", data.lineScore!!.homeErrors)
    }

    @Test fun allActionsRunnersMeasurementsAndFinalPitchCountsSurviveMappingWithoutFabricatedValues() {
        val root = parseJson("""{"allPlays":[{"about":{"atBatIndex":0,"inning":1,"halfInning":"top","isComplete":true,"isScoringPlay":true},"result":{"event":"Home Run","rbi":2},
          "playEvents":[{"index":1,"type":"action","details":{"description":"Pitching substitution"}},
           {"index":2,"isPitch":true,"pitchNumber":1,"details":{"description":"In play, runs","isInPlay":true},"count":{"balls":0,"strikes":0,"outs":1},"pitchData":{"startSpeed":94.2},"hitData":{"totalDistance":400,"launchSpeed":105.4}},
           {"index":3,"isPitch":true,"pitchNumber":2,"details":{"description":"Strikeout"},"count":{"balls":4,"strikes":3,"outs":3}}],
          "runners":[{"movement":{"start":"1B","end":"score","isOut":false},"details":{"runner":{"id":1,"fullName":"First Runner"},"responsiblePitcher":{"id":2,"fullName":"Pitcher"},"movementReason":"r_b_i","earned":true}},
           {"movement":{"end":"score"},"details":{"runner":{"id":3,"fullName":"Batter"},"isScoringEvent":false}}]}]}""")
        val play = NativeBaseball.atBats(root, "744834").single()
        assertEquals(3, play.events.size); assertEquals(2, play.pitches.size); assertEquals("action", play.events.first().type)
        assertEquals(400.0, play.events[1].hitDistance!!, 0.0); assertEquals(105.4, play.events[1].exitVelocity!!, 0.0)
        assertEquals(4, play.events.last().balls); assertEquals(3, play.events.last().strikes); assertEquals(3, play.events.last().outs)
        assertTrue(play.runners.first().scored); assertEquals("First Runner", play.runners.first().player!!.name)
        assertEquals(true, play.runners.first().earned); assertFalse(play.runners.last().scored); assertNull(play.runners.last().earned)
        assertEquals(2, play.rbi); assertNull(play.awayScore); assertNull(play.homeScore); assertNull(play.events.first().hitDistance)
    }
    @Test fun probablePitchersAndSeriesComeFromNativeMetadataNotTeamNameGuesses() {
        val original = feed(); val match = NativeBaseballProvider.gameFromFeed(original)!!
        val metadata = JsonObject(original["gameData"]!!.jsonObject + mapOf(
            "probablePitchers" to parseJson("""{"away":{"id":1,"fullName":"Announced Away"},"home":{"id":2,"fullName":"Announced Home"}}"""),
            "game" to parseJson("""{"type":"W"}""")))
        val data = NativeBaseball.parse(replace(original, "gameData", metadata), match, 1000)!!
        assertEquals("Announced Away", data.baseball!!.probableAway!!.name); assertEquals("2", data.baseball!!.probableHome!!.id)
        assertTrue(data.gameInfo.contains("Series" to "World Series"))
        val absent = NativeBaseball.parse(replace(original, "gameData", JsonObject(metadata.jsonObject - "probablePitchers")), match, 1000)!!
        assertNull(absent.baseball!!.probableAway); assertNull(absent.baseball!!.probableHome)
    }

    @Test fun cacheRejectsTraversalWrongGameFutureExpiredCorruptAndMismatchedTeams(): Unit = runBlocking {
        val directory = temporary.newFolder(); val cache = NativeBaseballSnapshotCache(directory, { 3_000_000_000 })
        assertNull(cache.load("../744834")); cache.save("../744834", snapshot()); assertTrue(directory.listFiles()!!.isEmpty())
        for (text in listOf("not-json", """{"version":1,"gameId":"744835","at":2999999000}""")) {
            File(directory, "744834.json").writeText(text); assertNull(cache.load("744834"))
        }
        for (at in listOf(1L, 3_001_000_000L)) { cache.save("744834", snapshot(at = at)); assertNull(cache.load("744834")) }
        val root = replace(feed(), "gamePk", JsonPrimitive(744835)); cache.save("744834", snapshot(root, 2_999_999_000)); assertNull(cache.load("744834"))
    }

    @Test fun diskRejectsOlderReplacementAndBoundsNumberOfGames(): Unit = runBlocking {
        val directory = temporary.newFolder(); val cache = NativeBaseballSnapshotCache(directory, { 3000 })
        cache.save("744834", snapshot(at = 2000)); cache.save("744834", snapshot(at = 1000)); assertEquals(2000L, cache.load("744834")!!.at)
        repeat(15) { offset -> val id = "${744834 + offset}"; cache.save(id, snapshot(replace(feed(), "gamePk", JsonPrimitive(id)))) }
        assertEquals(12, directory.listFiles()!!.size); assertTrue(directory.listFiles()!!.all { it.extension == "json" })
    }

    @Test fun combinedFailureStillRecoversIndependentStatusLinePlaysAndBox(): Unit = runBlocking {
        val original = feed(); val match = NativeBaseballProvider.gameFromFeed(original)!!
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.requestUrl!!.queryParameter("fields") != null -> MockResponse().setBody(status().toString())
            request.path!!.endsWith("/feed/live") -> MockResponse().setResponseCode(503)
            request.path!!.endsWith("/linescore") -> MockResponse().setBody(original["liveData"]["linescore"].toString())
            request.path!!.endsWith("/playByPlay") -> MockResponse().setBody(original["liveData"]["plays"].toString())
            request.path!!.endsWith("/boxscore") -> MockResponse().setBody(original["liveData"]["boxscore"].toString())
            else -> MockResponse().setResponseCode(404)
        } }
        server.start()
        try {
            val cache = NativeBaseballSnapshotCache(temporary.newFolder(), { 3000 })
            val data = NativeBaseball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), { 2000 }, cache).load(match, null)!!
            assertEquals(GameState.FINAL, data.liveMatch!!.state); assertEquals(61, data.baseball!!.atBats.size)
            assertTrue(data.boxScore.isNotEmpty()); assertTrue(data.baseball!!.errors.containsKey("feed")); assertFalse(data.baseball!!.savedSnapshot)
            assertEquals(NativeBaseballSnapshotCache.SURFACES, data.baseball!!.surfaceUpdatedAt.keys)
            assertTrue(data.baseball!!.surfaceUpdatedAt.values.all { it == 2000L }); assertEquals(5, server.requestCount)
            assertEquals(GameState.FINAL, cache.load("744834")!!.game()!!.state)
        } finally { server.shutdown() }
    }

    @Test fun stalePlaysDoNotVetoNewFinalStatusAndLineAndKeepTheirOwnTime(): Unit = runBlocking {
        val original = feed(); val match = NativeBaseballProvider.gameFromFeed(original)!!
        val cache = NativeBaseballSnapshotCache(temporary.newFolder(), { 3000 }); cache.save("744834", snapshot())
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = when {
            request.requestUrl!!.queryParameter("fields") != null -> MockResponse().setBody(status().toString())
            request.path!!.endsWith("/feed/live") -> MockResponse().setResponseCode(503)
            request.path!!.endsWith("/linescore") -> MockResponse().setBody(original["liveData"]["linescore"].toString())
            request.path!!.endsWith("/playByPlay") -> MockResponse().setBody("""{"allPlays":[]}""")
            else -> MockResponse().setResponseCode(503)
        } }; server.start()
        try {
            val data = NativeBaseball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), { 2000 }, cache).load(match, null)!!
            assertEquals(GameState.FINAL, data.liveMatch!!.state); assertEquals(LiveContext.EMPTY, data.liveMatch!!.live)
            assertEquals(61, data.baseball!!.atBats.size); assertEquals(1000L, data.baseball!!.surfaceUpdatedAt["plays"])
            assertEquals(2000L, data.baseball!!.surfaceUpdatedAt["overview"]); assertEquals(2000L, data.baseball!!.surfaceUpdatedAt["linescore"])
            assertEquals(1000L, data.baseball!!.surfaceUpdatedAt["boxscore"]); assertTrue(data.baseball!!.errors.keys.containsAll(setOf("feed", "plays", "boxscore")))
            val restored = cache.load("744834")!!; assertEquals(GameState.FINAL, restored.game()!!.state)
            assertEquals(61, restored.details(restored.game()!!)!!.baseball!!.atBats.size)
        } finally { server.shutdown() }
    }

    @Test fun failedColdLinkAndDetailRetainSavedDataWithoutAdvancingCheckedTimes(): Unit = runBlocking {
        val cache = NativeBaseballSnapshotCache(temporary.newFolder(), { 3000 }); cache.save("744834", snapshot())
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503) }; server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val base = server.url("/").toString().trimEnd('/')
            val native = NativeBaseball(http, base, { 2000 }, cache); val provider = NativeBaseballProvider(http, native, base, { 2000 })
            val match = provider.calendarGame("mlb:744834")!!; val data = native.load(match, null)!!
            assertTrue(data.baseball!!.savedSnapshot); assertEquals(1000L, data.providerStamp!!.requestedAt)
            assertTrue(data.baseball!!.surfaceUpdatedAt.values.all { it == 1000L }); assertEquals(1000L, cache.load("744834")!!.at)
            val count = server.requestCount
            assertTrue(native.load(match, data)!!.baseball!!.savedSnapshot); assertEquals(count, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun wrongGameAndForeignBoxCannotOverwriteSavedSnapshot(): Unit = runBlocking {
        val original = feed(); val match = NativeBaseballProvider.gameFromFeed(original)!!
        val cache = NativeBaseballSnapshotCache(temporary.newFolder(), { 3000 }); cache.save("744834", snapshot())
        val wrongBox = replace(original["liveData"]["boxscore"]!!, "teams", parseJson("""{"away":{"team":{"id":1}},"home":{"team":{"id":2}}}"""))
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when {
            request.path!!.contains("boxscore") -> wrongBox.toString()
            else -> replace(original, "gamePk", JsonPrimitive(744835)).toString()
        }) }; server.start()
        try {
            val data = NativeBaseball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), { 2000 }, cache).load(match, null)!!
            assertTrue(data.baseball!!.savedSnapshot); assertTrue(data.baseball!!.errors.containsKey("boxscore")); assertEquals(1000L, cache.load("744834")!!.at)
            assertEquals("744834", data.providerStamp!!.gameId)
        } finally { server.shutdown() }
    }
}
