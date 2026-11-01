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

/** Recorded final/detail shapes; live/outage/correction and clock advances are explicitly constructed. */
class NativeLaLigaServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun original() = fixture("official-matches-finished")["matches"].array.first()
    private fun game(raw: JsonElement = original()) = NativeLaLigaMapping.match(raw, 2026, "verified-2026")!!
    private val start get() = game().startMillis + 3_600_000
    private fun mapping() = NativeLaLigaReconciliation.resolve(game(), listOf(fixture("fotmob-day-matches")))!!
    private fun live(): JsonElement {
        val raw = fixture("fotmob-matchdetails-finished")
        val status = buildJsonObject { put("utcTime", raw["header"]["status"]["utcTime"]!!); put("started", true); put("finished", false); put("liveTime", buildJsonObject { put("minute", 60) }) }
        return replace(raw, "header", replace(raw["header"], "status", status))
    }
    private inner class Harness {
        var clock = start
        var officialRaw: JsonElement = replace(original(), "status", JsonPrimitive("PreMatch"))
        var fmRaw = live()
        var officialCode = 200; var fmCode = 200; var tickerCode = 404
        val folder = temporary.newFolder()
        val cache = NativeLaLigaSnapshotCache(folder, { clock })
        val mappingCache = NativeLaLigaMappingCache(temporary.newFolder(), { clock })
        val network = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/subscriptions") -> MockResponse().setResponseCode(officialCode).setBody("""{"total":1,"subscriptions":[{"slug":"verified-2026","year":2026,"competition":{"id":1,"slug":"primera-division"}}]}""")
                    path.endsWith("/api/v1/matches") -> MockResponse().setResponseCode(officialCode).setBody(buildJsonObject { put("total", 1); put("matches", JsonArray(listOf(officialRaw))) }.toString())
                    path.endsWith("/standing") -> MockResponse().setResponseCode(officialCode).setBody(fixture("official-standing").toString())
                    path.endsWith("/matchDetails") -> MockResponse().setResponseCode(fmCode).setBody(fmRaw.toString())
                    path.endsWith("/matches") -> MockResponse().setBody(fixture("fotmob-day-matches").toString())
                    else -> MockResponse().setResponseCode(tickerCode)
                }
            }
        }; start() }
        val http = HttpGateway(OkHttpClient())
        val provider = NativeLaLigaProvider(NativeLaLigaClient(http, network.url("/public-service").toString(), "constructed-public-setting", { clock }), { clock })
        fun service() = NativeLaLiga(provider, NativeFotMobClient(http, network.url("/").toString(), network.url("/").toString(), { clock }), cache, mappingCache, { clock })
    }
    @Test fun cachedOfficialLookupRetainsActualCatalogueFetchTime(): Unit = runBlocking {
        val h = Harness()
        try {
            val first = h.provider.officialGame("102249", 2026)!!; h.clock += 20_000
            val second = h.provider.officialGame("102249", 2026)!!
            assertEquals(first.checkedAt, second.checkedAt); assertEquals(2, h.network.requestCount)
            h.clock += 300_000
            assertEquals(h.clock, h.provider.officialGame("102249", 2026)!!.checkedAt); assertEquals(3, h.network.requestCount)
        } finally { h.network.shutdown() }
    }
    @Test fun completePipelineUsesVerifiedMappingAndRetainsIndependentlyDatedSurfaces(): Unit = runBlocking {
        val h = Harness()
        try {
            val service = h.service(); val first = service.load(game(h.officialRaw))
            assertEquals(GameState.LIVE, first.liveMatch!!.state); assertEquals("FotMob", first.soccer!!.scoreSource)
            assertEquals("60′", first.liveMatch!!.live.clock); assertEquals(3, first.plays.count { it.isScoring })
            assertEquals(24, first.soccer!!.shots.size); assertTrue(first.soccer!!.lineups.isNotEmpty()); assertTrue(first.teamStats.isNotEmpty())
            assertTrue(first.soccer!!.errors.containsKey("commentary")); assertTrue(first.soccer!!.surfaceSources.filterKeys { it != "officials" }.values.all { it == "FotMob" })
            assertEquals("LaLiga", first.soccer!!.surfaceSources["officials"])
            val initial = first.soccer!!.surfaceUpdatedAt
            h.clock += 20_000
            val second = service.load(game(h.officialRaw), first, tab = "Timeline")
            assertEquals(h.clock, second.soccer!!.surfaceUpdatedAt["events"])
            assertEquals(initial["statistics"], second.soccer!!.surfaceUpdatedAt["statistics"])
            assertEquals(initial["shots"], second.soccer!!.surfaceUpdatedAt["shots"])
            assertEquals(initial["lineups"], second.soccer!!.surfaceUpdatedAt["lineups"])
            assertEquals(initial["overview"]!! + 20_000, second.soccer!!.scoreUpdatedAt)
            assertEquals(mapping(), h.mappingCache.load(game(h.officialRaw)))
        } finally { h.network.shutdown() }
    }
    @Test fun detailOutageCannotFlipBackToLaggingPregameOrRedateScoreAndResources(): Unit = runBlocking {
        val h = Harness()
        try {
            val service = h.service(); val first = service.load(game(h.officialRaw)); h.clock += 20_000; h.fmCode = 503
            val failed = service.load(game(h.officialRaw), first, force = true)
            assertEquals(first.liveMatch, failed.liveMatch); assertEquals(first.soccer!!.scoreUpdatedAt, failed.soccer!!.scoreUpdatedAt)
            assertEquals(first.soccer!!.surfaceUpdatedAt, failed.soccer!!.surfaceUpdatedAt); assertEquals(first.plays, failed.plays)
            assertTrue(failed.soccer!!.errors.containsKey("fotmob"))
            assertTrue(failed.soccer!!.recoverableCoreFailure)
            assertEquals(first.soccer!!.scoreUpdatedAt, h.cache.load("102249")!!.details()!!.soccer!!.scoreUpdatedAt)
        } finally { h.network.shutdown() }
    }
    @Test fun officialOutageStillAcceptsIndependentlyVerifiedDetailAndOfficialFinalWinsThereafter(): Unit = runBlocking {
        val h = Harness()
        try {
            val service = h.service(); val first = service.load(game(h.officialRaw)); h.clock += 301_000; h.officialCode = 503
            val next = service.load(game(h.officialRaw), first)
            assertEquals(GameState.LIVE, next.liveMatch!!.state); assertEquals(h.clock, next.soccer!!.scoreUpdatedAt)
            assertTrue(next.soccer!!.errors.containsKey("official")); assertFalse(next.soccer!!.savedSnapshot)
            h.clock += 301_000; h.officialCode = 200; h.officialRaw = original()
            val final = service.load(game(h.officialRaw), next)
            assertEquals(GameState.FINAL, final.liveMatch!!.state); assertEquals("LaLiga", final.soccer!!.scoreSource)
            val finalAt = final.soccer!!.scoreUpdatedAt
            h.clock += 20_000
            val newerDetail = service.load(game(), final)
            assertEquals(GameState.FINAL, newerDetail.liveMatch!!.state)
            assertEquals(finalAt, newerDetail.soccer!!.scoreUpdatedAt) // Cached official final, newer detail envelope.
            assertEquals(finalAt, newerDetail.soccer!!.surfaceUpdatedAt["overview"])
        } finally { h.network.shutdown() }
    }
    @Test fun coldOfflineSnapshotRestoresAuthorityResourcesAndOriginalTimesWithoutNetwork(): Unit = runBlocking {
        val h = Harness()
        try {
            val first = h.service().load(game(h.officialRaw)); h.clock += 1000
            val cold = NativeLaLigaSnapshotCache(h.folder, { h.clock }).load("102249")!!
            val centre = cold.details(game(h.officialRaw))!!
            assertEquals(first.liveMatch, centre.liveMatch); assertEquals(first.plays, centre.plays)
            assertEquals(first.soccer!!.shots, centre.soccer!!.shots); assertEquals(first.soccer!!.surfaceUpdatedAt, centre.soccer!!.surfaceUpdatedAt)
            assertTrue(centre.soccer!!.savedSnapshot); assertEquals(first.soccer!!.scoreUpdatedAt, centre.soccer!!.scoreUpdatedAt)
            assertNull(cold.details(game().copy(home = game().away)))
            assertFalse(File(h.folder, "102249.pending").exists()); assertTrue(File(h.folder, "102249.json").length() < 2 * 1024 * 1024)
        } finally { h.network.shutdown() }
    }
    @Test fun corruptionForeignIdentityExpiredFutureAndTraversalSnapshotsAreRejected(): Unit = runBlocking {
        val h = Harness()
        try {
            h.service().load(game(h.officialRaw)); val file = File(h.folder, "102249.json"); val body = file.readText()
            assertNull(h.cache.load("../102249"))
            h.clock -= 300_001; assertNull(h.cache.load("102249")); h.clock += 300_001 + 31L * 86_400_000
            assertNull(h.cache.load("102249")); h.clock = start
            file.writeText(body.replace("\"provider\":\"LaLiga\"", "\"provider\":\"ESPN\"")); assertNull(h.cache.load("102249"))
            file.writeText("{malformed"); assertNull(h.cache.load("102249"))
            file.writeText(body.replace("\"fmHome\":\"9866\"", "\"fmHome\":\"9999\"")); assertNull(h.cache.load("102249"))
        } finally { h.network.shutdown() }
    }
    @Test fun historicalUnresolvedFinalDoesNotInventAnEnrichmentMapping(): Unit = runBlocking {
        val h = Harness(); h.clock += 2 * 86_400_000; h.officialRaw = original()
        try {
            val result = h.service().load(game())
            assertEquals(GameState.FINAL, result.liveMatch!!.state); assertEquals("LaLiga", result.soccer!!.scoreSource)
            assertTrue(result.soccer!!.errors.containsKey("fotmob")); assertEquals(2, h.network.requestCount)
            assertFalse(result.soccer!!.recoverableCoreFailure)
            assertTrue(result.plays.isEmpty()); assertTrue(result.soccer!!.shots.isEmpty())
        } finally { h.network.shutdown() }
    }
    @Test fun wrongDetailIdentityNeverReplacesAcceptedResourcesAndRetryCannotBypassDenial(): Unit = runBlocking {
        val h = Harness()
        try {
            val service = h.service(); val first = service.load(game(h.officialRaw)); h.clock += 20_000
            h.fmRaw = replace(h.fmRaw, "general", replace(h.fmRaw["general"], "matchId", JsonPrimitive(999)))
            val failed = service.load(game(h.officialRaw), first)
            assertEquals(first.liveMatch, failed.liveMatch); assertEquals(first.plays, failed.plays)
            assertTrue(failed.soccer!!.errors.containsKey("fotmob"))
            h.clock += 61_000; h.fmCode = 403
            service.load(game(h.officialRaw), failed); val count = h.network.requestCount
            service.onNetworkRestored(); service.load(game(h.officialRaw), failed, force = true)
            assertEquals(count, h.network.requestCount)
        } finally { h.network.shutdown() }
    }
    @Test fun publishedTableRetainsActualFetchTimeAndNeverClaimsToBeALiveSimulation(): Unit = runBlocking {
        val h = Harness()
        try {
            val first = h.provider.table(game()); h.clock += 20_000
            val second = h.provider.table(game())
            assertFalse(first.provisional); assertEquals(20, first.rows.size); assertEquals(first.checkedAt, second.checkedAt)
            assertTrue(first.rows.all { it.id.startsWith("laliga:") }); assertEquals(2, h.network.requestCount)
        } finally { h.network.shutdown() }
    }
    @Test fun malformedShotResourceCannotDiscardGoodTimelineStatisticsOrPreviouslyAcceptedShots(): Unit = runBlocking {
        val h = Harness()
        try {
            val service = h.service(); val first = service.load(game(h.officialRaw)); h.clock += 20_000
            val rows = h.fmRaw["content"]["shotmap"]["shots"].array
            val bad = JsonArray(rows.mapIndexed { index, shot -> if (index == 0) replace(shot, "teamId", JsonPrimitive(9999)) else shot })
            h.fmRaw = replace(h.fmRaw, "content", replace(h.fmRaw["content"], "shotmap", replace(h.fmRaw["content"]["shotmap"], "shots", bad)))
            val next = service.load(game(h.officialRaw), first, force = true)
            assertEquals(first.soccer!!.shots, next.soccer!!.shots)
            assertEquals(first.soccer!!.surfaceUpdatedAt["shots"], next.soccer!!.surfaceUpdatedAt["shots"])
            assertEquals(h.clock, next.soccer!!.surfaceUpdatedAt["events"]); assertEquals(h.clock, next.soccer!!.surfaceUpdatedAt["statistics"])
            assertTrue(next.soccer!!.errors.containsKey("shots")); assertTrue(next.plays.isNotEmpty()); assertTrue(next.teamStats.isNotEmpty())
        } finally { h.network.shutdown() }
    }
    @Test fun officialAssignmentsLoadAndRestoreIndependentlyOfAnUnresolvedEnrichmentSource(): Unit = runBlocking {
        val h = Harness(); h.officialRaw = fixture("official-live-20261024"); h.clock = game(h.officialRaw).startMillis + 7_200_000
        try {
            val service = h.service(); val result = service.load(game(h.officialRaw))
            assertEquals(6, result.soccer!!.officials.size); assertEquals("LaLiga", result.soccer!!.surfaceSources["officials"])
            val checked = result.soccer!!.surfaceUpdatedAt["officials"]
            assertEquals(h.clock, checked); h.clock += 20_000
            val second = service.load(game(h.officialRaw), result)
            assertEquals(checked, second.soccer!!.surfaceUpdatedAt["officials"])
            val restored = h.cache.load("102344")!!.details()!!
            assertEquals(result.soccer!!.officials, restored.soccer!!.officials)
            assertEquals(checked, restored.soccer!!.surfaceUpdatedAt["officials"])
        } finally { h.network.shutdown() }
    }
    @Test fun temporarilyAllNullPressureCannotEraseAcceptedHistoryOrMakeTheHealthyScoreAFailedRefresh(): Unit = runBlocking {
        val h = Harness()
        try {
            val service = h.service(); val first = service.load(game(h.officialRaw)); assertTrue(first.soccer!!.pressure.isNotEmpty())
            h.clock += 20_000
            val samples = h.fmRaw["content"]["momentum"]["main"]["data"].array.map { replace(it, "value", JsonNull) }
            h.fmRaw = replace(h.fmRaw, "content", replace(h.fmRaw["content"], "momentum", replace(h.fmRaw["content"]["momentum"], "main", replace(h.fmRaw["content"]["momentum"]["main"], "data", JsonArray(samples)))))
            val next = service.load(game(h.officialRaw), first, force = true)
            assertEquals(first.soccer!!.pressure, next.soccer!!.pressure)
            assertEquals(first.soccer!!.surfaceUpdatedAt["pressure"], next.soccer!!.surfaceUpdatedAt["pressure"])
            assertTrue(next.soccer!!.errors.containsKey("pressure")); assertFalse(next.soccer!!.recoverableCoreFailure)
            assertEquals(h.clock, next.soccer!!.scoreUpdatedAt)
        } finally { h.network.shutdown() }
    }
}
