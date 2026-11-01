package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Entirely constructed provider-shaped miniature game, never a purported recording or runtime fixture. */
class NativeSoccerRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private val id = "900007"
    private fun overview() = parseJson("""{"matchId":"900007","competitionId":"8","season":"2026","kickoff":"2026-10-23 19:00:00","period":"SecondHalf","clock":"60","ground":"Test Ground","homeTeam":{"id":"100","name":"Test Home","abbr":"HOM","score":1},"awayTeam":{"id":"200","name":"Test Away","abbr":"AWY","score":0}}""")
    private fun lineups() = parseJson("""{"home_team":{"players":[{"id":"101","firstName":"Home","lastName":"Player"}],"formation":{"lineup":[["101"]]}},"away_team":{"players":[{"id":"201","firstName":"Away","lastName":"Player"}],"formation":{"lineup":[["201"]]}}}""")
    private fun commentary(minute: String = "20:00:00", cursor: String? = "first", text: String = "Original") = buildJsonObject {
        put("matchId", id); put("data", JsonArray(listOf(buildJsonObject { put("timestamp", "2026-10-23 $minute"); put("comment", text); put("type", "comment") })))
        put("pagination", buildJsonObject { cursor?.let { put("_next", it) } })
    }
    private fun payloads() = linkedMapOf("overview" to overview(), "lineups" to lineups(),
        "events" to parseJson("""{"matchId":"900007","homeTeam":{"id":"100","goals":[{"playerId":"101","assistPlayerId":"102","goalType":"Goal","time":"47","period":"FirstHalf"}]},"awayTeam":{"id":"200"}}"""),
        "statistics" to parseJson("""[{"teamId":"100","stats":{"possessionPercentage":55}},{"teamId":"200","stats":{"possessionPercentage":45}}]"""),
        "officials" to parseJson("""{"matchId":"900007","matchOfficials":[{"official":{"name":"Test Referee"},"type":"Referee"}]}"""), "commentary" to commentary())
    private fun match() = NativePremierLeagueMapping.game(overview())!!
    private val names = mapOf("101" to "Home Player", "102" to "Assist Player", "201" to "Away Player")
    private fun update(time: Long = at) = NativeSoccerUpdate(match(), id, time, full = true,
        events = NativeSoccerDetails.events(payloads().getValue("events"), match(), id, names, "100", "200"),
        lineups = listOf(NativeSoccer.lineup(lineups()["home_team"], true)!!, NativeSoccer.lineup(lineups()["away_team"], false)!!),
        statistics = NativeSoccerDetails.stats(payloads().getValue("statistics"), "100", "200"),
        officials = NativeSoccerDetails.officials(payloads().getValue("officials"), id), commentary = NativeSoccer.comments(commentary(), id), nextCursor = "first", playerNames = names)
    private fun original() = NativeSoccerReducer.apply(null, update())!!
    private fun saved() = NativeSoccerSnapshotCache.Snapshot().accept(payloads(), at, original())

    @Test fun lightScoreCannotRedateOrEraseIndependentResourcesAndFinalStateCannotRegress() {
        val first = original()
        val corrected = match().copy(home = match().home.copy(score = "2"))
        val light = NativeSoccerReducer.apply(first, NativeSoccerUpdate(corrected, id, at + 10_000))!!
        assertEquals("2", light.liveMatch!!.home.score); assertEquals(first.plays, light.plays); assertEquals(first.teamStats, light.teamStats)
        assertEquals(at, light.soccer!!.lastFullRefresh); assertEquals(at + 10_000, light.soccer!!.surfaceUpdatedAt["overview"])
        for (kind in setOf("events", "lineups", "statistics", "officials", "commentary")) assertEquals(at, light.soccer!!.surfaceUpdatedAt[kind])
        val final = NativeSoccerReducer.apply(light, NativeSoccerUpdate(corrected.copy(state = GameState.FINAL, live = LiveContext.EMPTY), id, at + 20_000))!!
        assertSame(final, NativeSoccerReducer.apply(final, update(at + 30_000)))
        assertSame(light, NativeSoccerReducer.apply(light, update(at - 1)))
    }

    @Test fun validEmptyEventsCanWithdrawAGoalButEmptyLineupsStatsAndOfficialsDoNotUnannounceAcceptedData() {
        val first = original()
        val next = NativeSoccerReducer.apply(first, NativeSoccerUpdate(match(), id, at + 1000, events = emptyList(), lineups = emptyList(),
            statistics = emptyList(), officials = emptyList(), commentary = emptyList(), nextCursor = null))!!
        assertTrue(next.plays.isEmpty()); assertNotNull(next.playsSource)
        assertEquals(first.soccer!!.lineups, next.soccer!!.lineups); assertEquals(first.soccer!!.officials, next.soccer!!.officials)
        assertEquals(first.teamStats, next.teamStats); assertEquals(first.soccer!!.commentary, next.soccer!!.commentary); assertNull(next.soccer!!.nextCursor)
        for (kind in setOf("lineups", "statistics", "officials")) assertEquals(at, next.soccer!!.surfaceUpdatedAt[kind])
        assertEquals(at + 1000, next.soccer!!.surfaceUpdatedAt["events"])
    }

    @Test fun commentaryCursorSurvivesLivePollsAfterPagingAndForeignGameDataCannotLeak() {
        val first = original().let { it.copy(soccer = it.soccer!!.copy(nextCursor = "deep-history", commentaryPages = 2)) }
        val next = NativeSoccerReducer.apply(first, NativeSoccerUpdate(match(), id, at + 1000, commentary = NativeSoccer.comments(commentary(text = "Corrected"), id), nextCursor = "top-page"))!!
        assertEquals("deep-history", next.soccer!!.nextCursor); assertEquals(2, next.soccer!!.commentaryPages)
        assertEquals("Corrected", next.soccer!!.commentary.single().text)
        val foreign = first.copy(providerStamp = first.providerStamp!!.copy(gameId = "different"))
        val fresh = NativeSoccerReducer.apply(foreign, NativeSoccerUpdate(match(), id, at + 1000))!!
        assertTrue(fresh.plays.isEmpty()); assertTrue(fresh.soccer!!.lineups.isEmpty())
        assertNull(NativeSoccerReducer.apply(first, NativeSoccerUpdate(match(), "wrong-reference", at + 1000)))
    }

    @Test fun coldSnapshotRestoresTypedEventsPlayersAndOriginalPerResourceTimes(): Unit = runBlocking {
        val cache = NativeSoccerSnapshotCache(temporary.newFolder(), { at + 30_000 })
        val light = NativeSoccerReducer.apply(original(), NativeSoccerUpdate(match(), id, at + 10_000))!!
        val snapshot = saved().accept(mapOf("overview" to overview()), at + 10_000, light)
        cache.save(id, snapshot)
        val cold = cache.load(id)!!.details(match())!!
        assertTrue(cold.soccer!!.savedSnapshot); assertEquals(at + 10_000, cold.providerStamp!!.requestedAt)
        assertEquals(at, cold.soccer!!.surfaceUpdatedAt["lineups"]); assertEquals(at, cold.soccer!!.surfaceUpdatedAt["commentary"])
        assertEquals("45+2′", cold.plays.single().clock); assertEquals("Assist Player", cold.plays.single().soccerEvent!!.secondary!!.name)
        assertEquals("Test Referee", cold.soccer!!.officials.single().name); assertEquals("first", cold.soccer!!.nextCursor)
        assertEquals(2, cold.soccer!!.lineups.size); assertEquals(original().teamStats, cold.teamStats)
    }

    @Test fun cacheRejectsTraversalForeignProviderSeasonFutureExpiryAndOlderWrites(): Unit = runBlocking {
        var clock = at
        val folder = temporary.newFolder(); val cache = NativeSoccerSnapshotCache(folder, { clock })
        cache.save(id, saved()); assertNotNull(cache.load(id)); assertNull(cache.load("../$id"))
        cache.save(id, saved().copy(surfaces = saved().surfaces.mapValues { it.value.copy(at = at - 1) }, lastFullRefresh = at - 1))
        assertEquals(at, cache.load(id)!!.surfaces.getValue("overview").at)
        val foreignSeason = saved().copy(surfaces = saved().surfaces + ("overview" to NativeSoccerSnapshotCache.Surface(at, JsonObject(overview().obj.orEmpty() + ("season" to JsonPrimitive("2025"))))))
        val otherCache = NativeSoccerSnapshotCache(temporary.newFolder(), { at }); otherCache.save(id, foreignSeason); assertNull(otherCache.load(id))
        clock = at - 300_001; assertNull(cache.load(id)); clock = at + 31L * 86_400_000; assertNull(cache.load(id))
        clock = at
        val file = File(folder, "epl-$id.json"); val root = parseJson(file.readText())
        file.writeText(JsonObject(root.obj.orEmpty() + ("provider" to JsonPrimitive("MLS"))).toString())
        assertNull(cache.load(id))
    }

    @Test fun olderCommentPagesAndConcurrentLiveRefreshMergeWithoutMovingTheTailCursorOrRedatingHistory(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeSoccerSnapshotCache(folder, { at + 30_000 })
        val older = saved().withOlderComments(commentary("19:00:00", "tail", "History"))
        assertEquals(at, older.surfaces.getValue("commentary").at)
        val latest = NativeSoccerReducer.apply(original(), NativeSoccerUpdate(match(), id, at + 10_000, commentary = NativeSoccer.comments(commentary("20:01:00", "top-again", "New live"), id), nextCursor = "top-again"))!!
        val live = saved().accept(mapOf("overview" to overview(), "commentary" to commentary("20:01:00", "top-again", "New live")), at + 10_000, latest)
        cache.save(id, live); cache.save(id, older)
        val cold = cache.load(id)!!.details()!!
        assertEquals("tail", cold.soccer!!.nextCursor); assertEquals(1, cold.soccer!!.commentaryPages)
        assertEquals(setOf("Original", "History", "New live"), cold.soccer!!.commentary.map { it.text }.toSet())
        assertEquals(at + 10_000, cold.soccer!!.surfaceUpdatedAt["commentary"])
    }

    @Test fun cacheBoundsFileCountAndSizeAndDoesNotLeavePendingFiles(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeSoccerSnapshotCache(folder, { at })
        for (number in 1..14) {
            val key = "${900000 + number}"
            val surfaces = saved().surfaces.mapValues { (_, surface) -> surface.copy(payload = if (surface.payload.obj?.containsKey("matchId") == true) JsonObject(surface.payload.obj.orEmpty() + ("matchId" to JsonPrimitive(key))) else surface.payload) }
            cache.save(key, saved().copy(surfaces = surfaces))
        }
        assertEquals(12, folder.listFiles()!!.count { it.extension == "json" })
        assertFalse(folder.listFiles()!!.any { it.extension == "pending" })
        val huge = saved().copy(surfaces = saved().surfaces + ("commentary" to NativeSoccerSnapshotCache.Surface(at, commentary(text = "x".repeat(2 * 1024 * 1024)))))
        val oversized = NativeSoccerSnapshotCache(temporary.newFolder(), { at }); oversized.save(id, huge); assertNull(oversized.load(id))
    }

    @Test fun knownNativeColdLinkUsesCacheWithoutNetworkAndSavedDataDoesNotPretendARefreshSucceeded(): Unit = runBlocking {
        val cache = NativeSoccerSnapshotCache(temporary.newFolder(), { at + 30_000 }); cache.save(id, saved())
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(503)); start() }
        try {
            val http = HttpGateway(OkHttpClient()); val service = NativeSoccer(http, server.url("/").toString(), { at + 30_000 }, snapshots = cache)
            val provider = NativePremierLeagueProvider(NativePremierLeagueClient(http, server.url("/").toString()), cachedMatch = service::cachedMatch)
            assertEquals(match().id, provider.calendarGame(match().id)!!.id); assertEquals(0, server.requestCount)
            val centres = NativeGameCentres(http, soccer = service)
            assertTrue(centres.cached(match())!!.soccer!!.savedSnapshot)
            assertNull(centres.load(match(), centres.cached(match())))
            assertEquals(at, centres.cached(match())!!.providerStamp!!.requestedAt)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun nativeLoadPopulatesColdCacheAndARealSuccessfulRefreshClearsSavedState(): Unit = runBlocking {
        var clock = at; var offline = false
        val cache = NativeSoccerSnapshotCache(temporary.newFolder(), { clock })
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (offline) return MockResponse().setResponseCode(503)
                if (path.endsWith("players-by-id")) return MockResponse().setBody("""[{"id":"102","firstName":"Assist","lastName":"Player"}]""")
                val kind = when { path.endsWith(id) -> "overview"; path.endsWith("stats") -> "statistics"; else -> path.substringAfterLast('/') }
                return MockResponse().setBody(payloads().getValue(kind).toString())
            }
        }; start() }
        try {
            val service = NativeSoccer(HttpGateway(OkHttpClient()), server.url("/").toString(), { clock }, snapshots = cache)
            val first = service.load(match(), null)!!
            assertFalse(first.soccer!!.savedSnapshot); assertEquals("Assist Player", first.plays.single().soccerEvent!!.secondary!!.name)
            assertTrue(service.cached(match())!!.soccer!!.savedSnapshot)
            clock += 30_000; offline = true
            assertNotNull(runCatching { service.load(match(), null, force = true) }.exceptionOrNull())
            assertEquals(at, service.cached(match())!!.providerStamp!!.requestedAt)
            clock += 60_000; offline = false
            val recovered = service.load(match(), service.cached(match()), force = true)!!
            assertFalse(recovered.soccer!!.savedSnapshot); assertTrue(recovered.soccer!!.errors.isEmpty())
            assertEquals(clock, recovered.providerStamp!!.requestedAt); assertEquals(first.plays, recovered.plays)
        } finally { server.shutdown() }
    }
}
