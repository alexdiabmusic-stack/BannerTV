package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.TimeZone

/** Real mapper slices in deliberately miniature complete envelopes, plus constructed pagination/failure cases. */
class NativeLaLigaProviderTest {
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun official() = fixture("official-matches-finished")["matches"].array.first()
    private fun page(key: String, rows: List<JsonElement>, total: Int = rows.size) = buildJsonObject { put("total", total); put(key, JsonArray(rows)) }.toString()
    private fun subscriptions() = """{"total":2,"subscriptions":[{"slug":"verified-2025","year":2025,"competition":{"id":1,"slug":"primera-division"}},{"slug":"verified-2026","year":2026,"competition":{"id":1,"slug":"primera-division"}}]}"""
    private fun server(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply { dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = reply(request)
    }; start() }
    private fun client(server: MockWebServer) = NativeLaLigaClient(HttpGateway(OkHttpClient()), server.url("/public-service").toString(), "constructed-public-setting", { at })

    @Test fun completeOfficialSeasonIsSortedAndFullLocalLastDayIsIncludedDespiteStartHour(): Unit = runBlocking {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val dates = listOf("2026-08-18T00:00:00Z", "2026-08-17T23:59:00Z", "2026-08-16T08:00:00Z", "2026-08-15T17:30:00Z")
        val rows = dates.mapIndexed { index, date -> replace(replace(official(), "id", JsonPrimitive(100000 + index)), "date", JsonPrimitive(date)) }
        val requests = mutableListOf<String>()
        val network = server { request ->
            synchronized(requests) { requests += request.path!! }
            MockResponse().setBody(if (request.requestUrl!!.encodedPath.endsWith("/subscriptions")) subscriptions() else {
                val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
                page("matches", if (offset == 0) rows.take(3) else rows.drop(3), 4)
            })
        }
        try {
            val provider = NativeLaLigaProvider(client(network), { at })
            val start = Dates.parseIso("2026-08-15T12:00:00Z")!!
            val games = provider.schedule(start, 3)
            assertEquals(listOf("laliga:100003", "laliga:100002", "laliga:100001"), games.map { it.id })
            assertEquals(listOf("laliga:100003"), provider.scores(start).map { it.id })
            assertEquals(3, network.requestCount); assertTrue(requests.last().contains("offset=3"))
            assertTrue(requests.filter { "/matches?" in it }.all { "subscription=verified-2026" in it && "competition=primera-division" in it })
            assertEquals("LaLiga", provider.providerId); assertFalse(SportsCapability.GameDetails in provider.capabilities)
        } finally { network.shutdown(); TimeZone.setDefault(old) }
    }

    @Test fun actualSquadIsCompletelyPagedBeforeFilteringAndOptaIdsReachProfiles(): Unit = runBlocking {
        val prototype = fixture("official-squad")["squads"].array.first()
        val rows = (0..21).map { index ->
            var row: JsonElement = replace(prototype, "opta_id", JsonPrimitive("p${1000 + index}"))
            if (index == 4) row = replace(row, "role", buildJsonObject { put("slug", "entrenador") })
            row
        }
        val paths = mutableListOf<String>()
        val network = server { request ->
            synchronized(paths) { paths += request.path!! }
            val path = request.requestUrl!!.encodedPath
            MockResponse().setBody(when {
                path.endsWith("/subscriptions") -> subscriptions()
                path.endsWith("/standing") -> fixture("official-standing").toString()
                path.endsWith("/squad") -> page("squads", if (request.requestUrl!!.queryParameter("offset") == "0") rows.take(20) else rows.drop(20), 22)
                else -> "{}"
            })
        }
        try {
            val groups = NativeLaLigaProvider(client(network), { at }).roster("laliga:15")
            assertEquals(21, groups.sumOf { it.athletes.size }); assertTrue(groups.flatMap { it.athletes }.any { it.id == "laliga:p1021" })
            assertFalse(groups.flatMap { it.athletes }.any { it.id == "laliga:p1004" })
            assertTrue(paths.any { "/teams/real-madrid/squad?" in it && "offset=20" in it })
            assertTrue(paths.filter { "/squad?" in it }.all { "subscription=verified-2026" in it })
        } finally { network.shutdown() }
    }

    @Test fun profileLookupStreamsActualOffsetsAndPreservesSelectedBiographyAndSignedStats(): Unit = runBlocking {
        fun stats(id: String) = parseJson("""{"opta_id":"$id","stats":[{"name":"appearances","stat":7},{"name":"clean_sheets","stat":0},{"name":"performance_delta","stat":-0.25}]}""")
        val paths = mutableListOf<String>()
        val network = server { request ->
            synchronized(paths) { paths += request.path!! }
            MockResponse().setBody(if (request.requestUrl!!.encodedPath.endsWith("/subscriptions")) subscriptions()
                else page("player_stats", if (request.requestUrl!!.queryParameter("offset") == "0") listOf(stats("p999")) else listOf(stats("p60772")), 2))
        }
        try {
            val selected = NativeLaLigaMapping.squad(fixture("official-squad")["squads"].array, "laliga:15", at).first { it.id == "laliga:p60772" }
            val result = NativeLaLigaProvider(client(network), { at }).athlete(selected)
            assertEquals(selected, result.athlete); assertEquals("Bree", result.athlete.birthPlace)
            assertEquals("BE", result.bio["Nationality code"]); assertEquals("0", result.headlineStats["Clean Sheets"])
            assertEquals("-0.25", result.stats["Performance Delta"]); assertEquals("2026–27 season", result.statlineLabel)
            assertTrue(paths.last().contains("offset=1")); assertEquals(3, network.requestCount)
        } finally { network.shutdown() }
    }

    @Test fun foreignAndAmbiguousProfilesAreRejectedWithoutWrongNamespaceRequests(): Unit = runBlocking {
        val network = server { request -> MockResponse().setBody(if (request.requestUrl!!.encodedPath.endsWith("/subscriptions")) subscriptions()
            else """{"total":2,"player_stats":[{"opta_id":"p60772","stats":[]},{"opta_id":"p60772","stats":[]}]}""") }
        try {
            val provider = NativeLaLigaProvider(client(network), { at })
            for (id in listOf("60772", "fotmob:60772", "laliga:560")) assertTrue(runCatching { provider.athlete(Athlete(id, "Constructed")) }.exceptionOrNull() is IOException)
            assertEquals(0, network.requestCount)
            assertTrue(runCatching { provider.athlete(Athlete("laliga:p60772", "Constructed")) }.exceptionOrNull() is IOException)
            assertEquals(2, network.requestCount)
        } finally { network.shutdown() }
    }

    @Test fun profileChangedTotalWrongYearAndRepeatedPageFailRatherThanReportAbsenceOrPartialStats(): Unit = runBlocking {
        for (scenario in listOf("year", "total", "repeat")) {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val network = server { request -> MockResponse().setBody(if (request.requestUrl!!.encodedPath.endsWith("/subscriptions")) subscriptions() else {
                val n = calls.incrementAndGet()
                if (scenario == "year") """{"year":2025,"total":1,"player_stats":[{"opta_id":"p999","stats":[]}]}"""
                else """{"total":${if (scenario == "total" && n > 1) 3 else 2},"player_stats":[{"opta_id":"p999","stats":[]}]}"""
            }) }
            try {
                assertTrue(runCatching { NativeLaLigaProvider(client(network), { at }).athlete(Athlete("laliga:p60772", "Constructed")) }.exceptionOrNull() is IOException)
                assertTrue(network.requestCount <= 3)
            } finally { network.shutdown() }
        }
    }

    @Test fun officialCalendarIsCacheFirstAndNeverRequestsEspnForQualifiedNativeIds(): Unit = runBlocking {
        val game = NativeLaLigaMapping.match(official(), 2026, "verified-2026")!!
        val network = server { MockResponse().setResponseCode(503) }
        try {
            val provider = NativeLaLigaProvider(client(network), { at }, cachedMatch = { if (it == "102249") game else null })
            assertEquals(game, provider.calendarGame(game.id)); assertNull(provider.calendarGame("102249")); assertNull(provider.calendarGame("epl:102249"))
            assertEquals(0, network.requestCount)
        } finally { network.shutdown() }
    }

    @Test fun duplicateForeignAndWrongSeasonScheduleNeverBecomesAPartialSuccess(): Unit = runBlocking {
        for (rows in listOf(listOf(official(), official()), listOf(official(), replace(official(), "season", buildJsonObject { put("year", 2025) })),
            listOf(replace(official(), "competition", buildJsonObject { put("id", 2); put("slug", "segunda-division") })))) {
            val network = server { request -> MockResponse().setBody(if (request.requestUrl!!.encodedPath.endsWith("/subscriptions")) subscriptions() else page("matches", rows)) }
            try {
                val provider = NativeLaLigaProvider(client(network), { at })
                assertTrue(runCatching { provider.officialGame("102249", 2026) }.exceptionOrNull() is IOException)
            } finally { network.shutdown() }
        }
    }

    @Test fun roundsAndPublishedTableKeepOfficialProvenanceWithoutLiveClaims(): Unit = runBlocking {
        val network = server { request -> MockResponse().setBody(when {
            request.requestUrl!!.encodedPath.endsWith("/subscriptions") -> subscriptions()
            request.requestUrl!!.encodedPath.endsWith("/rounds") -> fixture("official-rounds").toString()
            else -> fixture("official-standing").toString()
        }) }
        try {
            val provider = NativeLaLigaProvider(client(network), { at })
            val standings = provider.standings().single(); assertEquals("LaLiga", standings.source); assertEquals(20, standings.rows.size)
            val rounds = provider.rounds(2026); assertEquals(1, rounds.first().week); assertEquals(38, rounds.last().week)
        } finally { network.shutdown() }
    }
}
