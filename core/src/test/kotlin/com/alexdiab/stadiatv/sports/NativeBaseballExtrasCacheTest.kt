package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeBaseballExtrasCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val highlights = """{"highlights":{"highlights":{"items":[{"id":"one","headline":"Home run","playbacks":[{"url":"https://video.example/one.mp4"}]}]}}}"""
    private val probability = """[{"about":{"atBatIndex":2},"homeTeamWinProbability":25},{"about":{"atBatIndex":8},"homeTeamWinProbability":75}]"""

    @Test fun independentDiskSurfacesRetainOriginalTimesAndNativeAtBatIndices(): Unit = runBlocking {
        val cache = NativeBaseballExtrasCache(temporary.newFolder(), { 3000 })
        cache.save("744834", "highlights", NativeBaseballExtrasCache.Saved(parseJson(highlights), 1000))
        cache.save("744834", "probability", NativeBaseballExtrasCache.Saved(parseJson(probability), 2000))
        assertEquals(1000L, cache.load("744834", "highlights")!!.at)
        assertEquals(listOf(2, 8), NativeBaseballExtras.parseProbabilityPoints(cache.load("744834", "probability")!!.payload).map { it.atBatIndex })
        assertEquals(2000L, cache.load("744834", "probability")!!.at)
    }
    @Test fun coldFailedNetworkAndCooldownReturnSavedValuesWithoutMakingThemFresh(): Unit = runBlocking {
        val cache = NativeBaseballExtrasCache(temporary.newFolder(), { 3000 })
        cache.save("744834", "highlights", NativeBaseballExtrasCache.Saved(parseJson(highlights), 1000))
        cache.save("744834", "probability", NativeBaseballExtrasCache.Saved(parseJson(probability), 1500))
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503) }; server.start()
        try {
            val extras = NativeBaseballExtras(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), cache, { 2000 })
            val video = extras.highlightsSurface("744834"); assertEquals("mlb:one", video.value.single().id); assertTrue(video.saved); assertEquals(1000L, video.checkedAt)
            val points = extras.probabilitySurface("744834"); assertEquals(listOf(2, 8), points.value.map { it.atBatIndex }); assertTrue(points.saved); assertEquals(1500L, points.checkedAt)
            assertTrue(extras.highlightsSurface("744834").saved); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun acceptedEmptyResponseReplacesOldContentRatherThanResurrectingIt(): Unit = runBlocking {
        val cache = NativeBaseballExtrasCache(temporary.newFolder(), { 3000 })
        cache.save("744834", "highlights", NativeBaseballExtrasCache.Saved(parseJson(highlights), 1000))
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("""{"highlights":{"highlights":{"items":[]}}}""")); server.start()
        try {
            val extras = NativeBaseballExtras(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), cache, { 2000 })
            val value = extras.highlightsSurface("744834"); assertFalse(value.saved); assertTrue(value.value.isEmpty()); assertEquals(2000L, value.checkedAt)
            assertTrue(NativeBaseballExtras.parseHighlights(cache.load("744834", "highlights")!!.payload).isEmpty())
            val base = GameCentre(videos = listOf(GameVideo("old", "Removed clip", "https://video.example/old.mp4")), winProbability = listOf(.5f), winProbabilitySource = "ESPN")
            val empty = GameCentre(baseballExtras = BaseballExtraState(mapOf("highlights" to 2000, "probability" to 2000)), winProbabilitySource = "MLB")
            val combined = NativeGameCentres.overlay(base, empty)
            assertTrue(combined.videos.isEmpty()); assertTrue(combined.winProbability.isEmpty()); assertEquals("MLB", combined.winProbabilitySource)
        } finally { server.shutdown() }
    }
    @Test fun invalidCacheCannotEscapeItsNamespaceOrLoseItsOriginalIdentity(): Unit = runBlocking {
        val directory = temporary.newFolder(); val cache = NativeBaseballExtrasCache(directory, { 3000 })
        cache.save("../outside", "highlights", NativeBaseballExtrasCache.Saved(parseJson(highlights), 1000)); assertTrue(directory.listFiles()!!.isEmpty())
        assertNull(cache.load("744834", "unknown")); assertNull(cache.load("../outside", "highlights"))
        for (raw in listOf("broken", """{"version":1,"gameId":"744835","kind":"highlights","at":1000,"payload":$highlights}""", """{"version":1,"gameId":"744834","kind":"highlights","at":99999999,"payload":$highlights}""")) {
            File(directory, "744834-highlights.json").writeText(raw); assertNull(cache.load("744834", "highlights"))
        }
        cache.save("744834", "probability", NativeBaseballExtrasCache.Saved(parseJson("{}"), 1000)); assertNull(cache.load("744834", "probability"))
    }
    @Test fun mediaCacheIsBoundedAndOlderSavesCannotReplaceNewerOnes(): Unit = runBlocking {
        val directory = temporary.newFolder(); val cache = NativeBaseballExtrasCache(directory, { 3000 })
        cache.save("744834", "probability", NativeBaseballExtrasCache.Saved(parseJson(probability), 2000))
        cache.save("744834", "probability", NativeBaseballExtrasCache.Saved(parseJson("[]"), 1000))
        assertEquals(2000L, cache.load("744834", "probability")!!.at)
        repeat(30) { offset -> cache.save("${744834 + offset}", "probability", NativeBaseballExtrasCache.Saved(parseJson(probability), 1000)) }
        assertEquals(24, directory.listFiles()!!.size); assertTrue(directory.listFiles()!!.all { it.extension == "json" })
    }
}
