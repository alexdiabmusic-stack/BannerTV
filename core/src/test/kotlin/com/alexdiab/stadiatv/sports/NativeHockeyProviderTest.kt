package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.data.UserSettings
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
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Recorded score is identified separately. Edge cases/tables/rosters are explicitly constructed. */
class NativeHockeyProviderTest {
    private val league = requireNotNull(League.find("hockey/nhl"))
    private fun score() = parseJson(javaClass.getResource("/parity/nhl-score.recorded.json")!!.readText())
    private fun raw() = score()["games"][0]!!
    private fun game() = requireNotNull(NativeHockeyProvider.game(raw()))
    private fun changed(vararg fields: Pair<String, JsonElement>) = JsonObject(raw().jsonObject + fields)

    @Test fun recordedScoreMapsNativeNamespacedIdentitiesZeroScoreWinnerAndBroadcasts() {
        val game = game()
        assertEquals("nhl:2023020573", game.id); assertEquals("nhl:55", game.home.teamId)
        assertEquals("nhl:54", game.away.teamId); assertEquals("0–3", game.displayScore)
        assertTrue(game.home.isWinner); assertFalse(game.away.isWinner)
        assertEquals("NHL", game.nativeReference!!.provider); assertEquals("2023020573", game.nativeReference!!.gameId)
        assertEquals(GameState.FINAL, game.state); assertEquals("T-Mobile Park", game.venue)
        assertTrue("SN" in game.broadcasts); assertTrue(game.home.logoUrl!!.endsWith("SEA_light.svg"))
    }
    @Test fun malformedGamesAreNotInventedAndFutureGamesDoNotDisplayPrematureScores() {
        assertNull(NativeHockeyProvider.game(parseJson("{}")))
        assertNull(NativeHockeyProvider.game(changed("id" to JsonPrimitive("../../401"))))
        assertNull(NativeHockeyProvider.game(changed("startTimeUTC" to JsonPrimitive("not-a-date"))))
        val future = requireNotNull(NativeHockeyProvider.game(changed("gameState" to JsonPrimitive("FUT"))))
        assertEquals(GameState.PRE, future.state); assertNull(future.home.score); assertFalse(future.home.isWinner)
    }
    @Test fun statesDistinguishPostponedCancelledIntermissionOvertimeAndShootout() {
        assertEquals("Postponed", NativeHockeyProvider.game(changed("gameScheduleState" to JsonPrimitive("PPD")))!!.statusDetail)
        assertEquals("Cancelled", NativeHockeyProvider.game(changed("gameScheduleState" to JsonPrimitive("CNCL")))!!.statusDetail)
        val live = changed("gameState" to JsonPrimitive("LIVE"), "clock" to parseJson("""{"timeRemaining":"02:01","inIntermission":true}"""))
        assertEquals("Intermission · 3rd period", NativeHockeyProvider.game(live)!!.statusDetail)
        assertNull(NativeHockeyProvider.game(live)!!.live.clock)
        assertEquals("Final · 2OT", NativeHockeyProvider.game(changed("periodDescriptor" to parseJson("""{"number":5,"periodType":"OT","maxRegulationPeriods":3}""")))!!.statusDetail)
        assertEquals("Final · Shootout", NativeHockeyProvider.game(changed("periodDescriptor" to parseJson("""{"number":5,"periodType":"SO","maxRegulationPeriods":3}""")))!!.statusDetail)
    }
    @Test fun weeklyScheduleDatesUseEasternIdentityAndBoundTheSeasonAcrossDst() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val start = Dates.parseIso("2026-10-30T12:00:00-04:00")!!
            assertEquals(listOf("2026-10-30", "2026-11-06"), NativeHockeyProvider.scheduleDays(start, 14))
            assertEquals(53, NativeHockeyProvider.scheduleDays(start, 100_000).size)
            assertEquals(1, NativeHockeyProvider.scheduleDays(start, 0).size)
            assertEquals("2026-10-29", NativeHockeyProvider.day(Dates.parseIso("2026-10-30T01:00:00Z")!!))
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun scheduleFiltersExactLocalWindowAndDeduplicatesWeekOverlap() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val start = Dates.parseIso("2024-01-01T18:00:00-05:00")!!
            val atEnd = game().copy(id = "end", startMillis = Dates.parseIso("2024-01-03T00:00:00-05:00")!!)
            val before = game().copy(id = "before", startMillis = Dates.parseIso("2023-12-31T23:59:59-05:00")!!)
            assertEquals(listOf(game().id), NativeHockeyProvider.scheduleWindow(listOf(game(), game(), before, atEnd), start, 2).map { it.id })
        } finally { TimeZone.setDefault(old) }
    }
    private fun standingsRaw() = parseJson("""{"standings":[
        {"teamAbbrev":{"default":"OTT"},"teamName":{"default":"Ottawa Senators"},"teamCommonName":{"default":"Senators"},"divisionName":"Atlantic","divisionSequence":2,"wins":10,"losses":4,"otLosses":2,"points":22,"goalDifferential":7},
        {"teamAbbrev":{"default":"BOS"},"teamName":{"default":"Boston Bruins"},"divisionName":"Atlantic","divisionSequence":1,"wins":11,"losses":3,"otLosses":2,"points":24},
        {"teamAbbrev":{"default":"MIN"},"teamName":{"default":"Minnesota Wild"},"divisionName":"Central","divisionSequence":1,"points":18}] }""")
    @Test fun standingsUseDivisionSequenceAndReportedPointsWithNhlTeamIds() {
        val groups = NativeHockeyProvider.parseStandings(standingsRaw())
        assertEquals(listOf("Atlantic", "Central"), groups.map { it.name })
        assertEquals(listOf("nhl:6", "nhl:9"), groups.first().rows.map { it.teamId })
        assertEquals("10-4-2", groups.first().rows.last().record)
        assertEquals("22", groups.first().rows.last().values["points"])
        assertEquals("7", groups.first().rows.last().values["goaldifferential"])
        assertEquals("–", groups.last().rows.single().record)
    }
    @Test fun teamsAndSavedEspnFavouritesReconcileByLeagueScopedAliasesNotNumericCollision() {
        val teams = NativeHockeyProvider.parseTeams(standingsRaw())
        val ottawa = teams.single { it.abbreviation == "OTT" }
        val oldFavorite = FavoriteTeam(league.path, "14", "Ottawa Senators", "OTT")
        assertTrue(UserSettings(favoriteTeams = listOf(oldFavorite)).isFavorite(ottawa))
        val tampa = Team("nhl:14", "Tampa Bay Lightning", "Lightning", "TBL", null, league)
        assertFalse(oldFavorite.matches(tampa)) // Old ESPN 14 is not the NHL's team 14.
        assertTrue(FavoriteTeam(league.path, "legacy-tb", "Tampa Bay", "TB").matches(tampa))
        assertFalse(TeamIdentity.sameAbbreviation("basketball/nba", "TB", "TBL"))
    }
    @Test fun nativeRostersAndProfilesHaveNativePlayerIdentityAndReportedStatistics() {
        val roster = NativeHockeyProvider.parseRoster(parseJson("""{"forwards":[{"id":8478407,"firstName":{"default":"Vince"},"lastName":{"default":"Dunn"},"positionCode":"D","sweaterNumber":29,"headshot":"https://assets.nhle.com/player.png"}],"goalies":[{"id":0,"firstName":{"default":"Invalid"}}]}"""))
        val player = roster.single().athletes.single()
        assertEquals("nhl:8478407", player.id); assertEquals("Vince Dunn", player.name); assertEquals("29", player.jersey)
        val profile = NativeHockeyProvider.parsePlayer(parseJson("""{"playerId":8478407,"firstName":{"default":"Vince"},"lastName":{"default":"Dunn"},"position":"D","featuredStats":{"season":20232024,"regularSeason":{"subSeason":{"gamesPlayed":81,"points":64,"plusMinus":9}}},"birthDate":"1996-10-29","birthCity":{"default":"Mississauga"},"birthCountry":"CAN"}"""))
        assertEquals(player.id, profile.athlete.id); assertEquals("64", profile.stats["Points"])
        assertFalse(profile.stats.containsKey("Goals")); assertEquals("20232024", profile.bio["Season"])
    }
    @Test fun scoresCacheShapeChecksAnd429CooldownAreBounded(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(score().toString()))
            val provider = NativeHockeyProvider(HttpGateway(OkHttpClient()), baseUrl = server.url("/").toString().removeSuffix("/"), now = { 10L })
            assertEquals(1, provider.scores(1704110400000).size); assertEquals(1, provider.scores(1704110400000).size)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "300"))
            try { provider.scores(null); fail("Expected 429") } catch (error: HttpStatusException) { assertEquals(429, error.code) }
            try { provider.standings(); fail("Expected shared NHL cooldown") } catch (_: IOException) { }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun nativeIdentityFailuresNeverFallBackToEspn(): Unit = runBlocking {
        val calls = AtomicInteger()
        val http = HttpGateway(OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("No ESPN requests permitted") }.build())
        val provider = object : SportsProvider {
            override val league = this@NativeHockeyProviderTest.league
            override val providerId = "NHL"
            override val capabilities = setOf(SportsCapability.Rosters, SportsCapability.Players, SportsCapability.GameDetails)
            override suspend fun scores(date: Long?) = emptyList<Match>()
            override suspend fun schedule(start: Long, days: Int) = emptyList<Match>()
            override suspend fun roster(teamId: String): List<RosterGroup> = throw IOException("Native roster unavailable")
            override suspend fun athlete(athlete: Athlete): AthleteOverview = throw IOException("Native player unavailable")
            override suspend fun gameCentre(match: Match): GameCentre = throw IOException("Native game unavailable")
        }
        val repository = SportsRepository(mapOf(league.path to provider), EspnApi(http))
        try { repository.roster(league, "nhl:9"); fail("Expected native failure") } catch (_: IOException) { }
        try { repository.athlete(league, Athlete("nhl:8478407", "Vince Dunn")); fail("Expected native failure") } catch (_: IOException) { }
        try { repository.gameCentre(game()); fail("Expected native failure") } catch (_: IOException) { }
        try { repository.roster(league, "unknown:9"); fail("Expected unknown namespace rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls.get())
    }
    @Test fun nativeGameDetailsUseKnownIdWithoutScheduleLookup(): Unit = runBlocking {
        val server = MockWebServer(); val paths = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return MockResponse().setBody(raw().toString())
            }
        }; server.start()
        try {
            val details = NativeHockey(HttpGateway(OkHttpClient()), server.url("/").toString().removeSuffix("/"))
            val result = details.load(game(), null)!!
            assertEquals("2023020573", result.providerStamp!!.gameId)
            assertEquals(4, paths.size); assertTrue(paths.all { it.startsWith("/gamecenter/2023020573/") })
        } finally { server.shutdown() }
    }
    @Test fun nativeScheduleVisitsEveryWeekThenCachesTheRange(): Unit = runBlocking {
        val server = MockWebServer(); val paths = Collections.synchronizedList(mutableListOf<String>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += request.path.orEmpty()
                return MockResponse().setBody("""{"gameWeek":[{"games":[${raw()}]}]}""")
            }
        }; server.start()
        try {
            val provider = NativeHockeyProvider(HttpGateway(OkHttpClient()), baseUrl = server.url("/").toString().removeSuffix("/"), now = { 1L })
            val start = Dates.parseIso("2024-01-01T12:00:00-05:00")!!
            assertEquals(1, provider.schedule(start, 14).size); assertEquals(1, provider.schedule(start, 14).size)
            assertEquals(setOf("/schedule/2024-01-01", "/schedule/2024-01-08"), paths.toSet()); assertEquals(2, paths.size)
        } finally { server.shutdown() }
    }
}
