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
import java.io.IOException
import com.alexdiab.stadiatv.TestHttp

/** Recorded public NFL game with explicitly constructed score corrections and outages. No external network. */
class NativeFootballRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val week = NflWeek(2025, NflSeasonType.REG, 1)
    private val row get() = NflCalendar.rows(parseJson(javaClass.getResource("/parity/nfl-regular-final.json")!!.readText())).single()
    private val id get() = row["id"].string()
    private fun replace(raw: JsonElement, key: String, value: JsonElement) = JsonObject(raw.jsonObject + (key to value))
    private fun live(offset: Int = 480): JsonElement = replace(row, "summary", JsonObject(row["summary"]!!.jsonObject + mapOf(
        "phase" to JsonPrimitive("IN_PROGRESS"), "quarter" to JsonPrimitive("Q4"), "offset" to JsonPrimitive(offset))))
    private fun match(raw: JsonElement = live()) = NflCalendar.matches(JsonArray(listOf(raw)), week).single()
    private fun score(raw: JsonElement, offset: Int = 481, total: Int = 30, phase: String = "IN_PROGRESS"): JsonElement {
        val summary = raw["summary"]!!
        val home = summary["homeTeam"]!!
        return JsonObject(summary.jsonObject + mapOf("offset" to JsonPrimitive(offset), "phase" to JsonPrimitive(phase),
            "homeTeam" to replace(home, "score", replace(home["score"]!!, "total", JsonPrimitive(total)))))
    }
    private fun summaries(summary: JsonElement) = buildJsonObject { put("data", JsonArray(listOf(summary))) }
    private fun rich(raw: JsonElement) = JsonArray(listOf(raw))
    private fun unavailable(): Nothing = throw IOException("Constructed outage")
    private fun snapshot(raw: JsonElement = live(), summaryAt: Long = 1000, detailsAt: Long = 1000) =
        NativeFootballSnapshotCache.Snapshot(raw, summaryAt, detailsAt, mapOf("test-person" to "Reported Name"))

    @Test fun acceptedSnapshotRecoversColdNativeIdentityAndOriginalSurfaceTimes(): Unit = runBlocking {
        val cache = NativeFootballSnapshotCache(temporary.newFolder(), { 4000 })
        cache.save(id, snapshot(summaryAt = 3000))
        val saved = cache.load(id)!!
        assertEquals("nfl:$id", saved.match()!!.id)
        assertEquals("NFL", saved.match()!!.nativeReference!!.provider)
        val data = saved.details(saved.match()!!)!!
        assertTrue(data.plays.isNotEmpty()); assertTrue(data.boxScore.isNotEmpty())
        assertEquals(3000L, data.providerStamp!!.requestedAt)
        assertEquals(3000L, data.football!!.summaryUpdatedAt); assertEquals(1000L, data.football!!.detailsUpdatedAt)
        assertTrue(data.football!!.savedSnapshot); assertEquals("Reported Name", saved.players["test-person"])
    }

    @Test fun cacheRejectsForeignGameCorruptTimesTraversalAndOlderReplacement(): Unit = runBlocking {
        val directory = temporary.newFolder(); var now = 4000L
        val cache = NativeFootballSnapshotCache(directory, { now })
        cache.save(id, snapshot(summaryAt = 3000))
        cache.save(id, snapshot(summaryAt = 2000)); assertEquals(3000L, cache.load(id)!!.summaryAt)
        cache.save("other-game", snapshot()); assertNull(cache.load("other-game"))
        cache.save("../escape", snapshot()); assertNull(cache.load("../escape"))
        val file = File(directory, "$id.json"); val accepted = parseJson(file.readText())
        for ((key, value) in listOf("gameId" to JsonPrimitive("foreign"), "summaryAt" to JsonPrimitive(0),
            "detailsAt" to JsonPrimitive(4001), "summaryAt" to JsonPrimitive(400_000))) {
            file.writeText(replace(accepted, key, value).toString()); assertNull("Invalid $key", cache.load(id))
        }
        file.writeText(accepted.toString()); now += 31L * 24 * 3_600_000
        assertNull(cache.load(id))
    }

    @Test fun cacheIsBoundedToTwelveGamesAndDoesNotPersistBearerMetadata(): Unit = runBlocking {
        val directory = temporary.newFolder(); val cache = NativeFootballSnapshotCache(directory, { 4000 })
        repeat(16) { number ->
            val key = "game-$number"
            val base = row
            val updated = JsonObject(base.jsonObject + mapOf("id" to JsonPrimitive(key),
                "summary" to replace(base["summary"]!!, "gameId", JsonPrimitive(key)),
                "driveChart" to replace(base["driveChart"]!!, "gameId", JsonPrimitive(key))))
            cache.save(key, snapshot(updated))
        }
        val files = directory.listFiles()!!.filter { it.extension == "json" }
        assertEquals(12, files.size); assertTrue(files.sumOf { it.length() } <= 24L * 1024 * 1024)
        assertTrue(files.none { it.readText().contains("Bearer ") }); assertTrue(directory.listFiles()!!.none { it.extension == "pending" })
    }

    @Test fun freshDetailsUseLightweightScoresWithoutReAgingOrErasingDriveDetails(): Unit = runBlocking {
        var at = 1000L; var richCalls = 0; var summaryCalls = 0
        val loader = NativeFootballLoader({ at }, null, { richCalls++; rich(live()) },
            { summaryCalls++; summaries(score(live())) }, { emptyMap() })
        val original = loader.load(match(), week, id, null)!!
        at = 7000
        val updated = loader.load(match(), week, id, original)!!
        assertEquals(1, richCalls); assertEquals(1, summaryCalls)
        assertEquals("30", updated.liveMatch!!.home.score)
        assertEquals(original.plays, updated.plays); assertEquals(original.boxScore, updated.boxScore)
        assertEquals(7000L, updated.football!!.summaryUpdatedAt); assertEquals(1000L, updated.football!!.detailsUpdatedAt)
        assertEquals(481, updated.providerStamp!!.sequence)
    }

    @Test fun richOutageStillAcceptsExactSummaryAndRetainsOriginalDetailFreshness(): Unit = runBlocking {
        val cache = NativeFootballSnapshotCache(temporary.newFolder(), { 20_000 })
        cache.save(id, snapshot())
        var richCalls = 0
        val loader = NativeFootballLoader({ 20_000 }, cache, { richCalls++; unavailable() },
            { summaries(score(live())) }, { emptyMap() })
        val data = loader.load(match(), week, id, null)!!
        assertEquals(1, richCalls); assertEquals("30", data.liveMatch!!.home.score)
        assertEquals(20_000L, data.football!!.summaryUpdatedAt); assertEquals(1000L, data.football!!.detailsUpdatedAt)
        assertTrue(data.football!!.errors.containsKey("plays")); assertFalse(data.football!!.savedSnapshot)
        val recovered = cache.load(id)!!.details(match())!!
        assertEquals("30", recovered.liveMatch!!.home.score); assertEquals(data.plays, recovered.plays)
        assertEquals(1000L, recovered.football!!.detailsUpdatedAt)
    }

    @Test fun wrongGameTeamAndOlderSequenceSummariesCannotReplaceAcceptedState(): Unit = runBlocking {
        val cache = NativeFootballSnapshotCache(temporary.newFolder(), { 20_000 }); cache.save(id, snapshot())
        val base = score(live())
        val foreignTeam = replace(base, "homeTeam", replace(base["homeTeam"]!!, "teamId", JsonPrimitive("other-team")))
        for (summary in listOf(replace(base, "gameId", JsonPrimitive("foreign")), foreignTeam, score(live(), offset = 479))) {
            val loader = NativeFootballLoader({ 20_000 }, cache, { unavailable() }, { summaries(summary) }, { emptyMap() })
            val data = loader.load(match(), week, id, null)!!
            assertEquals("24", data.liveMatch!!.home.score); assertEquals(480, data.providerStamp!!.sequence)
            assertEquals(1000L, data.football!!.summaryUpdatedAt); assertTrue(data.football!!.savedSnapshot)
        }
    }

    @Test fun finalSummaryTriggersRichReconciliationAndCannotRollBackToLive(): Unit = runBlocking {
        var at = 1000L; var fullCalls = 0
        val loader = NativeFootballLoader({ at }, null, { fullCalls++; if (fullCalls == 1) rich(live()) else unavailable() },
            { summaries(score(live(), offset = if (at > 7000) 482 else 481, phase = if (at > 7000) "IN_PROGRESS" else "FINAL")) }, { emptyMap() })
        val original = loader.load(match(), week, id, null)!!; at = 7000
        val final = loader.load(match(), week, id, original)!!
        assertEquals(2, fullCalls); assertTrue(final.providerStamp!!.final); assertTrue(final.liveMatch!!.home.isWinner)
        assertEquals(LiveContext.EMPTY, final.liveMatch!!.live); assertNull(final.football!!.field)
        assertEquals(original.plays, final.plays); assertEquals(1000L, final.football!!.detailsUpdatedAt)
        at = 30_000
        val retry = loader.load(match(), week, id, final)!!
        assertTrue(retry.providerStamp!!.final); assertEquals(7000L, retry.football!!.summaryUpdatedAt)
    }

    @Test fun missingRichPlayPackageKeepsOldDrivesWithoutClaimingNewDetailTime(): Unit = runBlocking {
        var at = 1000L
        val loader = NativeFootballLoader({ at }, null, { rich(if (at == 1000L) live() else JsonObject(live(481).jsonObject - "driveChart")) },
            { unavailable() }, { emptyMap() })
        val original = loader.load(match(), week, id, null)!!; at = 20_000
        val updated = loader.load(match(), week, id, original)!!
        assertEquals(original.plays, updated.plays); assertEquals(1000L, updated.football!!.detailsUpdatedAt)
        assertEquals(20_000L, updated.football!!.summaryUpdatedAt); assertTrue(updated.football!!.errors.containsKey("plays"))
    }

    @Test fun coldCalendarAndAllFailedSurfacesRecoverSavedDataWithoutReAging(): Unit = runBlocking {
        val cache = NativeFootballSnapshotCache(temporary.newFolder(), { 20_000 }); cache.save(id, snapshot())
        val loader = NativeFootballLoader({ 20_000 }, cache, { unavailable() }, { unavailable() }, { emptyMap() })
        assertEquals("nfl:$id", loader.cachedMatch(id)!!.id)
        val data = loader.load(loader.cachedMatch(id)!!, week, id, null)!!
        assertEquals(1000L, data.providerStamp!!.requestedAt); assertEquals(1000L, data.football!!.summaryUpdatedAt)
        assertEquals(1000L, data.football!!.detailsUpdatedAt); assertTrue(data.football!!.savedSnapshot)
        assertTrue(data.football!!.errors.keys.containsAll(listOf("plays", "overview")))
        assertNull(loader.cachedMatch("not-cached"))
    }

    @Test fun wrongRichGameOrForeignTeamCannotEnterTheCache(): Unit = runBlocking {
        val cache = NativeFootballSnapshotCache(temporary.newFolder(), { 1000 })
        for (raw in listOf(replace(live(), "id", JsonPrimitive("foreign")),
            replace(live(), "homeTeam", replace(live()["homeTeam"]!!, "id", JsonPrimitive("foreign-team"))))) {
            val loader = NativeFootballLoader({ 1000 }, cache, { rich(raw) }, { unavailable() }, { emptyMap() })
            assertNull(loader.load(match(), week, id, null)); assertNull(cache.load(id))
        }
    }

    @Test fun manualRetryRefreshesRichDetailsImmediatelyAndNamesAreAnOptionalSurface(): Unit = runBlocking {
        var at = 1000L; var richCalls = 0
        val loader = NativeFootballLoader({ at }, null, { richCalls++; rich(live()) }, { unavailable() }, { unavailable() })
        val first = loader.load(match(), week, id, null)!!
        assertTrue(first.plays.isNotEmpty()) // GSIS-provided names remain usable when the roster directory fails.
        at = 2000
        val retry = loader.load(match(), week, id, first, force = true)!!
        assertEquals(2, richCalls); assertEquals(2000L, retry.football!!.detailsUpdatedAt)
    }

    @Test fun cachedCalendarAndDetailsAreAvailableBeforeAnyNetworkRequest(): Unit = runBlocking {
        val cache = NativeFootballSnapshotCache(temporary.newFolder(), { 20_000 }); cache.save(id, snapshot())
        var networkCalls = 0
        val client = NativeFootball(TestHttp { networkCalls++; throw IOException("No external requests") },
            now = { 20_000 }, snapshots = cache)
        val cold = client.calendarGame("nfl:$id", 1_757_031_600_000)!!
        assertEquals("nfl:$id", cold.id); assertEquals(1000L, client.cachedGame(cold)!!.football!!.detailsUpdatedAt)
        assertEquals(0, networkCalls)
        assertNull(client.cachedGame(cold.copy(nativeReference = NativeGameReference("MLB", "744834"))))
    }
    @Test fun pregamePlaceholderTotalsAreNotPresentedAsAnActualZeroScore() {
        val raw = replace(live(), "summary", JsonObject(row["summary"]!!.jsonObject + ("phase" to JsonPrimitive("PREGAME"))))
        val data = NativeFootball.parse(raw, match(), 1000)!!
        assertEquals(GameState.PRE, data.liveMatch!!.state); assertNull(data.liveMatch!!.away.score); assertNull(data.liveMatch!!.home.score)
        assertFalse(data.liveMatch!!.home.isWinner); assertFalse(data.liveMatch!!.away.isWinner)
        assertNull(data.lineScore)
    }
    @Test fun halftimeAndSuspensionDoNotAdvertiseAnActiveDriveButDelayedRetainsIt() {
        val raw = live()
        val drives = raw["driveChart"]["drives"].array
        val current = JsonObject(drives.last().jsonObject + mapOf("endedDescription" to JsonPrimitive(""), "endedWithScore" to JsonPrimitive(false)))
        val active = replace(raw, "driveChart", replace(raw["driveChart"]!!, "drives", JsonArray(drives.dropLast(1) + current)))
        for (phase in listOf("IN_PROGRESS", "HALFTIME", "DELAYED", "SUSPENDED", "FINAL")) {
            val updated = replace(active, "summary", replace(active["summary"]!!, "phase", JsonPrimitive(phase)))
            val data = NativeFootball.parse(updated, match(), 1000)!!
            if (phase in listOf("IN_PROGRESS", "DELAYED")) assertNotNull(phase, data.football!!.currentDriveId)
            else assertNull(phase, data.football!!.currentDriveId)
        }
    }
}
