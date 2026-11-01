package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/** Entirely constructed miniature match for resource scheduling, never runtime data or a real result claim. */
class NativeSoccerPollingTest {
    private val overview = """{"matchId":"900007","competitionId":"8","season":"2026","kickoff":"2026-10-23 19:00:00","period":"SecondHalf","clock":"60","homeTeam":{"id":"100","name":"Test Home","abbr":"HOM","score":1},"awayTeam":{"id":"200","name":"Test Away","abbr":"AWY","score":0}}"""
    private val lineups = """{"home_team":{"players":[{"id":"101","firstName":"Home","lastName":"Player"}],"formation":{"lineup":[["101"]]}},"away_team":{"players":[{"id":"201","firstName":"Away","lastName":"Player"}],"formation":{"lineup":[["201"]]}}}"""
    private val stats = """[{"teamId":"100","stats":{"possessionPercentage":55}},{"teamId":"200","stats":{"possessionPercentage":45}}]"""

    @Test fun announcedLineupsAndOffTabResourcesAreNotRefetchedAndLightScoresDoNotPostponeTheFullRefresh(): Unit = runBlocking {
        var clock = 1_800_000_000_000L
        val paths = ConcurrentLinkedQueue<String>()
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath; paths += path
                return MockResponse().setBody(when {
                    path.endsWith("900007") -> overview
                    path.endsWith("lineups") -> lineups
                    path.endsWith("events") -> """{"homeTeam":{"id":"100"},"awayTeam":{"id":"200"}}"""
                    path.endsWith("stats") -> stats
                    path.endsWith("commentary") -> """{"data":[],"pagination":{"_next":"opaque"}}"""
                    path.endsWith("officials") -> """{"matchOfficials":[{"official":{"name":"Test Official"},"type":"Referee"}]}"""
                    else -> error("Unexpected route $path")
                })
            }
        }; start() }
        try {
            val provider = NativeSoccer(HttpGateway(OkHttpClient()), server.url("/").toString(), { clock })
            val match = NativePremierLeagueMapping.game(parseJson(overview))!!
            val initial = provider.load(match, null)!!
            assertEquals(6, paths.size); assertEquals(2, initial.soccer!!.lineups.size)
            assertEquals("Test Official", initial.soccer!!.officials.single().name)
            val first = clock; assertEquals(first, initial.soccer!!.lastFullRefresh)
            clock += 10_000; paths.clear()
            val light = provider.load(match, initial, tab = "Events")!!
            assertEquals(setOf("/api/v2/matches/900007", "/api/v1/matches/900007/events"), paths.toSet())
            assertEquals(first, light.soccer!!.lastFullRefresh)
            assertEquals(first, light.soccer!!.surfaceUpdatedAt["lineups"])
            assertEquals(first, light.soccer!!.surfaceUpdatedAt["statistics"])
            assertEquals(first, light.soccer!!.surfaceUpdatedAt["commentary"])
            assertEquals(first, light.soccer!!.surfaceUpdatedAt["officials"])
            assertEquals(clock, light.soccer!!.surfaceUpdatedAt["events"])
            clock += 110_000; paths.clear()
            val full = provider.load(match, light, tab = "Events")!!
            assertEquals(6, paths.size); assertEquals(clock, full.soccer!!.lastFullRefresh)
            assertEquals(clock, full.soccer!!.surfaceUpdatedAt["commentary"])
            clock += 1000; paths.clear()
            provider.load(match, full, force = true, tab = "Events")
            assertEquals(6, paths.size)
        } finally { server.shutdown() }
    }

    @Test fun failedOrUnrequestedSurfacesRetainTheirActualTimesAndMalformedCommentaryDoesNotEraseItsCursor(): Unit = runBlocking {
        var clock = 1_800_000_000_000L
        var malformed = false
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(when {
                request.requestUrl!!.encodedPath.endsWith("900007") -> overview
                request.requestUrl!!.encodedPath.endsWith("lineups") -> if (malformed) "{}" else lineups
                request.requestUrl!!.encodedPath.endsWith("events") -> """{"homeTeam":{},"awayTeam":{}}"""
                request.requestUrl!!.encodedPath.endsWith("stats") -> if (malformed) "{}" else stats
                request.requestUrl!!.encodedPath.endsWith("officials") -> if (malformed) "{}" else """{"matchOfficials":[{"official":{"name":"Original Official"},"type":"Referee"}]}"""
                else -> if (malformed) "{}" else """{"data":[{"timestamp":"2026-10-23 20:00:00","comment":"Original","type":"comment"}],"pagination":{"_next":"opaque"}}"""
            })
        }; start() }
        try {
            val provider = NativeSoccer(HttpGateway(OkHttpClient()), server.url("/").toString(), { clock })
            val match = NativePremierLeagueMapping.game(parseJson(overview))!!
            val first = provider.load(match, null)!!
            val originalTime = clock; clock += 30_000; malformed = true
            val fresh = provider.load(match, first, force = true)!!
            assertEquals(first.teamStats, fresh.teamStats); assertEquals(first.soccer!!.lineups, fresh.soccer!!.lineups)
            assertEquals(first.soccer!!.commentary, fresh.soccer!!.commentary); assertEquals("opaque", fresh.soccer!!.nextCursor)
            assertEquals(first.soccer!!.officials, fresh.soccer!!.officials)
            for (kind in listOf("statistics", "lineups", "commentary", "officials")) {
                assertTrue(fresh.soccer!!.errors.containsKey(kind)); assertEquals(originalTime, fresh.soccer!!.surfaceUpdatedAt[kind])
            }
            assertEquals(clock, fresh.soccer!!.surfaceUpdatedAt["overview"])
        } finally { server.shutdown() }
    }
    @Test fun validNotAnnouncedLineupsAreCheckedAgainOnFullRefreshNotEveryLightPoll(): Unit = runBlocking {
        var clock = 1_800_000_000_000L
        val paths = ConcurrentLinkedQueue<String>()
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath; paths += path
                return MockResponse().setBody(when {
                    path.endsWith("900007") -> overview
                    path.endsWith("lineups") -> """{"home_team":{},"away_team":{}}"""
                    path.endsWith("events") -> """{"homeTeam":{},"awayTeam":{}}"""
                    path.endsWith("stats") -> stats
                    path.endsWith("officials") -> """{"matchOfficials":[]}"""
                    else -> """{"data":[]}"""
                })
            }
        }; start() }
        try {
            val provider = NativeSoccer(HttpGateway(OkHttpClient()), server.url("/").toString(), { clock })
            val match = NativePremierLeagueMapping.game(parseJson(overview))!!
            val first = provider.load(match, null)!!
            assertTrue(first.soccer!!.lineups.isEmpty()); assertTrue(first.soccer!!.errors.isEmpty()); assertEquals(clock, first.soccer!!.surfaceUpdatedAt["lineups"])
            clock += 10_000; paths.clear()
            val light = provider.load(match, first, tab = "Lineups")!!
            assertEquals(listOf("/api/v2/matches/900007"), paths.toList())
            clock += 110_000; paths.clear(); provider.load(match, light, tab = "Lineups")
            assertEquals(1, paths.count { it.endsWith("/lineups") })
        } finally { server.shutdown() }
    }
}
