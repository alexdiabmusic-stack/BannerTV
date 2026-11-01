package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeBasketballRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val config = NativeBasketballLeague.NBA
    private val at = Dates.parseIso("2026-01-15T20:00:00Z")!!
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/$name.constructed.json")!!.readText())
    private fun game() = fixture("boxscore-live")["game"]!!
    private fun plays() = fixture("playbyplay-live")["game"]!!
    private fun match() = NativeBasketballMapping.game(game(), config)!!
    private fun update(old: GameCentre? = null, game: JsonElement = game(), plays: JsonElement? = plays(), time: Long = at, box: Boolean = true, score: Boolean = true) =
        NativeBasketball.update(old, game, plays, match(), time, config, scoreFresh = score, boxFresh = box, playsSource = "NBA CDN")!!
    private fun withoutBox(game: JsonElement) = JsonObject(game.jsonObject.mapValues { (key, value) ->
        if (key in setOf("awayTeam", "homeTeam")) JsonObject(value.jsonObject - "players" - "statistics") else value
    })
    private fun setup(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }; start()
    }
    private fun service(server: MockWebServer, now: () -> Long, cache: NativeBasketballSnapshotCache? = null): NativeBasketball {
        val http = HttpGateway(OkHttpClient())
        return NativeBasketball(http, NativeBasketballClient(config, http, now, server.url("/cdn").toString(), server.url("/stats-host").toString()),
            NativeBasketballClient(NativeBasketballLeague.WNBA, http, now, server.url("/wnba").toString(), server.url("/wnba-stats").toString()), cache, now)
    }

    @Test fun scoreOnlyTickRetainsBoxLeadersPlaysAndTheirOriginalTimes() {
        val old = update()
        val next = update(old, withoutBox(game()), null, at + 15_000, box = false)
        assertEquals(old.boxScore, next.boxScore); assertEquals(old.leaders, next.leaders); assertEquals(old.plays, next.plays)
        assertEquals(at + 15_000, next.basketball!!.surfaceUpdatedAt["score"])
        assertEquals(at, next.basketball!!.surfaceUpdatedAt["box"]); assertEquals(at, next.basketball!!.surfaceUpdatedAt["plays"])
        assertTrue(next.basketball!!.boxLoaded); assertTrue(next.basketball!!.playsLoaded)
    }
    @Test fun validatedNativeGameAcceptsItsReportedStartAndClockWithoutDuplicatedRawPeriod() {
        val changed = JsonObject(game().jsonObject + mapOf("period" to JsonPrimitive(4), "gameClock" to JsonPrimitive("PT29.8S")))
        val data = NativeBasketball.parse(changed, plays(), match().copy(startMillis = match().startMillis - 3_600_000), at)!!
        assertEquals(match().startMillis, data.liveMatch!!.startMillis)
        assertEquals("Q4 · 0:29.8", data.liveMatch!!.statusDetail)
        assertEquals("0:29.8", data.liveMatch!!.live.clock)
    }
    @Test fun implausibleOnCourtCountDoesNotEraseTheAcceptedCourtList() {
        val old = update()
        val incoming = old.copy(providerStamp = old.providerStamp!!.copy(requestedAt = at + 1000),
            basketball = old.basketball!!.copy(onCourt = emptyList(), onCourtValid = false))
        assertEquals(old.basketball!!.onCourt, NativeBasketball.reduce(old, incoming).basketball!!.onCourt)
    }

    @Test fun finalTransitionKeepsRealPeriodInternallyButClearsLiveClockAndCorrectsWinner() {
        val old = update()
        val final = JsonObject(game().jsonObject + mapOf("gameStatus" to JsonPrimitive(3), "gameStatusText" to JsonPrimitive("Final"), "period" to JsonPrimitive(4)))
        val next = update(old, final, time = at + 1000)
        assertTrue(next.providerStamp!!.final); assertEquals(4, next.basketball!!.period); assertEquals(LiveContext.EMPTY, next.liveMatch!!.live)
        assertEquals((next.liveMatch!!.home.score!!.toInt() > next.liveMatch!!.away.score!!.toInt()), next.liveMatch!!.home.isWinner)
        assertSame(next, NativeBasketball.reduce(next, old.copy(providerStamp = old.providerStamp!!.copy(requestedAt = at + 2000))))
    }

    @Test fun wrongGameTeamIdsForeignLeagueAndForeignPlayResponseAreRejected() {
        val expected = match()
        assertNull(NativeBasketball.parse(JsonObject(game().jsonObject + ("gameId" to JsonPrimitive("0022500501"))), plays(), expected, at))
        assertNull(NativeBasketball.parse(game(), plays(), expected.copy(home = expected.home.copy(teamId = "nba:999")), at))
        assertNull(NativeBasketball.parse(game(), plays(), expected.copy(nativeReference = NativeGameReference("WNBA", "0022500500")), at))
        assertNull(NativeBasketball.parse(game(), JsonObject(plays().jsonObject + ("gameId" to JsonPrimitive("0022500501"))), expected, at))
        assertNull(NativeBasketball.parse(game(), JsonObject(plays().jsonObject - "actions"), expected, at))
    }

    @Test fun shrinkingOneTeamsBoxCannotBeHiddenByAddingRowsToTheOtherTeam() {
        val old = update()
        val away = old.boxScore.first(); val home = old.boxScore.last()
        val changed = old.copy(boxScore = listOf(away.copy(groups = away.groups.map { it.copy(rows = it.rows.dropLast(1)) }), home.copy(groups = home.groups.map { it.copy(rows = it.rows + it.rows) })),
            providerStamp = old.providerStamp!!.copy(requestedAt = at + 1000), basketball = old.basketball!!.copy(surfaceUpdatedAt = old.basketball!!.surfaceUpdatedAt + ("box" to at + 1000)))
        val result = NativeBasketball.reduce(old, changed)
        assertEquals(old.boxScore, result.boxScore); assertEquals(at, result.basketball!!.surfaceUpdatedAt["box"])
    }

    @Test fun fullTwelveColumnsHaveFractionsAndNativeInAppPlayerIdsWithoutInventingMissingValues() {
        val data = update()
        val group = data.boxScore.last().groups.single(); val row = group.rows.first()
        assertEquals(listOf("MIN", "PTS", "REB", "AST", "FG", "3PT", "FT", "STL", "BLK", "TO", "PF", "+/-"), group.labels)
        assertEquals("28", row.stats.first()); assertTrue(row.athleteId!!.startsWith("nba:"))
        assertTrue(data.basketball!!.onCourt.all { it.id.startsWith("nba:") })
        val missing = game().jsonObject.toMutableMap()
        val team = game()["homeTeam"]!!.jsonObject.toMutableMap(); val player = team["players"].array.first().jsonObject.toMutableMap()
        player["statistics"] = JsonObject(player["statistics"]!!.jsonObject - "fieldGoalsAttempted")
        team["players"] = JsonArray(listOf(JsonObject(player))); missing["homeTeam"] = JsonObject(team)
        assertEquals("–", update(game = JsonObject(missing)).boxScore.last().groups.single().rows.single().stats[4])
    }

    @Test fun knownNativeGameUsesDirectBoxAndPlaysThenLightweightScoreWithoutSeasonRedownload(): Unit = runBlocking {
        var tick = at
        val server = setup { request -> when {
            request.path!!.contains("/boxscore/") -> MockResponse().setBody(fixture("boxscore-live").toString())
            request.path!!.contains("/playbyplay/") -> MockResponse().setBody(fixture("playbyplay-live").toString())
            request.path!!.contains("/scoreboard/") -> MockResponse().setBody(buildJsonObject { put("scoreboard", buildJsonObject { put("games", JsonArray(listOf(withoutBox(game())))) }) }.toString())
            else -> MockResponse().setResponseCode(404)
        } }
        try {
            val service = service(server, { tick }); val first = service.load(match(), null)!!
            tick += 15_000; val second = service.load(match(), first)!!
            assertEquals(3, server.requestCount) // A repeated play request may reuse HttpGateway's fresh 12s body.
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertEquals(1, requests.count { it.path!!.contains("/boxscore/") }); assertEquals(1, requests.count { it.path!!.contains("/scoreboard/") })
            assertTrue(requests.none { it.path!!.contains("staticData") || it.path!!.contains("stats-host") })
            assertEquals(at, second.basketball!!.surfaceUpdatedAt["box"]); assertEquals(tick, second.basketball!!.surfaceUpdatedAt["score"])
        } finally { server.shutdown() }
    }

    @Test fun statsPlayFallbackUsesNamedColumnTableAndKeepsItsActualSource(): Unit = runBlocking {
        val server = setup { request -> if (request.path!!.startsWith("/cdn")) MockResponse().setResponseCode(404) else MockResponse().setBody(fixture("nba-playbyplay-v3").toString()) }
        try {
            val http = HttpGateway(OkHttpClient()); val client = NativeBasketballClient(config, http, { at }, server.url("/cdn").toString(), server.url("/stats-host").toString())
            val feed = client.plays("0022500500")
            assertEquals("NBA Stats", feed.source); assertEquals("0022500500", feed.game["gameId"].string()); assertEquals(2, feed.game["actions"].array.size)
            val data = update(plays = feed.game); assertEquals(2, data.plays.size); assertEquals("nba:1610612747", data.plays.first().teamId)
        } finally { server.shutdown() }
    }

    @Test fun aWrongPlaySurfaceCannotVetoTheIndependentValidBox(): Unit = runBlocking {
        val foreign = JsonObject(plays().jsonObject + ("gameId" to JsonPrimitive("0022500501")))
        val server = setup { request -> when {
            request.path!!.contains("/boxscore/") -> MockResponse().setBody(fixture("boxscore-live").toString())
            request.path!!.contains("/playbyplay/") -> MockResponse().setBody(buildJsonObject { put("game", foreign) }.toString())
            else -> MockResponse().setResponseCode(404)
        } }
        try {
            val data = service(server, { at }).load(match(), null)!!
            assertTrue(data.boxScore.isNotEmpty()); assertTrue(data.basketball!!.boxLoaded)
            assertFalse(data.basketball!!.playsLoaded); assertTrue(data.plays.isEmpty())
            assertTrue(data.basketball!!.errors.containsKey("plays")); assertFalse(data.basketball!!.errors.containsKey("box"))
        } finally { server.shutdown() }
    }

    @Test fun cacheReplaysScoreAndPlaysWithoutChangingOriginalBoxTimeAndColdLinksAreCachedFirst(): Unit = runBlocking {
        val cache = NativeBasketballSnapshotCache(temporary.newFolder(), { at + 30_000 })
        val first = update(); val initial = NativeBasketballSnapshotCache.Snapshot().accept(game(), NativeBasketballClient.PlayFeed(plays(), "NBA CDN"), true, at, first)
        val next = update(first, withoutBox(game()), null, at + 15_000, box = false)
        val saved = initial.accept(withoutBox(game()), null, false, at + 15_000, next)
        cache.save(config, "0022500500", saved)
        val restored = cache.load(config, "0022500500")!!; val data = restored.details(match(), config)!!
        assertEquals(next.plays, data.plays); assertEquals(next.boxScore, data.boxScore); assertTrue(data.basketball!!.savedSnapshot)
        assertEquals(at, data.basketball!!.surfaceUpdatedAt["box"]); assertEquals(at + 15_000, data.basketball!!.surfaceUpdatedAt["score"])
        val server = setup { MockResponse().setResponseCode(503) }
        try {
            val http = HttpGateway(OkHttpClient()); val client = NativeBasketballClient(config, http, { at }, server.url("/").toString())
            val provider = NativeBasketballProvider(client, cachedMatch = { cache.load(config, it)?.match(config) })
            assertEquals(match().id, provider.calendarGame(match().id)!!.id); assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cacheCannotMixLeaguesReplaceNewerDataOrAcceptTraversalFutureAndExpiredRecords(): Unit = runBlocking {
        var tick = at
        val cache = NativeBasketballSnapshotCache(temporary.newFolder(), { tick })
        val first = update(); val initial = NativeBasketballSnapshotCache.Snapshot().accept(game(), NativeBasketballClient.PlayFeed(plays(), "NBA CDN"), true, at, first)
        cache.save(config, "0022500500", initial)
        assertNull(cache.load(NativeBasketballLeague.WNBA, "0022500500")); assertNull(cache.load(config, "../../0022500500"))
        cache.save(config, "0022500500", initial.copy(surfaces = initial.surfaces.mapValues { it.value.copy(at = at - 1) }))
        assertEquals(at, cache.load(config, "0022500500")!!.surfaces["box"]!!.at)
        cache.save(config, "0022500501", initial); assertNull(cache.load(config, "0022500501"))
        tick = at - 300_001; assertNull(cache.load(config, "0022500500"))
        tick = at + 31L * 86_400_000; assertNull(cache.load(config, "0022500500"))
    }

    @Test fun cacheRetainsPriorPlayWindowAndReplacesCorrectionInsteadOfDuplicatingItsShot(): Unit = runBlocking {
        val first = update(); val initial = NativeBasketballSnapshotCache.Snapshot().accept(game(), NativeBasketballClient.PlayFeed(plays(), "NBA CDN"), true, at, first)
        val action = plays()["actions"].array.last()
        val corrected = JsonObject(action.jsonObject + mapOf("description" to JsonPrimitive("Overturned"), "actionType" to JsonPrimitive("foul")))
        val window = JsonObject(plays().jsonObject + ("actions" to JsonArray(listOf(corrected))))
        val next = update(first, plays = window, time = at + 1000)
        val saved = initial.accept(game(), NativeBasketballClient.PlayFeed(window, "NBA CDN"), true, at + 1000, next)
        val restored = saved.details(match(), config)!!
        assertEquals(first.plays.size, restored.plays.size)
        assertEquals(next.plays, restored.plays); assertEquals(next.shots, restored.shots)
    }
}
