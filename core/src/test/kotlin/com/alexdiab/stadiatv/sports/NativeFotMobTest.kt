package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPOutputStream

class NativeFotMobTest {
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun game() = NativeLaLigaMapping.match(fixture("official-matches-finished")["matches"].array.first(), 2026, "verified-2026")!!
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun tickerBytes() = Base64.getDecoder().decode(javaClass.getResource("/parity/laliga-fotmob-ticker-finished.source.base64")!!.readText().trim())
    private fun tickerDetails() = buildJsonObject { put("content", buildJsonObject { put("liveticker", buildJsonObject { put("langs", "es_gen,en_gen"); put("ltcUrl", JsonNull) }) }) }
    private fun server(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }; start() }
    private fun client(server: MockWebServer, now: () -> Long = { at }) = NativeFotMobClient(HttpGateway(OkHttpClient()), server.url("/").toString(), server.url("/").toString(), now)
    private fun daily(rows: List<JsonElement>, league: Int = 87) = buildJsonObject { put("leagues", buildJsonArray { add(buildJsonObject { put("id", league); put("matches", JsonArray(rows)) }) }) }
    private fun candidate(): JsonElement = fixture("fotmob-day-matches")["leagues"].array.filter { it["id"].string() == "87" || it["primaryId"].string() == "87" }
        .flatMap { it["matches"].array }.single { it["id"].string() == "5868011" }

    @Test fun actualFormalSadNamesResolveThroughBothClubsAndKickoffWithoutUsingScores() {
        val mapping = NativeLaLigaReconciliation.resolve(game(), listOf(fixture("fotmob-day-matches")))!!
        assertEquals("102249", mapping.officialId); assertEquals("5868011", mapping.fotmobId)
        assertEquals("9866", mapping.fotmobHome); assertEquals("8305", mapping.fotmobAway); assertTrue(mapping.matches(game()))
        NativeLaLigaReconciliation.validate(fixture("fotmob-matchdetails-finished"), mapping, game())
        val contradictoryScores = game().copy(home = game().home.copy(score = "99"), away = game().away.copy(score = "98"))
        assertEquals(mapping, NativeLaLigaReconciliation.resolve(contradictoryScores, listOf(fixture("fotmob-day-matches"))))
        assertEquals("getafe", NativeLaLigaReconciliation.normalize("Getafe Club de Fútbol S.A.D."))
    }
    @Test fun ambiguousReversedForeignCompetitionAndOutsideWindowNeverGuessAMatch() {
        val row = candidate(); val extra = replace(row, "id", JsonPrimitive("5868012"))
        assertNull(NativeLaLigaReconciliation.resolve(game(), listOf(daily(listOf(row, extra)))))
        assertNull(NativeLaLigaReconciliation.resolve(game(), listOf(daily(listOf(row), 88))))
        assertNull(NativeLaLigaReconciliation.resolve(game().copy(home = game().away, away = game().home), listOf(daily(listOf(row)))))
        assertNull(NativeLaLigaReconciliation.resolve(game().copy(startMillis = game().startMillis + 6 * 3_600_000L + 1), listOf(daily(listOf(row)))))
        assertNotNull(NativeLaLigaReconciliation.resolve(game(), listOf(daily(listOf(row)), daily(listOf(row)))))
    }
    @Test fun inconsistentDuplicateCandidatesAndMalformedScopedRowsAreNotSilentlyIgnored() {
        val row = candidate()
        val changed = replace(row, "home", replace(row["home"], "id", JsonPrimitive(123)))
        assertTrue(runCatching { NativeLaLigaReconciliation.resolve(game(), listOf(daily(listOf(row)), daily(listOf(changed)))) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeLaLigaReconciliation.resolve(game(), listOf(daily(listOf(JsonObject(row.jsonObject - "status"))))) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeLaLigaReconciliation.resolve(game(), listOf(buildJsonObject {})) }.exceptionOrNull() is IOException)
    }
    @Test fun detailRevalidatesMatchClubOrderLeagueSeasonAndKickoffEvenAfterMapping() {
        val mapping = NativeLaLigaReconciliation.resolve(game(), listOf(daily(listOf(candidate()))))!!
        val raw = fixture("fotmob-matchdetails-finished")
        val wrongs = listOf(replace(raw, "general", replace(raw["general"], "matchId", JsonPrimitive("5868012"))),
            replace(raw, "general", replace(raw["general"], "leagueId", JsonPrimitive(88))),
            replace(raw, "general", replace(raw["general"], "homeTeam", replace(raw["general"]["homeTeam"], "id", JsonPrimitive(1)))),
            replace(raw, "header", replace(raw["header"], "teams", JsonArray(raw["header"]["teams"].array.reversed()))),
            replace(raw, "header", replace(raw["header"], "status", replace(raw["header"]["status"], "utcTime", JsonPrimitive("2026-08-16T17:30:00.000Z")))))
        for (wrong in wrongs) assertTrue(runCatching { NativeLaLigaReconciliation.validate(wrong, mapping, game()) }.exceptionOrNull() is IOException)
        assertFalse(mapping.matches(game().copy(nativeReference = game().nativeReference!!.copy(season = 2025))))
        assertFalse(mapping.copy(officialHome = "epl:21").matches(game()))
    }
    @Test fun actualHeaderlessGzipTickerAndAlreadyDecodedJsonHaveTheSameReportedPayload() {
        val decoded = NativeFotMobClient.decodeTicker(tickerBytes())
        assertEquals("9866", decoded["HometeamId"].string()); assertEquals("8305", decoded["AwayteamId"].string())
        assertEquals("2955299923", decoded["Events"].array.first()["MessageId"].string())
        assertTrue(decoded["Events"].array.any { it["Elapsed"].string() == "90" && it["ElapsedPlus"].string() == "9" })
        assertEquals(decoded, NativeFotMobClient.decodeTicker(decoded.toString().toByteArray()))
        assertEquals("https://data.fotmob.com/webcl/ltc/gsm/5868011_en_gen.json.gz", NativeFotMobClient.tickerUrl("5868011", tickerDetails()))
    }
    @Test fun tickerRejectsForeignHostCredentialsQueryTraversalAndWrongGameBeforeFetching(): Unit = runBlocking {
        val network = server { MockResponse().setBody("{}") }
        try {
            for (url in listOf("http://data.fotmob.com/webcl/ltc/gsm/5868011_en_gen.json.gz", "https://example.com/webcl/ltc/gsm/5868011_en_gen.json.gz",
                "https://user@data.fotmob.com/webcl/ltc/gsm/5868011_en_gen.json.gz", "https://data.fotmob.com/webcl/ltc/gsm/5868012_en_gen.json.gz",
                "https://data.fotmob.com/webcl/ltc/gsm/5868011_en_gen.json.gz?key=constructed", "https://data.fotmob.com/webcl/ltc/gsm/../5868011_en_gen.json.gz")) {
                val raw = replace(tickerDetails(), "content", replace(tickerDetails()["content"], "liveticker", replace(tickerDetails()["content"]["liveticker"], "ltcUrl", JsonPrimitive(url))))
                assertTrue(runCatching { client(network).ticker("5868011", raw) }.exceptionOrNull() is IOException)
            }
            assertEquals(0, network.requestCount)
            assertNull(client(network).ticker("5868011", buildJsonObject {})); assertEquals(0, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun tickerBombCorruptionAndHtmlAreBoundedFailuresNotSuccessfulEmptyCommentary() {
        val output = ByteArrayOutputStream(); GZIPOutputStream(output).use { it.write(ByteArray(4 * 1024 * 1024 + 1) { 32 }) }
        assertTrue(runCatching { NativeFotMobClient.decodeTicker(output.toByteArray()) }.exceptionOrNull() is ResponseTooLargeException)
        assertTrue(runCatching { NativeFotMobClient.decodeTicker(byteArrayOf(0x1f, 0x8b.toByte(), 0)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeFotMobClient.decodeTicker("<html>blocked</html>".toByteArray()) }.exceptionOrNull() is IOException)
    }
    @Test fun realGzipIsFetchedOnExactAnnouncedLanguagePathWithoutFakeBrowserHeaders(): Unit = runBlocking {
        val network = server { MockResponse().setBody(Buffer().write(tickerBytes())) }
        try {
            val data = client(network).ticker("5868011", tickerDetails())!!
            assertEquals("9866", data["HometeamId"].string())
            val request = network.takeRequest(); assertEquals("/webcl/ltc/gsm/5868011_en_gen.json.gz", request.path)
            assertEquals("BannerTV/1.0", request.getHeader("User-Agent")); assertEquals(1, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun tickerDenialIsIndependentOfDetailsButSharedWithinCommentary(): Unit = runBlocking {
        var clock = at
        val network = server { request -> if (request.requestUrl!!.encodedPath.startsWith("/webcl/")) MockResponse().setResponseCode(403) else MockResponse().setBody("{}") }
        try {
            val api = client(network) { clock }
            assertTrue(runCatching { api.ticker("5868011", tickerDetails()) }.exceptionOrNull() is HttpStatusException)
            api.details("5868011"); assertEquals(2, network.requestCount)
            assertTrue(runCatching { api.ticker("5868012", tickerDetails()) }.exceptionOrNull() is IOException); assertEquals(2, network.requestCount)
            clock += 300_001; assertTrue(runCatching { api.ticker("5868011", tickerDetails()) }.exceptionOrNull() is HttpStatusException); assertEquals(3, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun mainGatewayDenialBlocksOtherMainRoutesWithoutRetriesOrBypass(): Unit = runBlocking {
        var clock = at; val network = server { MockResponse().setResponseCode(429).addHeader("Retry-After", "300") }
        try {
            val api = client(network) { clock }
            assertTrue(runCatching { api.details("5868011") }.exceptionOrNull() is HttpStatusException)
            assertTrue(runCatching { api.day("20260815") }.exceptionOrNull() is IOException); assertEquals(1, network.requestCount)
            clock += 300_001; assertTrue(runCatching { api.day("20260815") }.exceptionOrNull() is HttpStatusException); assertEquals(2, network.requestCount)
        } finally { network.shutdown() }
    }
}
