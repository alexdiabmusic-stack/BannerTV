package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeBaseballExtrasTest {
    @Test fun highlightsUseNativeNestingAndOnlyHttpsMp4Playbacks() {
        val result = NativeBaseballExtras.parseHighlights(parseJson("""{"highlights":{"highlights":{"items":[
            {"id":"one","headline":"Home run","playbacks":[{"url":"http://video.example/one.mp4"},{"url":"https://video.example/one.m3u8"},{"url":"https://video.example/one.mp4?quality=720"}],"image":{"cuts":[{"src":"https://images.example/one.jpg"}]}},
            {"id":"one","playbacks":[{"url":"https://video.example/duplicate.mp4"}]},
            {"guid":"two","title":"Strikeout","playbacks":[{"url":"https://video.example/two.mp4"}]},
            {"id":"bad","playbacks":[{"url":"file:///private/clip.mp4"}]}]}}}"""))
        assertEquals(listOf("mlb:one", "mlb:two"), result.map { it.id })
        assertEquals("Home run", result[0].title); assertEquals("https://video.example/one.mp4?quality=720", result[0].url)
        assertEquals("https://images.example/one.jpg", result[0].image)
    }
    @Test fun missingHighlightsAndMalformedProbabilityStayUnavailable() {
        assertTrue(NativeBaseballExtras.parseHighlights(parseJson("{}" )).isEmpty())
        assertTrue(NativeBaseballExtras.parseProbability(parseJson("""[{"about":{"atBatIndex":1},"homeTeamWinProbability":101},{"about":{"atBatIndex":2},"homeTeamWinProbability":-1},{"about":{},"homeTeamWinProbability":50},{"about":{"atBatIndex":3},"homeTeamWinProbability":"NaN"}]""")).isEmpty())
    }
    @Test fun probabilitySortsAtBatIdentityAcceptsCorrectionsAndNeverFillsMissingPoints() {
        val result = NativeBaseballExtras.parseProbability(parseJson("""[{"about":{"atBatIndex":8},"homeTeamWinProbability":80},{"about":{"atBatIndex":2},"homeTeamWinProbability":25},{"about":{"atBatIndex":8},"homeTeamWinProbability":75}]"""))
        assertEquals(listOf(.25f, .75f), result)
        val indexed = NativeBaseballExtras.parseProbabilityPoints(parseJson("""[{"about":{"atBatIndex":8},"homeTeamWinProbability":80},{"about":{"atBatIndex":2},"homeTeamWinProbability":25},{"about":{"atBatIndex":8},"homeTeamWinProbability":75}]"""))
        assertEquals(listOf(2, 8), indexed.map { it.atBatIndex }); assertEquals(listOf(.25f, .75f), indexed.map { it.homeProbability })
    }
    @Test fun failedContentDoesNotBlockProbabilityAndHonoursItsOwnCooldown(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val extras = NativeBaseballExtras(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "300"))
            assertThrows(HttpStatusException::class.java) { runBlocking { extras.highlights("744834") } }
            assertThrows(IllegalStateException::class.java) { runBlocking { extras.highlights("744834") } }
            server.enqueue(MockResponse().setBody("""[{"about":{"atBatIndex":1},"homeTeamWinProbability":50}]"""))
            assertEquals(listOf(.5f), extras.probability("744834"))
            assertEquals(2, server.requestCount)
            assertEquals("/api/v1/game/744834/content", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("about,atBatIndex,homeTeamWinProbability", request.requestUrl!!.queryParameter("fields"))
        } finally { server.shutdown() }
    }
    @Test fun chartProvenanceFollowsTheSurfaceActuallyChosen() {
        val base = GameCentre(winProbability = listOf(.4f), winProbabilitySource = "ESPN")
        val native = GameCentre(winProbability = listOf(.7f), winProbabilitySource = "MLB · reported at-bats")
        assertEquals("MLB · reported at-bats", NativeGameCentres.overlay(base, native).winProbabilitySource)
        assertEquals("ESPN", NativeGameCentres.overlay(base, native.copy(winProbability = emptyList())).winProbabilitySource)
    }
    @Test fun feedRefreshDoesNotEraseIndependentContentAndProbability() {
        val old = GameCentre(providerStamp = ProviderStamp("MLB", "1", 10), baseball = BaseballDetail(1, "top", 0, 0, 0, listOf(null, null, null), null, null, emptyList()),
            videos = listOf(GameVideo("one", "Clip", "https://video.example/one.mp4")), winProbability = listOf(.8f), winProbabilitySource = "MLB")
        val refresh = old.copy(providerStamp = old.providerStamp!!.copy(requestedAt = 11), videos = emptyList(), winProbability = emptyList(), winProbabilitySource = null)
        val result = NativeBaseball.reduce(old, refresh)
        assertEquals(old.videos, result.videos); assertEquals(old.winProbability, result.winProbability); assertEquals("MLB", result.winProbabilitySource)
    }
}
