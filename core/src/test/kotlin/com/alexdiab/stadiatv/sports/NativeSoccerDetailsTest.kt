package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Constructed provider-shaped cases, not purported recordings of real fixtures. */
class NativeSoccerDetailsTest {
    private val match = Match("espn-game", League.find("soccer/eng.1")!!, 1745667000000, "Everton at Chelsea", "EVE @ CHE", GameState.LIVE, "", TeamSide("Chelsea", "Chelsea", "CHE", teamId = "espn-che"), TeamSide("Everton", "Everton", "EVE", teamId = "espn-eve"))
    @Test fun missingFormationDoesNotInventStartingMembershipOrDuplicatePlayers() {
        val lineup = NativeSoccer.lineup(parseJson("""{"players":[{"id":"1","firstName":"Unknown","lastName":"Role"},{"id":"1","firstName":"Duplicate"},{"id":"2","firstName":"Named","lastName":"Sub"}],"formation":{"subs":["2"]}}"""), true)!!
        assertEquals(2, lineup.players.size); assertFalse(lineup.hasPitch)
        assertFalse(lineup.players.first().membershipKnown)
        assertTrue(lineup.players.last().membershipKnown); assertFalse(lineup.players.last().starter)
    }
    @Test fun statsUseNativeTeamIdentityNotArrayOrderAndDoNotInventZeros() {
        val raw = parseJson("""[{"teamId":"a","stats":{"possessionPercentage":40,"totalScoringAtt":0,"expectedGoals":"0.23"}},{"teamId":"h","stats":{"possessionPercentage":60,"expectedGoals":1.72,"accuratePass":321,"saves":"NaN","redCard":-1}}]""")
        val stats = NativeSoccerDetails.stats(raw, "h", "a")!!
        assertEquals(StatComparison("Possession", "40%", "60%"), stats.first())
        assertEquals(StatComparison("Shots", "0", "–"), stats.first { it.label == "Shots" })
        assertEquals(StatComparison("Expected goals", "0.23", "1.72"), stats.first { it.label == "Expected goals" })
        assertFalse(stats.any { it.label in setOf("Saves", "Red cards") })
        assertNull(NativeSoccerDetails.stats(raw, "unrelated", "unrelated-2"))
    }
    @Test fun eventBucketsPreserveNativeIdentityNamesAddedTimeAndUnknownKinds() {
        val raw = parseJson("""{"homeTeam":{"goals":[{"playerId":"p","assistPlayerId":"a","goalType":"Goal","time":"45+2","period":"FirstHalf","timestamp":"20250426T134702+0100"}],"cards":[{"playerId":"p","type":"Second Yellow","time":60,"period":"SecondHalf"}],"subs":[{"playerOnId":"s","playerOffId":"p","time":70,"period":"SecondHalf"}]},"awayTeam":{"goals":[{"playerId":"e","goalType":"New provider goal kind","time":80}],"cards":[{"playerId":"e","type":"Blue","time":81}]}}""")
        val plays = NativeSoccerDetails.events(raw, match, "native-game", mapOf("p" to "Scorer", "a" to "Assister", "s" to "Substitute"))!!
        val goal = plays.single { it.isScoring }
        assertEquals("espn-che", goal.teamId); assertEquals("45+2′", goal.clock); assertEquals("Scorer · Assist: Assister", goal.text)
        assertNull(goal.homeScore); assertNull(goal.awayScore)
        assertEquals(SoccerEventKind.Goal, goal.soccerEvent!!.kind)
        assertEquals("p", goal.soccerEvent!!.primary!!.id); assertEquals("a", goal.soccerEvent!!.secondary!!.id)
        val substitution = plays.single { it.type == "Substitution" }.soccerEvent!!
        assertEquals("s", substitution.primary!!.id); assertEquals("p", substitution.secondary!!.id)
        assertTrue(plays.any { it.type == "Second yellow card" }); assertTrue(plays.any { it.text == "On: Substitute · Off: Scorer" })
        assertEquals("Blue", plays.first().type); assertFalse(plays.first().isScoring)
        assertTrue(plays.any { it.type == "New provider goal kind" && !it.isScoring })
        assertEquals(setOf("p", "a", "s", "e"), NativeSoccerDetails.referencedPlayers(raw))
    }
    @Test fun correctionsReuseIdsAndCompleteEmptySnapshotCanRemoveWithdrawnEvents() {
        val raw = """{"homeTeam":{"goals":[{"playerId":"p","goalType":"Goal","time":12,"timestamp":"20250426T131200+0100"}]},"awayTeam":{}}"""
        val original = NativeSoccerDetails.events(parseJson(raw), match, "m", emptyMap())!!
        val corrected = NativeSoccerDetails.events(parseJson(raw.replace("\"time\":12", "\"time\":13")), match, "m", emptyMap())!!
        assertEquals(original.single().id, corrected.single().id); assertEquals("13′", corrected.single().clock)
        val empty = NativeSoccerDetails.events(parseJson("""{"homeTeam":{},"awayTeam":{}}"""), match, "m", emptyMap())!!
        val overlay = NativeGameCentres.overlay(GameCentre(plays = original), GameCentre(plays = empty, playsSource = "Premier League"))
        assertTrue(overlay.plays.isEmpty()); assertEquals("Premier League", overlay.playsSource)
        assertNull(NativeSoccerDetails.events(parseJson("{}"), match, "m", emptyMap()))
        assertNull(NativeSoccerDetails.events(parseJson("""{"homeTeam":{"id":"wrong-team"},"awayTeam":{}}"""), match, "m", emptyMap(), "h", "a"))
    }
    @Test fun tableKeepsPublishedPointsDeductionsAndMissingValuesSeparate() {
        val raw = parseJson("""{"matchweek":10,"tables":[{"entries":[{"team":{"id":"b","name":"Club B"},"overall":{"position":2,"played":10,"points":15}},{"team":{"id":"a","name":"Club A"},"overall":{"position":1,"played":10,"points":20}}]}],"deductions":[{"teamId":"a","points":3}]}""")
        val table = NativeSoccerDetails.table(raw, 2025, true, 100)!!
        assertTrue(table.provisional); assertEquals(2025, table.season); assertEquals(10, table.matchweek)
        assertEquals("a", table.rows.first().id); assertEquals(20, table.rows.first().points); assertEquals(3, table.rows.first().deduction)
        assertNull(table.rows.first().won); assertNull(table.rows.last().deduction)
        assertFalse(NativeSoccerDetails.table(raw, 2025, false, 100)!!.provisional)
        assertNull(NativeSoccerDetails.table(parseJson("{}"), 2025, true, 100))
    }
    @Test fun partialResourceFailureDoesNotDiscardMetadataOrOtherSurfaces(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath.substringAfterLast('/')) {
                "native-game" -> MockResponse().setBody("""{"matchId":"native-game","homeTeam":{"id":"h","name":"Chelsea","score":2},"awayTeam":{"id":"a","name":"Everton","score":1},"period":"SecondHalf","clock":"73"}""")
                "lineups" -> MockResponse().setBody("{}")
                "officials" -> MockResponse().setBody("""{"matchOfficials":[]}""")
                "events" -> MockResponse().setBody("""{"homeTeam":{},"awayTeam":{}}""")
                "stats" -> MockResponse().setResponseCode(429).addHeader("Retry-After", "300")
                "commentary" -> MockResponse().setBody("""{"data":[{"comment":"A real-shaped update","timestamp":"2025-04-26 14:00:00","type":"comment"}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val provider = NativeSoccer(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'))
            val previous = GameCentre(providerStamp = ProviderStamp("Premier League", "native-game", 1), teamStats = listOf(StatComparison("Shots", "1", "2")), teamStatsSource = "Premier League")
            val result = provider.load(match, previous)!!
            assertEquals("2", result.liveMatch!!.home.score); assertEquals("native-game", result.providerStamp!!.gameId)
            assertEquals(previous.teamStats, result.teamStats); assertTrue(result.soccer!!.errors.containsKey("statistics")); assertEquals(1, result.soccer!!.commentary.size)
            assertEquals("Premier League · native match events", result.playsSource)
            // The shared gateway must honour a real 429 across surfaces rather than retry another route immediately.
            assertTrue(runCatching { provider.load(match, result) }.exceptionOrNull() is java.io.IOException)
            val paths = (1..server.requestCount).map { server.takeRequest().requestUrl!!.encodedPath }
            assertEquals(1, paths.count { it.endsWith("/stats") })
            assertTrue(paths.none { "espn-game" in it })
        } finally { server.shutdown() }
    }
    @Test fun tableRouteUsesSeasonAndLiveFlagNotEspnIdentity(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"tables":[]}"""))
            val table = NativeSoccer(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/')).table(match)
            assertTrue(table.provisional); val request = server.takeRequest()
            assertEquals("/api/v5/competitions/8/seasons/2024/standings", request.requestUrl!!.encodedPath)
            assertEquals("true", request.requestUrl!!.queryParameter("live"))
        } finally { server.shutdown() }
    }
    @Test fun malformedOrOversizedResourcesCannotBecomeAnAuthoritativeEmptySnapshot() {
        val malformedEvents = parseJson("""{"homeTeam":{"goals":{}},"awayTeam":{}}""")
        assertNull(NativeSoccerDetails.events(malformedEvents, match, "m", emptyMap()))
        assertTrue(NativeSoccerDetails.referencedPlayers(malformedEvents).isEmpty())
        val tooMany = buildJsonObject { put("homeTeam", buildJsonObject { put("goals", JsonArray(List(513) { buildJsonObject { put("playerId", "1"); put("time", 1) } })) }); put("awayTeam", JsonObject(emptyMap())) }
        assertNull(NativeSoccerDetails.events(tooMany, match, "m", emptyMap()))
        val emptyLineups = parseJson("""{"matchId":"m","home_team":{},"away_team":{}}""")
        assertTrue(NativeSoccerDetails.validLineups(emptyLineups, "m")); assertFalse(NativeSoccerDetails.validLineups(emptyLineups, "different"))
        val hugeLineups = JsonObject(emptyLineups.obj.orEmpty() + ("home_team" to buildJsonObject { put("players", JsonArray(List(65) { JsonObject(emptyMap()) })) }))
        assertFalse(NativeSoccerDetails.validLineups(hugeLineups, "m"))
        assertNull(NativeSoccerDetails.stats(parseJson("""[{"teamId":"h","stats":{"saves":1}},{"teamId":"foreign","stats":{"saves":2}}]"""), "h", "a"))
    }
    @Test fun nativeTableRejectsMismatchedSeasonCompetitionProvisionalStateAndMissingEntries() {
        assertNull(NativeSoccerDetails.table(parseJson("""{"tables":[{}]}"""), 2026, true, 1))
        assertNull(NativeSoccerDetails.table(parseJson("""{"season":{"id":"2025"},"tables":[]}"""), 2026, true, 1))
        assertNull(NativeSoccerDetails.table(parseJson("""{"competition":{"id":"99"},"tables":[]}"""), 2026, true, 1))
        assertNull(NativeSoccerDetails.table(parseJson("""{"live":false,"tables":[]}"""), 2026, true, 1))
        assertNotNull(NativeSoccerDetails.table(parseJson("""{"season":{"id":"2026"},"competition":{"id":"8"},"live":true,"tables":[]}"""), 2026, true, 1))
    }
}
