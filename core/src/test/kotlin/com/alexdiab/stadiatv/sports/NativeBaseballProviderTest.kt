package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.data.FavoriteTeam
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
import java.util.TimeZone

class NativeBaseballProviderTest {
    private val league = League.find("baseball/mlb")!!
    private fun recorded() = parseJson(javaClass.getResource("/parity/mlb-schedule.recorded.json")!!.readText())
    private fun first() = recorded()["dates"][0]["games"][0]!!
    private fun changed(raw: JsonElement, vararg values: Pair<String, JsonElement>) = JsonObject(raw.jsonObject + values)

    @Test fun recordedSchedulePreservesEveryGameNativeIdsScoresCrestsAndFinalState() {
        val raw = recorded(); val games = NativeBaseballProvider.matches(raw)
        assertEquals(raw["dates"].array.sumOf { it["games"].array.size }, games.size)
        val game = games.single { it.id == "mlb:744932" }
        assertEquals("MLB", game.nativeReference!!.provider); assertEquals("744932", game.nativeReference!!.gameId)
        assertEquals("mlb:141", game.home.teamId); assertEquals("PIT", game.away.abbreviation)
        assertEquals("8–1", game.displayScore); assertTrue(game.away.isWinner); assertFalse(game.home.isWinner)
        assertEquals(GameState.FINAL, game.state); assertEquals(LiveContext.EMPTY, game.live)
        assertTrue(game.home.logoUrl!!.endsWith("/141.jpg"))
    }
    @Test fun doubleheadersAreDistinctGamesNotDeduplicatedByTeamsOrKickoff() {
        val second = changed(first(), "gamePk" to JsonPrimitive(744933), "gameNumber" to JsonPrimitive(2), "doubleHeader" to JsonPrimitive("Y"))
        val root = buildJsonObject { put("dates", JsonArray(listOf(buildJsonObject { put("games", JsonArray(listOf(first(), first(), second))) }))) }
        assertEquals(listOf("mlb:744932", "mlb:744933"), NativeBaseballProvider.matches(root).map { it.id })
    }
    @Test fun postponedAndWarmupOverrideMisleadingAbstractStatesAndPregameHasNoPlaceholderScores() {
        fun status(detail: String, abstract: String) = parseJson("""{"detailedState":"$detail","abstractGameState":"$abstract"}""")
        for (detail in listOf("Postponed", "Cancelled", "Warmup", "Pre-Game", "Scheduled")) {
            assertEquals(GameState.PRE, NativeBaseballProvider.gameState(status(detail, "Live")))
            val game = NativeBaseballProvider.game(changed(first(), "status" to status(detail, "Live")))!!
            assertNull(game.away.score); assertNull(game.home.score); assertFalse(game.away.isWinner)
        }
        for (detail in listOf("Suspended", "Delayed Start", "Manager Challenge", "Umpire Review")) assertEquals(GameState.LIVE, NativeBaseballProvider.gameState(status(detail, "Preview")))
        assertEquals(GameState.FINAL, NativeBaseballProvider.gameState(status("Completed Early", "Preview")))
    }
    @Test fun malformedGamesAndMissingNumericScoresNeverBecomeZeroOrWinners() {
        assertNull(NativeBaseballProvider.game(changed(first(), "gamePk" to JsonPrimitive("../1"))))
        assertNull(NativeBaseballProvider.game(changed(first(), "gameDate" to JsonPrimitive("not-a-date"))))
        val teams = first()["teams"]!!.jsonObject; val home = teams["home"]!!.jsonObject
        val raw = changed(first(), "teams" to JsonObject(teams + ("home" to JsonObject(home - "score"))))
        val game = NativeBaseballProvider.game(raw)!!
        assertNull(game.home.score); assertFalse(game.away.isWinner); assertFalse(game.home.isWinner)
    }
    @Test fun liveBaseOccupancyClearsBetweenInningsAndFinalMetadataNeverShowsAnOldCount() {
        val live = changed(first(), "status" to parseJson("""{"detailedState":"In Progress","abstractGameState":"Live"}"""),
            "linescore" to parseJson("""{"currentInning":3,"inningState":"Top","balls":2,"strikes":1,"outs":1,"offense":{"first":{"id":1}}}"""))
        val game = NativeBaseballProvider.game(live)!!
        assertEquals("Top 3", game.statusDetail); assertTrue(game.live.situation.contains("Runners on first"))
        val middle = changed(live, "linescore" to parseJson("""{"currentInning":3,"inningState":"Middle","balls":2,"strikes":1,"outs":3,"offense":{"first":{"id":1}}}"""))
        assertEquals(listOf("3 outs"), NativeBaseballProvider.game(middle)!!.live.situation)
    }
    @Test fun feedToCalendarAndDetailUseTheSameQualifiedIdentityWithoutScheduleGuessing() {
        val feed = parseJson(javaClass.getResource("/parity/mlb-live-late.json")!!.readText())
        val match = NativeBaseballProvider.gameFromFeed(feed)!!
        assertEquals("mlb:744834", match.id); assertTrue(match.home.teamId!!.startsWith("mlb:"))
        val detail = NativeBaseball.parse(feed, match, 1000)!!
        assertEquals(match.id, detail.liveMatch!!.id); assertTrue(detail.boxScore.flatMap { it.groups }.flatMap { it.rows }.all { it.athleteId!!.startsWith("mlb:") })
        assertNull(NativeBaseball.parse(feed, match.copy(nativeReference = NativeGameReference("MLB", "744835")), 1000))
    }
    @Test fun standingsKeepRankOrderNativeTeamsAndActualDetailedColumns() {
        val root = parseJson("""{"records":[{"division":{"id":201,"name":"American League East"},"teamRecords":[{"team":{"id":141,"name":"Toronto Blue Jays","abbreviation":"TOR"},"wins":5,"losses":3,"winningPercentage":".625","divisionGamesBack":"-","gamesPlayed":8,"streak":{"streakCode":"W2"},"wildCardRank":"1","records":{"splitRecords":[{"type":"lastTen","wins":5,"losses":3}]}},{"team":{"id":147,"name":"New York Yankees","abbreviation":"NYY"},"wins":4,"losses":4}]}]}""")
        val group = NativeBaseballProvider.parseStandings(root).single()
        assertEquals("American League East", group.name); assertEquals(listOf("mlb:141", "mlb:147"), group.rows.map { it.teamId })
        assertEquals(".625", group.rows.first().values["winpercent"]); assertEquals("5-3", group.rows.first().values["lastten"])
        assertEquals("W2", group.rows.first().values["streak"]); assertFalse(group.rows.last().values.containsKey("streak"))
    }
    @Test fun rosterAndPlayerRetainProviderScopeTypedBioAndDistinctPitchingBattingStats() {
        val roster = NativeBaseballProvider.parseRoster(parseJson("""{"roster":[{"person":{"id":1,"fullName":"Example Pitcher","currentAge":25,"height":"6' 2\""},"jerseyNumber":"9","position":{"abbreviation":"P","name":"Pitcher"}},{"person":{"id":2,"fullName":"Example Batter"},"position":{"abbreviation":"C"}}]}"""))
        assertEquals(listOf("Pitchers", "Position players"), roster.map { it.title }); assertEquals("mlb:1", roster.first().athletes.single().id)
        assertEquals(25, roster.first().athletes.single().age)
        val profile = NativeBaseballProvider.parsePlayer(parseJson("""{"id":1,"fullName":"Example Pitcher","currentAge":25,"birthCity":"Ottawa","birthCountry":"Canada","stats":[{"group":{"displayName":"hitting"},"splits":[{"season":"2024","stat":{"avg":".250","strikeOuts":3}}]},{"group":{"displayName":"pitching"},"splits":[{"season":"2024","stat":{"era":"2.10","strikeOuts":15}}]}]}"""))
        assertEquals("2024 Season", profile.statlineLabel); assertEquals("3", profile.stats["Hitting · Strikeouts"]); assertEquals("15", profile.stats["Pitching · Strikeouts"])
        assertEquals("Ottawa, Canada", profile.athlete.birthPlace); assertFalse(profile.stats.keys.any { it.contains("Home runs") })
    }
    @Test fun savedEspnFavoritesResolveMlbAbbreviationAliasesWithoutNumericCollisions() {
        val favorite = FavoriteTeam(league.path, "26", "Athletics", "OAK")
        val team = Team("mlb:133", "Athletics", "Athletics", "ATH", null, league)
        assertTrue(favorite.matches(team)); assertFalse(favorite.matches(team.copy(abbreviation = "NYY", name = "New York Yankees", id = "mlb:26")))
        assertTrue(TeamIdentity.sameAbbreviation(league.path, "WSH", "WSN")); assertFalse(TeamIdentity.sameAbbreviation("hockey/nhl", "WSH", "WSN"))
    }
    @Test fun qualifiedTeamIdentityCannotBeOverriddenByAMatchingAbbreviation() {
        val raw = parseJson(javaClass.getResource("/parity/mlb-live-late.json")!!.readText())
        val match = NativeBaseballProvider.gameFromFeed(raw)!!
        assertNotNull(NativeBaseball.parse(raw, match, 1000))
        assertNull(NativeBaseball.parse(raw, match.copy(home = match.home.copy(teamId = "mlb:999")), 1000))
        assertNull(NativeBaseball.parse(changed(raw, "gamePk" to JsonPrimitive("../../744834")), match, 1000))
    }
    @Test fun standingsDiscoverNativeLeaguesAndUseNativeColumnsAndProvenance(): Unit = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"leagues":[{"id":103,"active":true,"sport":{"id":1}},{"id":104,"active":true,"sport":{"id":1}},{"id":999,"active":false,"sport":{"id":1}}]}"""))
        server.enqueue(MockResponse().setBody("""{"records":[{"division":{"name":"American League East"},"teamRecords":[{"team":{"id":141,"name":"Toronto Blue Jays","abbreviation":"TOR"},"wins":5,"losses":3,"divisionRank":"1"}]}]}""")); server.start()
        try {
            val provider = NativeBaseballProvider(HttpGateway(OkHttpClient()), baseUrl = server.url("/").toString().trimEnd('/'), now = { Dates.parseIso("2026-10-23T16:00:00Z")!! })
            val group = provider.standings().single()
            assertEquals("MLB", group.source); assertEquals("mlb:141", group.rows.single().teamId)
            assertEquals("/api/v1/leagues", server.takeRequest().requestUrl!!.encodedPath)
            val request = server.takeRequest().requestUrl!!
            assertEquals("103,104", request.queryParameter("leagueId")); assertEquals("2026", request.queryParameter("season"))
            assertEquals("regularSeason", request.queryParameter("standingsTypes")); assertEquals("team,division", request.queryParameter("hydrate"))
        } finally { server.shutdown() }
    }
    @Test fun scheduleChunksBoundPayloadsUseExactCalendarWindowsAndCacheParsedGames(): Unit = runBlocking {
        val zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val server = MockWebServer(); server.enqueue(MockResponse().setBody(recorded().toString())); server.enqueue(MockResponse().setBody("""{"dates":[]}""")); server.start()
        try {
            val provider = NativeBaseballProvider(HttpGateway(OkHttpClient()), baseUrl = server.url("/").toString().trimEnd('/'), now = { 1000 })
            val start = Dates.parseIso("2024-06-01T12:00:00Z")!!
            val games = provider.schedule(start, 32); assertTrue(games.isNotEmpty()); assertEquals(games, provider.schedule(start, 32)); assertEquals(2, server.requestCount)
            val requests = listOf(server.takeRequest(), server.takeRequest()).sortedBy { it.requestUrl!!.queryParameter("startDate") }
            assertEquals("2024-06-01", requests[0].requestUrl!!.queryParameter("startDate")); assertEquals("2024-07-01", requests[0].requestUrl!!.queryParameter("endDate"))
            assertEquals("2024-07-02", requests[1].requestUrl!!.queryParameter("startDate")); assertTrue(requests.all { it.requestUrl!!.queryParameter("hydrate") == "team,broadcasts" })
        } finally { server.shutdown(); TimeZone.setDefault(zone) }
    }
    @Test fun rateLimitBlocksEveryProviderSurfaceUntilTheDeclaredCooldown(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "300")); server.start()
        try {
            val provider = NativeBaseballProvider(HttpGateway(OkHttpClient()), baseUrl = server.url("/").toString().trimEnd('/'), now = { 1000 })
            assertTrue(runCatching { provider.scores(null) }.isFailure); assertTrue(runCatching { provider.teams() }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun knownNativeGameLoadsDirectlyAndForeignProviderIdsAreNeverReused(): Unit = runBlocking {
        val server = MockWebServer(); val feed = javaClass.getResource("/parity/mlb-live-late.json")!!.readText(); server.enqueue(MockResponse().setBody(feed)); server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val base = server.url("/").toString().trimEnd('/')
            val match = NativeBaseballProvider.gameFromFeed(parseJson(feed))!!
            val details = NativeBaseball(http, base, { 1000 })
            assertEquals("744834", details.load(match, null)!!.providerStamp!!.gameId)
            assertEquals("/api/v1.1/game/744834/feed/live", server.takeRequest().path)
            assertNull(details.load(match.copy(nativeReference = NativeGameReference("NHL", "744834")), null)); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun nativeRosterAndPlayerFailuresCannotFallThroughToEspn(): Unit = runBlocking {
        var fallbackRequests = 0
        val client = OkHttpClient.Builder().addInterceptor { fallbackRequests++; throw IOException("No external test requests") }.build()
        val broken = object : SportsProvider {
            override val league = this@NativeBaseballProviderTest.league
            override val providerId = "MLB"
            override val capabilities = setOf(SportsCapability.Rosters, SportsCapability.Players)
            override suspend fun scores(date: Long?) = emptyList<Match>()
            override suspend fun schedule(start: Long, days: Int) = emptyList<Match>()
            override suspend fun roster(teamId: String): List<RosterGroup> = throw IOException("Unavailable")
            override suspend fun athlete(athlete: Athlete): AthleteOverview = throw IOException("Unavailable")
        }
        val repository = SportsRepository(mapOf(league.path to broken), EspnApi(HttpGateway(client)))
        assertTrue(runCatching { repository.roster(league, "mlb:141") }.isFailure)
        assertTrue(runCatching { repository.athlete(league, Athlete("mlb:1", "Example")) }.isFailure)
        assertEquals(0, fallbackRequests)
    }
}
