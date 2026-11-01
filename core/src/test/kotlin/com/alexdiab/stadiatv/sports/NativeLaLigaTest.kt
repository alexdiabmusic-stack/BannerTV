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

/** Pinned upstream page slices, not complete captures; constructed network envelopes are explicitly miniature. */
class NativeLaLigaTest {
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun server(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply { dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = reply(request)
    }; start() }
    private fun client(server: MockWebServer, now: () -> Long = { at }) = NativeLaLigaClient(HttpGateway(OkHttpClient()), server.url("/public-service").toString(), "constructed-public-site-setting", now)
    @Test fun sourceOfficialMatchHasItsOwnNamespaceRealCrestsNoFakeClockAndBothScoresForAWinner() {
        val raw = fixture("official-matches-finished")["matches"].array.first()
        val game = NativeLaLigaMapping.match(raw, 2026, "resolved-season")!!
        assertEquals("laliga:102249", game.id); assertEquals("laliga:21", game.home.teamId)
        assertEquals("3–0", game.displayScore); assertEquals(GameState.FINAL, game.state); assertTrue(game.home.isWinner)
        assertTrue(game.home.logoUrl!!.startsWith("https://assets.laliga.com/")); assertEquals(LiveContext.EMPTY, game.live)
        assertEquals(Dates.parseIso("2026-08-15T17:30:00Z"), game.startMillis); assertTrue("DAZN" in game.broadcasts)
        assertFalse(NativeLaLigaMapping.match(JsonObject(raw.jsonObject - "away_score"), 2026, "resolved-season")!!.home.isWinner)
        assertNull(NativeLaLigaMapping.match(raw, 2025, "wrong-season"))
    }
    @Test fun sourcePregameHasMissingNotZeroScoresAndInterruptedUnknownStatesRemainHonest() {
        val rows = fixture("official-matches-prematch")["matches"].array
        assertTrue(rows.isNotEmpty()); assertTrue(rows.map { NativeLaLigaMapping.match(it, 2026, "resolved-season")!! }.all { it.home.score == null && it.away.score == null && !it.home.isWinner })
        assertEquals("Suspended", NativeLaLigaMapping.status("Suspended").label)
        assertEquals("NewProviderState", NativeLaLigaMapping.status("NewProviderState").label)
        assertEquals(GameState.PRE, NativeLaLigaMapping.status("penalty").state)
        assertEquals("penalty", NativeLaLigaMapping.status("penalty").label)
        assertNull(NativeLaLigaMapping.rawId("fotmob:60772", true)); assertNull(NativeLaLigaMapping.rawId("laliga:../15"))
        assertNull(NativeLaLigaMapping.rawId("60772", true)); assertEquals("p60772", NativeLaLigaMapping.rawId("laliga:p60772", true))
    }
    @Test fun calendarBoundaryUsesMadridJulyAndInvalidDatesAreNotNormalizedIntoOtherGames() {
        val before = Dates.parseIso("2026-06-30T21:59:00Z")!!
        assertEquals(2025, NativeLaLigaMapping.season(before)); assertEquals(2026, NativeLaLigaMapping.season(before + 120_000))
        assertNull(NativeLaLigaMapping.iso("2026-02-30T12:00:00+00:00")); assertNull(NativeLaLigaMapping.iso("2026-10-23T25:00:00+00:00"))
        assertNull(NativeLaLigaMapping.iso("2026-10-23")); assertNull(NativeLaLigaMapping.iso("2026-10-23T12:00:00Zjunk"))
    }
    @Test fun sourceSquadUsesOptaNotRowOrPersonIdAndRetainsActualBiographyWithoutMislabelledNationality() {
        val rows = fixture("official-squad")["squads"].array
        val players = NativeLaLigaMapping.squad(rows, "laliga:15", at)
        val courtois = players.single { it.id == "laliga:p60772" }
        assertEquals("Thibaut Courtois", courtois.name); assertEquals("Goalkeeper", courtois.position)
        assertEquals("BE", courtois.details); assertEquals("Bree", courtois.birthPlace)
        assertEquals("200 cm", courtois.height); assertEquals("96 kg", courtois.weight); assertEquals(34, courtois.age)
        assertTrue(runCatching { NativeLaLigaMapping.squad(listOf(JsonObject(rows.first().jsonObject - "opta_id")), "laliga:15", at) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeLaLigaMapping.squad(rows, "laliga:21", at) }.exceptionOrNull() is IOException)
        assertTrue(NativeLaLigaMapping.squad(listOf(replace(rows.first(), "current", JsonPrimitive(false))), "laliga:15", at).isEmpty())
    }
    @Test fun publishedStandingsAndRoundCountsComeFromSourceNotHardcodedAssumptions() {
        val table = NativeLaLigaMapping.standings(fixture("official-standing"))
        assertEquals(20, table.rows.size); assertEquals("LaLiga", table.source)
        assertEquals("94", table.rows.first().values["points"]); assertEquals("1", table.rows.first().values["leaguerank"])
        val rounds = NativeLaLigaMapping.rounds(fixture("official-rounds")); assertEquals(38, rounds.size)
        assertEquals(1, rounds.first().week); assertEquals(38, rounds.last().week)
        val short = replace(fixture("official-standing"), "standings", JsonArray(fixture("official-standing")["standings"].array.take(1)))
        assertTrue(runCatching { NativeLaLigaMapping.standings(short) }.exceptionOrNull() is IOException)
    }
    @Test fun playerSeasonMetricsJoinOptaAndPreserveRealZeroAndSignedPerformanceValues() {
        val raw = parseJson("""{"player_stats":[{"id":999,"opta_id":"p60772","stats":[{"name":"appearances","stat":7},{"name":"clean_sheets","stat":0},{"name":"performance_delta","stat":-0.25},{"name":"bad","stat":"NaN"}]}]}""")
        val (all, headline) = NativeLaLigaMapping.playerStatistics(raw, "p60772")!!
        assertEquals("7", headline["Appearances"]); assertEquals("0", headline["Clean Sheets"])
        assertEquals("-0.25", all["Performance Delta"]); assertFalse("Bad" in all)
        assertNull(NativeLaLigaMapping.playerStatistics(raw, "p999"))
    }
    @Test fun completeSquadPaginationUsesActualReturnedOffsetsAndNeverSilentlyTruncates(): Unit = runBlocking {
        val network = server { request ->
            val offset = request.requestUrl!!.queryParameter("offset")!!.toInt()
            val rows = if (offset == 0) (1..20) else (21..34)
            MockResponse().setBody(buildJsonObject { put("total", 34); put("squads", JsonArray(rows.map { buildJsonObject { put("id", it) } })) }.toString())
        }
        try {
            assertEquals(34, client(network).pages("/api/v1/teams/real-madrid/squad", "squads", mapOf("subscription" to "resolved-season")).size)
            val first = network.takeRequest(); val second = network.takeRequest()
            assertEquals("100", first.requestUrl!!.queryParameter("limit")); assertEquals("20", second.requestUrl!!.queryParameter("offset"))
            assertEquals("constructed-public-site-setting", first.getHeader("Ocp-Apim-Subscription-Key"))
        } finally { network.shutdown() }
        val broken = server { MockResponse().setBody("""{"total":34,"squads":[]}""") }
        try { assertTrue(runCatching { client(broken).pages("/api/v1/teams/real-madrid/squad", "squads") }.exceptionOrNull() is IOException) }
        finally { broken.shutdown() }
    }
    @Test fun subscriptionCacheIsKeyedByActualRequestedYearNotTheLastHistoricalLookup(): Unit = runBlocking {
        val network = server { MockResponse().setBody("""{"total":3,"subscriptions":[{"slug":"cup-2026","year":2026,"competition":{"id":3,"slug":"copa-del-rey"}},{"slug":"provider-2025","year":2025,"competition":{"id":1,"slug":"primera-division"}},{"slug":"provider-2026","year":2026,"competition":{"id":1,"slug":"primera-division"}}]}""") }
        try {
            val api = client(network); assertEquals("provider-2025", api.subscription(2025)); assertEquals("provider-2026", api.subscription(2026)); assertEquals("provider-2025", api.subscription(2025))
            assertEquals(1, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun rejectedPublicConfigurationAndRateLimitsStopAllOfficialRequestsWithoutBypass(): Unit = runBlocking {
        for (code in listOf(401, 403, 429)) {
            var clock = at; val network = server { MockResponse().setResponseCode(code).addHeader("Retry-After", "300") }
            try {
                val api = client(network) { clock }; assertTrue(runCatching { api.get("/api/v1/competitions") }.exceptionOrNull() is HttpStatusException)
                assertTrue(runCatching { api.get("/api/v1/subscriptions") }.exceptionOrNull() is IOException); assertEquals(1, network.requestCount)
                clock += 600_001; assertTrue(runCatching { api.get("/api/v1/competitions") }.exceptionOrNull() is HttpStatusException); assertEquals(2, network.requestCount)
            } finally { network.shutdown() }
        }
    }
}
