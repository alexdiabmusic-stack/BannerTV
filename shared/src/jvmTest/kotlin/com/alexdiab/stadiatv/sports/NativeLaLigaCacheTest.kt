package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.NativeLaLigaSnapshotCache.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeLaLigaCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private val at = 1_792_800_000_000L
    private fun official(id: Int = 102249, time: Long = at): NativeLaLigaProvider.OfficialGame {
        val raw = JsonObject(fixture("official-matches-finished")["matches"].array.first().jsonObject + ("id" to JsonPrimitive(id)))
        return NativeLaLigaProvider.OfficialGame(NativeLaLigaMapping.match(raw, 2026, "verified-2026")!!, raw, time)
    }
    private fun snapshot(id: Int = 102249, time: Long = at): Snapshot {
        val official = official(id, time)
        return Snapshot(official, null, Evidence(official), null)
    }
    @Test fun richGameCacheHasTwelveSlotsAndNeverStoresTraversalOrForeignResources(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeLaLigaSnapshotCache(folder, { at + 1000 })
        for (id in 102249..102263) cache.save("$id", snapshot(id))
        assertEquals(12, folder.listFiles()!!.count { it.extension == "json" })
        assertTrue(folder.listFiles()!!.sumOf { it.length() } <= 12L * 1024 * 1024)
        cache.save("../102249", snapshot()); assertNull(cache.load("../102249"))
        cache.save("200000", snapshot(200000).copy(surfaces = mapOf("playlist" to Surface(at, JsonObject(emptyMap())))))
        assertNull(cache.load("200000"))
    }
    @Test fun oversizedNewEvidenceCannotDestroyAnExistingAcceptedSnapshot(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeLaLigaSnapshotCache(folder, { at + 1000 }); cache.save("102249", snapshot())
        val good = File(folder, "102249.json").readBytes()
        val old = official(time = at + 1)
        val padded = old.copy(payload = JsonObject(old.payload.jsonObject + ("extra" to JsonPrimitive("x".repeat(2 * 1024 * 1024)))))
        cache.save("102249", Snapshot(padded, null, Evidence(padded), null))
        assertArrayEquals(good, File(folder, "102249.json").readBytes()); assertNotNull(cache.load("102249"))
        assertFalse(File(folder, "102249.pending").exists())
    }
    @Test fun staleWriterCannotRollbackScoreResourceTimesOrFinalAuthority(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeLaLigaSnapshotCache(folder, { at + 1000 })
        cache.save("102249", snapshot(time = at + 100)); cache.save("102249", snapshot(time = at))
        assertEquals(at + 100, cache.load("102249")!!.accepted.sample(null).checkedAt)
    }
    @Test fun officialFinalSurvivesARecordedLaterPregameRegressionAfterColdReload(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeLaLigaSnapshotCache(folder, { at + 1000 })
        val final = official()
        val raw = JsonObject(final.payload.jsonObject + ("status" to JsonPrimitive("PreMatch")))
        val later = NativeLaLigaProvider.OfficialGame(NativeLaLigaMapping.match(raw, 2026, "verified-2026")!!, raw, at + 100)
        val snapshot = Snapshot(later, null, Evidence(final), null, envelopeAt = at + 100)
        cache.save("102249", snapshot)
        val restored = NativeLaLigaSnapshotCache(folder, { at + 1000 }).load("102249")!!
        assertEquals(final.match, restored.match()); assertEquals(at, restored.details()!!.soccer!!.scoreUpdatedAt)
        assertEquals("LaLiga", restored.details()!!.soccer!!.scoreSource)
    }
    @Test fun croppedResourcesKeepOnlyTheRequiredVerifiedSurfaceAndDoNotDuplicateTheEntireResponse() {
        val raw = fixture("fotmob-matchdetails-finished")
        val shot = NativeLaLigaSnapshotCache.scoped(raw, "shots")
        assertEquals(raw["content"]["shotmap"], shot["content"]["shotmap"])
        assertNull(shot["content"]["lineup"]); assertEquals(raw["header"], shot["header"])
        assertTrue(shot.toString().length < raw.toString().length)
        assertTrue(NativeLaLigaSnapshotCache.scoped(raw, "score")["content"]!!.jsonObject.isEmpty())
    }
    @Test fun legitimateOfficialKickoffCorrectionCanClearAnInvalidOldEnrichmentMappingOnDisk(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeLaLigaSnapshotCache(folder, { at + 1000 })
        val old = official(); val mapping = NativeLaLigaReconciliation.resolve(old.match, listOf(fixture("fotmob-day-matches")))!!
        val events = Surface(at, NativeLaLigaSnapshotCache.scoped(fixture("fotmob-matchdetails-finished"), "events"))
        cache.save("102249", Snapshot(old, null, Evidence(old), mapping, mapOf("events" to events)))
        assertNotNull(cache.load("102249")!!.mapping)
        // Constructed source correction; no old FotMob identity may be reassigned outside its kickoff window.
        val raw = JsonObject(old.payload.jsonObject + ("date" to JsonPrimitive("2026-08-16T17:30:00+00:00")))
        val current = NativeLaLigaProvider.OfficialGame(NativeLaLigaMapping.match(raw, 2026, "verified-2026")!!, raw, at + 100)
        assertFalse(mapping.matches(current.match))
        cache.save("102249", Snapshot(current, null, Evidence(current), null))
        val restored = NativeLaLigaSnapshotCache(folder, { at + 1000 }).load("102249")!!
        assertNull(restored.mapping); assertTrue(restored.surfaces.isEmpty())
        assertEquals(current.match.startMillis, restored.match().startMillis)
        assertEquals(at + 100, restored.accepted.sample(null).checkedAt)
    }
}
