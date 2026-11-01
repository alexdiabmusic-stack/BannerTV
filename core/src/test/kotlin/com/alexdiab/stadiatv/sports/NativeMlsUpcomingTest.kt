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
import java.io.IOException

/** Announced schedule / unpublished detail scenarios are explicitly constructed from pinned source slices. */
class NativeMlsUpcomingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private val id = "MLS-MAT-0009LX"
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/mls-$name.source.json")!!.readText())
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun announced() = replace(fixture("schedule")["schedule"].array.single { it["match_id"].string() == id }, "match_status", JsonPrimitive("scheduled"))
    private fun server(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }; start()
    }
    private fun client(server: MockWebServer, now: () -> Long = { at }) = NativeMlsClient(HttpGateway(OkHttpClient()), server.url("/").toString(), now)

    @Test fun announcedColdCalendarUsesThinCacheWithoutNetworkOrInventedScore(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeMlsUpcomingCache(folder, { at + 20_000 })
        cache.save(listOf(announced()), at)
        val network = server { MockResponse().setResponseCode(503) }
        try {
            val service = NativeMls(client(network), { at + 20_000 }, upcoming = NativeMlsUpcomingCache(folder, { at + 20_000 }))
            val provider = NativeMlsProvider(client(network), cachedMatch = service::cachedMatch)
            val game = provider.calendarGame("mls:$id")!!
            assertEquals(GameState.PRE, game.state); assertNull(game.home.score); assertNull(game.away.score)
            val saved = service.cached(game)!!
            assertTrue(saved.soccer!!.overviewNotPublished); assertTrue(saved.soccer!!.savedSnapshot)
            assertEquals(at, saved.providerStamp!!.requestedAt); assertEquals("schedule", saved.providerStamp!!.revision)
            assertEquals(mapOf("schedule" to at), saved.soccer!!.surfaceUpdatedAt)
            assertEquals(0, network.requestCount); assertFalse(File(folder, "upcoming.pending").exists())
        } finally { network.shutdown() }
    }

    @Test fun unpublished404IsNotFailureOrOverviewAndNeitherRetryNorReconnectBypassesAbsenceCooldown(): Unit = runBlocking {
        var clock = at; val cache = NativeMlsUpcomingCache(temporary.newFolder(), { clock }); cache.save(listOf(announced()), at)
        val game = NativeMlsMapping.game(announced())!!
        val network = server { MockResponse().setResponseCode(404) }
        try {
            val service = NativeMls(client(network) { clock }, { clock }, upcoming = cache)
            val first = service.load(game, service.cached(game))
            assertTrue(first.soccer!!.overviewNotPublished); assertTrue(first.soccer!!.errors.isEmpty())
            assertFalse("overview" in first.soccer!!.surfaceUpdatedAt); assertEquals(at, first.providerStamp!!.requestedAt)
            clock += 10_000; service.onNetworkRestored()
            val retry = service.load(game, first, force = true, tab = "Timeline")
            assertEquals(at, retry.providerStamp!!.requestedAt); assertEquals(1, network.requestCount)
            clock += 50_001; service.load(game, retry)
            assertEquals(2, network.requestCount) // One overview each time, never four unpublished section requests.
        } finally { network.shutdown() }
    }

    @Test fun unpublishedWithoutSavedScheduleDoesNotInventAProviderTimestamp(): Unit = runBlocking {
        val network = server { MockResponse().setResponseCode(404) }
        try {
            val result = NativeMls(client(network), { at }).load(NativeMlsMapping.game(announced())!!, null)
            assertNull(result.providerStamp); assertTrue(result.soccer!!.surfaceUpdatedAt.isEmpty())
            assertFalse(result.soccer!!.savedSnapshot); assertTrue(result.soccer!!.overviewNotPublished)
        } finally { network.shutdown() }
    }

    @Test fun publishedOverviewReplacesScheduledProvenanceAndClearsUnpublishedState(): Unit = runBlocking {
        var clock = at; var published = false
        val cache = NativeMlsUpcomingCache(temporary.newFolder(), { clock }); cache.save(listOf(announced()), at)
        val network = server { request ->
            if (!published) MockResponse().setResponseCode(404) else {
                val raw = when {
                    request.requestUrl!!.encodedPath.endsWith("key_events") -> fixture("key-events")
                    request.requestUrl!!.encodedPath.endsWith("commentary") -> fixture("commentary")
                    request.requestUrl!!.encodedPath.startsWith("/statistics/players/") -> fixture("player-match-stats")
                    request.requestUrl!!.encodedPath.startsWith("/statistics/clubs/") -> fixture("team-match-stats")
                    else -> fixture("match-overview-fulltime")
                }
                MockResponse().setBody(raw.toString())
            }
        }
        try {
            val service = NativeMls(client(network) { clock }, { clock }, upcoming = cache)
            val game = NativeMlsMapping.game(announced())!!; val first = service.load(game, service.cached(game))
            published = true; clock += 60_001
            val next = service.load(game, first)
            assertEquals(GameState.FINAL, next.liveMatch!!.state); assertEquals("2–2", next.liveMatch!!.displayScore)
            assertFalse(next.soccer!!.overviewNotPublished); assertFalse(next.soccer!!.savedSnapshot)
            assertEquals(clock, next.soccer!!.surfaceUpdatedAt["overview"]); assertEquals(clock, next.providerStamp!!.requestedAt)
            assertEquals("MLS match overview", next.gameInfo.last { it.first == "Source" }.second)
            assertEquals(78, next.plays.size); assertEquals(6, network.requestCount)
        } finally { network.shutdown() }
    }

    @Test fun absentLiveFinalAndPreviouslyPublishedPregameOverviewRemainErrors(): Unit = runBlocking {
        val network = server { MockResponse().setResponseCode(404) }
        try {
            val service = NativeMls(client(network), { at }); val pre = NativeMlsMapping.game(announced())!!
            for (state in listOf(GameState.LIVE, GameState.FINAL)) {
                assertTrue(runCatching { service.load(pre.copy(state = state), null) }.exceptionOrNull() is HttpStatusException)
            }
            val published = NativeSoccerReducer.apply(null, NativeSoccerUpdate(pre, id, at - 1000, "MLS", "MLS"))!!
            assertTrue(runCatching { service.load(pre, published) }.exceptionOrNull() is HttpStatusException)
            assertEquals(at - 1000, published.soccer!!.surfaceUpdatedAt["overview"])
        } finally { network.shutdown() }
    }

    @Test fun cachePreservesOriginalPerFixtureAgeAndOlderWritesCannotRollbackOrDelete(): Unit = runBlocking {
        var clock = at; val folder = temporary.newFolder(); val cache = NativeMlsUpcomingCache(folder, { clock })
        val other = replace(announced(), "match_id", JsonPrimitive("MLS-MAT-0009DZ"))
        cache.save(listOf(announced()), at); clock += 20_000; cache.save(listOf(other), clock)
        assertEquals(at, cache.load(id)!!.at); assertEquals(clock, cache.load("MLS-MAT-0009DZ")!!.at)
        cache.save(listOf(replace(announced(), "stadium_name", JsonPrimitive("Older name"))), at - 1000)
        cache.save(listOf(replace(announced(), "match_status", JsonPrimitive("finalWhistle"))), at - 1000)
        assertEquals(at, cache.load(id)!!.at); assertNotEquals("Older name", cache.load(id)!!.match()!!.venue)
        cache.save(listOf(replace(announced(), "match_status", JsonPrimitive("finalWhistle"))), clock)
        assertNull(cache.load(id)); assertNotNull(cache.load("MLS-MAT-0009DZ"))
        clock = at + 31L * 86_400_000; assertNull(cache.load("MLS-MAT-0009DZ"))
    }

    @Test fun cacheRejectsForeignCorruptFutureAndTraversalAndDoesNotCreateRichSnapshots(): Unit = runBlocking {
        var clock = at; val folder = temporary.newFolder(); val cache = NativeMlsUpcomingCache(folder, { clock })
        cache.save(listOf(announced()), at); assertNull(cache.load("../$id")); assertNull(cache.load("mls:$id"))
        clock = at - 300_001; assertNull(cache.load(id)); clock = at
        val file = File(folder, "upcoming.json")
        file.writeText(file.readText().replace("\"provider\":\"MLS\"", "\"provider\":\"EPL\"")); assertNull(cache.load(id))
        file.writeText("{broken"); assertNull(cache.load(id)); cache.save(listOf(announced()), at)
        assertNotNull(cache.load(id)); assertEquals(listOf("upcoming.json"), folder.list()!!.toList())
        assertNull(NativeMlsSnapshotCache(folder, { at }).load(id))
    }

    @Test fun upcomingIndexIsBoundedAndKeepsNewerRecordsSeparatelyFromRichGames(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeMlsUpcomingCache(folder, { at })
        val rows = (0 until 160).map { index -> replace(announced(), "match_id", JsonPrimitive("MLS-MAT-${index.toString().padStart(6, '0')}")) }
        cache.save(rows, at - 1000); cache.save(listOf(announced()), at)
        val file = File(folder, "upcoming.json"); val root = parseJson(file.readText())
        assertEquals(128, root["entries"]!!.jsonObject.size); assertTrue(file.length() <= 384 * 1024)
        assertEquals(at, cache.load(id)!!.at); assertFalse(File(folder, "$id.json").exists())
    }

    @Test fun largeCalendarPrunesByByteBudgetInsteadOfFailingToSaveEveryFixture(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeMlsUpcomingCache(folder, { at })
        val longRow = replace(announced(), "stadium_name", JsonPrimitive("A".repeat(6000)))
        val rows = (0 until 128).map { replace(longRow, "match_id", JsonPrimitive("MLS-MAT-${it.toString().padStart(6, '0')}")) }
        cache.save(rows, at - 1000); cache.save(listOf(announced()), at)
        assertEquals(at, cache.load(id)!!.at)
        val file = File(folder, "upcoming.json"); assertTrue(file.length() in 1..384L * 1024)
        val size = parseJson(file.readText())["entries"]!!.jsonObject.size
        assertTrue(size in 2..127)
    }

    @Test fun announcedTableUsesVerifiedScheduleSeasonWithoutRequestingUnpublishedOverview(): Unit = runBlocking {
        val cache = NativeMlsUpcomingCache(temporary.newFolder(), { at }); cache.save(listOf(announced()), at)
        val network = server { MockResponse().setBody(fixture("standings-conference").toString()) }
        try {
            val table = NativeMls(client(network), { at }, upcoming = cache).table(NativeMlsMapping.game(announced())!!)
            assertEquals(2026, table.season); assertFalse(table.provisional); assertEquals(2, table.groups.size)
            val request = network.takeRequest(); assertEquals("/competitions/MLS-COM-000001/seasons/MLS-SEA-0001KA/standings", request.requestUrl!!.encodedPath)
            assertEquals("false", request.requestUrl!!.queryParameter("is_live")); assertEquals(1, network.requestCount)
        } finally { network.shutdown() }
    }

    @Test fun scheduleOnlyCommitsValidatedRowsAfterAllPagesSucceed(): Unit = runBlocking {
        val row = announced(); var recorded = emptyList<JsonElement>()
        val network = server { request -> MockResponse().setBody(when {
            request.requestUrl!!.encodedPath.endsWith("/seasons") -> """{"seasons":[{"season":2026,"season_id":"MLS-SEA-0001KA"}]}"""
            else -> """{"schedule":[$row]}"""
        }) }
        try {
            val provider = NativeMlsProvider(client(network), { at }, rememberSchedule = { rows, time -> recorded = rows; assertEquals(at, time) })
            assertEquals(1, provider.scores(NativeMlsMapping.game(row)!!.startMillis).size)
            assertEquals(id, recorded.single()["match_id"].string())
        } finally { network.shutdown() }
    }
}
