package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Source-labelled constructed fixtures, never assertions about real games/current rosters. */
class NativeBasketballProviderTest {
    private val nba = NativeBasketballLeague.NBA
    private val wnba = NativeBasketballLeague.WNBA
    private val at = Dates.parseIso("2026-01-15T20:00:00Z")!!
    private val summer = Dates.parseIso("2026-07-10T20:00:00Z")!!
    private fun fixture(name: String) = javaClass.getResource("/parity/$name.constructed.json")!!.readText()
    private fun setup(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }; start()
    }
    private fun client(server: MockWebServer, config: NativeBasketballLeague = nba, now: () -> Long = { at }): NativeBasketballClient =
        NativeBasketballClient(config, HttpGateway(OkHttpClient()), now, server.url("/cdn").toString(), server.url("/stats-host").toString())
    private fun game() = parseJson(fixture("nba-schedule-cdn"))["leagueSchedule"]["gameDates"][0]["games"][0]!!
    private fun table(headers: List<String>, rows: List<List<JsonElement>>, name: String = "SeasonTotalsRegularSeason"): JsonObject = buildJsonObject {
        put("resultSets", buildJsonArray { add(buildJsonObject {
            put("name", name); put("headers", JsonArray(headers.map(::JsonPrimitive))); put("rowSet", JsonArray(rows.map(::JsonArray)))
        }) })
    }

    @Test fun qualifiedGameTeamsActualScoreFinalWinnerAndNoFinalClockAreMapped() {
        val match = NativeBasketballMapping.game(game(), nba)!!
        assertEquals("nba:0022500500", match.id); assertEquals(NativeGameReference("NBA", "0022500500"), match.nativeReference)
        assertEquals("nba:1610612747", match.home.teamId); assertEquals("110", match.home.score)
        assertTrue(match.home.isWinner); assertFalse(match.away.isWinner); assertEquals(LiveContext.EMPTY, match.live)
        assertNull(NativeBasketballMapping.game(JsonObject(game().jsonObject - "gameDateTimeUTC"), nba))
        assertNull(NativeBasketballMapping.game(JsonObject(game().jsonObject + ("gameId" to JsonPrimitive(22500500))), nba))
    }

    @Test fun pregameZerosAreNotScoresAndTextInterruptionsOverrideNumericLiveStatus() {
        fun modified(status: Int, text: String) = JsonObject(game().jsonObject + mapOf("gameStatus" to JsonPrimitive(status), "gameStatusText" to JsonPrimitive(text)))
        val pre = NativeBasketballMapping.game(modified(1, "7:00 pm ET"), nba)!!
        assertNull(pre.home.score); assertFalse(pre.home.isWinner); assertEquals(LiveContext.EMPTY, pre.live)
        assertEquals(GameState.PRE to "Postponed", NativeBasketballMapping.state(modified(2, "PPD")))
        assertEquals(GameState.LIVE to "Suspended", NativeBasketballMapping.state(modified(2, "Suspended")))
        assertEquals(GameState.LIVE to "Halftime", NativeBasketballMapping.state(modified(2, "Halftime")))
        assertEquals("Final · 2OT", NativeBasketballMapping.state(JsonObject(modified(3, "Final").jsonObject + ("period" to JsonPrimitive(6)))).second)
    }

    @Test fun nbaSeasonAndSlateDayUseEasternBoundaryAndWnbaUsesCalendarYear() {
        val boundary = Dates.parseIso("2026-08-01T02:00:00Z")!!
        assertEquals("2025-26", nba.season(boundary)); assertEquals("2026-27", nba.season(boundary + 3 * 3_600_000))
        assertEquals("2026-07-31", nba.day(boundary)); assertEquals("2026", wnba.season(boundary))
        assertNull(nba.rawId("nba:../1")); assertNull(nba.rawId("wnba:2544")); assertNull(nba.rawId("2544"))
    }
    @Test fun recordedWnbaScheduleUsesRealDateTimeInsteadOfThe1900ClockPlaceholder() {
        val rows = parseJson(javaClass.getResource("/parity/wnba-schedule-dates.recorded.json")!!.readText()).array
        assertEquals(listOf("2026-10-21", "2026-10-29", "2026-10-31"), rows.map { wnba.day(NativeBasketballMapping.start(it)!!) })
        val known = rows.first()
        assertEquals(Dates.parseIso("2026-10-22T00:00:00Z"), NativeBasketballMapping.start(known))
        assertNull(NativeBasketballMapping.start(JsonObject(known.jsonObject - "gameDateTimeUTC" - "gameDateUTC")))
        val box = JsonObject(known.jsonObject + mapOf("gameDateTimeUTC" to JsonPrimitive("1900-01-01T00:00:00Z"), "gameTimeUTC" to JsonPrimitive("2026-10-22T00:00:00Z")))
        assertEquals(Dates.parseIso("2026-10-22T00:00:00Z"), NativeBasketballMapping.start(box))
    }
    @Test fun aMalformedHistoricalScheduleCannotBeReportedAsAnEmptySuccessfulSlate(): Unit = runBlocking {
        val content = fixture("nba-schedule-cdn").replace("2026-01-15T19:30:00Z", "not-a-date").replace("2026-01-17T00:00:00Z", "not-a-date")
        val server = setup { MockResponse().setBody(content) }
        try { assertTrue(runCatching { client(server).scoreboard(at + 86_400_000) }.exceptionOrNull() is IOException) }
        finally { server.shutdown() }
    }

    @Test fun allBundledAssetAliasesResolveToExistingFilesButUnknownFranchisesAreNotInvented() {
        for (config in listOf(nba, wnba)) {
            val directory = File("src/main/assets/team-logos/${config.prefix}")
            val files = directory.listFiles()!!.filter { it.extension in setOf("svg", "png") }
            assertEquals(if (config == nba) 30 else 15, files.size)
            files.forEach { assertEquals(it.name, config.logo(it.nameWithoutExtension)!!.substringAfterLast('/')) }
        }
        assertEquals(nba.logo("WAS"), nba.logo("WSH")); assertEquals(nba.logo("GSW"), nba.logo("GS"))
        assertEquals(wnba.logo("NY"), wnba.logo("NYL")); assertEquals(wnba.logo("LV"), wnba.logo("LVA"))
        assertEquals(wnba.logo("CONN"), wnba.logo("CON")); assertEquals(wnba.logo("LAS"), wnba.logo("LA"))
        assertNull(wnba.logo("NEW")); assertNull(nba.logo("../LAL"))
    }

    @Test fun todayUsesOnlyLeagueCdnAndNonTodaySlatesReuseTheOneCompactSchedule(): Unit = runBlocking {
        val server = setup { request -> MockResponse().setBody(if (request.path!!.contains("todaysScoreboard")) """{"scoreboard":{"games":[]}}""" else fixture("nba-schedule-cdn")) }
        try {
            val client = client(server); assertTrue(client.scoreboard(at).isEmpty())
            assertEquals(1, client.scoreboard(at + 86_400_000).size)
            val provider = NativeBasketballProvider(client, { at }); assertEquals(4, provider.teams().size)
            assertEquals(listOf("nba:0022500501"), provider.schedule(at + 86_400_000, 1).map { it.id })
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().path!!.contains("todaysScoreboard_00.json"))
            assertTrue(server.takeRequest().path!!.contains("scheduleLeagueV2_1.json"))
        } finally { server.shutdown() }
    }

    @Test fun nbaHttpFailureCanUseStatsButSuccessfulInvalidSchemaMustNot(): Unit = runBlocking {
        val server = setup { request -> if (request.path!!.startsWith("/cdn")) MockResponse().setResponseCode(403) else MockResponse().setBody("""{"scoreboard":{"games":[]}}""") }
        try {
            assertTrue(client(server).scoreboard(at).isEmpty()); assertEquals(2, server.requestCount)
            server.takeRequest(); val stats = server.takeRequest()
            assertEquals("00", stats.requestUrl!!.queryParameter("LeagueID")); assertEquals("2026-01-15", stats.requestUrl!!.queryParameter("GameDate"))
            assertEquals("no-store", stats.getHeader("Cache-Control")); assertEquals("https://www.nba.com/", stats.getHeader("Referer"))
        } finally { server.shutdown() }
        val bad = setup { MockResponse().setBody("""{"notAScoreboard":{}}""") }
        try { assertTrue(runCatching { client(bad).scoreboard(at) }.isFailure); assertEquals(1, bad.requestCount) } finally { bad.shutdown() }
    }

    @Test fun historicalNbaUsesStatsDateAndWnbaNeverPretendsToHaveHistoricalScores(): Unit = runBlocking {
        val server = setup { MockResponse().setBody("""{"scoreboard":{"games":[]}}""") }
        try {
            assertTrue(client(server).scoreboard(Dates.parseIso("2024-01-15T20:00:00Z")!!).isEmpty())
            assertTrue(server.takeRequest().path!!.startsWith("/stats-host/stats/scoreboardv3"))
            assertTrue(runCatching { client(server, wnba, { summer }).scoreboard(Dates.parseIso("2025-07-10T20:00:00Z")!!) }.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun staleTodaysFileMustReconcileAgainstScheduleRatherThanReportingOldGamesOrFalseEmpty(): Unit = runBlocking {
        val server = setup { request -> MockResponse().setBody(if (request.path!!.contains("todaysScoreboard"))
            """{"scoreboard":{"gameDate":"2026-01-14","games":[]}}""" else fixture("nba-schedule-cdn")) }
        try {
            val matches = NativeBasketballProvider(client(server), { at }).scores(null)
            assertEquals(listOf("nba:0022500500"), matches.map { it.id }); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
        val unavailable = setup { request -> if (request.path!!.contains("todaysScoreboard")) MockResponse().setBody("""{"scoreboard":{"gameDate":"2026-07-09","games":[]}}""") else MockResponse().setResponseCode(503) }
        try {
            assertTrue(runCatching { client(unavailable, wnba, { summer }).scoreboard(summer) }.isFailure)
            assertEquals(2, unavailable.requestCount)
        } finally { unavailable.shutdown() }
    }

    @Test fun wnbaBlockedLiveEndpointsNeverReachStatsAndHonorTheHostCooldown(): Unit = runBlocking {
        var tick = summer
        val server = setup { MockResponse().setResponseCode(429).addHeader("Retry-After", "600") }
        try {
            val client = client(server, wnba, { tick })
            assertTrue(runCatching { client.scoreboard(summer) }.exceptionOrNull() is HttpStatusException)
            assertTrue(runCatching { client.plays("1022600050") }.exceptionOrNull() is HttpStatusException)
            assertEquals(1, server.requestCount)
            tick += 600_001
            assertTrue(runCatching { client.plays("1022600050") }.isFailure); assertEquals(2, server.requestCount)
            assertTrue(runCatching { client.stats("playbyplayv3", emptyMap(), 0) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun wnbaTeamsAreDerivedFromAnnouncedScheduleIncludingAnUnknownExpansionTeam(): Unit = runBlocking {
        val content = fixture("wnba-schedule-cdn").replace("New York", "New City").replace("Liberty", "Expansion").replace("NYL", "NEW")
        val server = setup { MockResponse().setBody(content) }
        try {
            val provider = NativeBasketballProvider(client(server, wnba, { summer }), { summer })
            val expansion = provider.teams().single { it.abbreviation == "NEW" }
            assertEquals("wnba:1611661321", expansion.id); assertNull(expansion.logoUrl)
            assertEquals(4, provider.teams().size); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun namedStandingsPreserveReportedRankAndDoNotInferTiesFromWinPercent() {
        val rows = NativeBasketballMapping.table(parseJson(fixture("nba-standings-v3")), "Standings")
        val groups = NativeBasketballProvider.standingsRows(rows, nba)
        assertEquals(listOf("East", "West"), groups.map { it.name }); assertTrue(groups.all { it.source == "NBA" })
        assertEquals("3", groups.last().rows.single().values["leaguerank"]); assertEquals("40", groups.first().rows.single().values["gamesplayed"])
        val unranked = JsonObject(rows.first().jsonObject - "PlayoffRank")
        val group = NativeBasketballProvider.standingsRows(listOf(unranked, JsonObject(rows.last().jsonObject + ("Conference" to JsonPrimitive("East")))), nba).single()
        assertEquals(listOf("BOS", "LAL"), group.rows.map { it.abbreviation })
    }

    @Test fun rosterBiographyUsesNativePlayerIdsAndRejectsForeignTeamAndSeason(): Unit = runBlocking {
        var content = fixture("nba-roster")
        val server = setup { MockResponse().setBody(content) }
        try {
            val provider = NativeBasketballProvider(client(server), { at })
            val athlete = provider.roster("nba:1610612747").flatMap { it.athletes }.first { it.id == "nba:2544" }
            assertEquals("6-9", athlete.height); assertEquals("250 lb", athlete.weight); assertEquals(41, athlete.age); assertEquals(22, athlete.experienceYears)
            val request = server.takeRequest(); assertEquals("2025-26", request.requestUrl!!.queryParameter("Season")); assertEquals("1610612747", request.requestUrl!!.queryParameter("TeamID"))
            content = content.replace("1610612747", "1610612738")
            assertTrue(runCatching { NativeBasketballProvider(client(server), { at }).roster("nba:1610612747") }.exceptionOrNull() is IOException)
            content = fixture("nba-roster").replace("2025-26", "2024-25")
            assertTrue(runCatching { NativeBasketballProvider(client(server), { at }).roster("nba:1610612747") }.exceptionOrNull() is IOException)
            assertTrue(runCatching { provider.roster("wnba:1610612747") }.isFailure); assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun playerCareerChoosesLatestActualSeasonAndTradedTotalRatherThanTheLastTeam(): Unit = runBlocking {
        val headers = listOf("PLAYER_ID", "SEASON_ID", "TEAM_ABBREVIATION", "PTS", "REB", "AST", "FG_PCT")
        fun row(season: String, team: String, pts: Int) = listOf(JsonPrimitive(2544), JsonPrimitive(season), JsonPrimitive(team), JsonPrimitive(pts), JsonPrimitive(8), JsonPrimitive(7), JsonPrimitive(.5))
        val body = table(headers, listOf(row("2024-25", "TOT", 25), row("2024-25", "LAL", 22), row("2023-24", "LAL", 26)))
        val server = setup { MockResponse().setBody(body.toString()) }
        try {
            val person = Athlete("nba:2544", "Reported player", height = "6-9")
            val profile = NativeBasketballProvider(client(server), { at }).athlete(person)
            assertEquals("2024-25", profile.bio["Statistics season"]); assertEquals("25", profile.headlineStats["Points"])
            assertEquals("6-9", profile.athlete.height); assertEquals("0.5", profile.stats["Field goal fraction"])
            assertFalse(profile.statlineLabel!!.contains("2025-26"))
        } finally { server.shutdown() }
    }

    @Test fun malformedOrAmbiguousColumnTablesDoNotBecomeEmptySuccess() {
        assertTrue(runCatching { NativeBasketballMapping.table(parseJson("{}"), "Standings") }.isFailure)
        val duplicate = table(listOf("PLAYER_ID", "PLAYER_ID"), listOf(listOf(JsonPrimitive(1), JsonPrimitive(2))))
        assertTrue(runCatching { NativeBasketballMapping.table(duplicate, "SeasonTotalsRegularSeason") }.isFailure)
        val short = table(listOf("PLAYER_ID", "PTS"), listOf(listOf(JsonPrimitive(1))))
        assertNull(NativeBasketballMapping.table(short, "SeasonTotalsRegularSeason").single()["PTS"])
    }

    @Test fun qualifiedIdentityFailureNeverFallsThroughToEspnButLegacyBareIdsStayEspn(): Unit = runBlocking {
        var espnCount = 0
        val espn = EspnApi(HttpGateway(OkHttpClient.Builder().addInterceptor { espnCount++; throw IOException("No external test traffic") }.build()))
        val server = setup { MockResponse().setResponseCode(503) }
        try {
            val repository = SportsRepository(mapOf(nba.league.path to NativeBasketballProvider(client(server), { at })), espn)
            assertTrue(runCatching { repository.roster(nba.league, "nba:1610612747") }.isFailure); assertEquals(0, espnCount)
            assertTrue(runCatching { repository.athlete(nba.league, Athlete("nba:2544", "Reported")) }.isFailure); assertEquals(0, espnCount)
            assertTrue(runCatching { repository.roster(nba.league, "13") }.isFailure); assertTrue(espnCount > 0)
        } finally { server.shutdown() }
    }
}
