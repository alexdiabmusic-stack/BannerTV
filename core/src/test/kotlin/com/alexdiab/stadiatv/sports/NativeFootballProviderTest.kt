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

/** Native source shapes with the recorded game; constructed roster/standings/failure cases are not live data. */
class NativeFootballProviderTest {
    private val league = League.find("football/nfl")!!
    private val at = Dates.parseIso("2025-09-05T16:00:00Z")!!
    private fun fixture() = parseJson(javaClass.getResource("/parity/nfl-regular-final.json")!!.readText())
    private fun game() = NflCalendar.rows(fixture()).single()
    private val awayId get() = game()["awayTeam"]["id"].string()
    private val homeId get() = game()["homeTeam"]["id"].string()
    private fun teams() = parseJson("""{"teams":[{"id":"$awayId","fullName":"Dallas Cowboys","abbreviation":"DAL","divisionFullName":"NFC East"},{"id":"$homeId","fullName":"Philadelphia Eagles","abbreviation":"PHI","divisionFullName":"NFC East"}]}""")
    private fun week(number: Int = 1, begin: String = "2025-09-03", end: String = "2025-09-10") = """{"season":2025,"seasonType":"REG","week":$number,"dateBegin":"$begin","dateEnd":"$end"}"""
    private fun roster() = """{"rosters":[{"team":{"id":"$awayId"},"persons":[{"id":"native-player-1","displayName":"Reported Rookie","jerseyNumber":9,"position":"QB","positionGroup":"Offense","height":74,"weight":220,"collegeNames":["One College"],"nflExperience":0,"headshot":"https://static.www.nfl.com/{formatInstructions}/person/1"}]}]}"""
    private fun setup(extra: (RecordedRequest) -> MockResponse?): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse = extra(request) ?: when {
            request.path == "/identity/v3/token" -> MockResponse().setBody("""{"accessToken":"anonymous-test-token"}""")
            request.path!!.startsWith("/football/v2/weeks/date/") -> MockResponse().setBody(week())
            request.path!!.startsWith("/football/v2/teams/history") -> MockResponse().setBody(teams().toString())
            request.path!!.startsWith("/football/v2/rosters") -> MockResponse().setBody(roster())
            request.path!!.startsWith("/football/v2/experience/weekly-game-details") -> MockResponse().setBody(fixture().toString())
            else -> MockResponse().setResponseCode(404)
        } }; start()
    }
    private fun provider(server: MockWebServer): NativeFootballProvider = NativeFootballProvider(
        NativeFootball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), { at }), { at })

    @Test fun nativeTeamsRostersProfilesShareTheirProviderIdentityAndPreserveTypedOptionalBiography(): Unit = runBlocking {
        val server = setup { null }
        try {
            val provider = provider(server); val teams = provider.teams()
            assertEquals(listOf("nfl:$awayId", "nfl:$homeId"), teams.map { it.id })
            val player = provider.roster("nfl:$awayId").single().athletes.single()
            assertEquals("nfl:native-player-1", player.id); assertEquals("6′ 2″", player.height); assertEquals("220 lb", player.weight)
            assertEquals(0, player.experienceYears); assertEquals("One College", player.college); assertNull(player.age)
            assertTrue(player.headshotUrl!!.contains("/image/upload/f_png,w_192/"))
            val profile = provider.athlete(player)
            assertEquals("NFL", profile.bio["Source"]); assertEquals("2025", profile.bio["Roster season"])
            assertEquals("0", profile.stats["Experience"]); assertFalse(profile.stats.containsKey("Passing yards"))
            assertEquals(4, server.requestCount) // token, current week, metadata and one shared season roster
            val requests = (0 until server.requestCount).map { server.takeRequest() }.filter { it.path != "/identity/v3/token" }
            assertTrue(requests.all { it.getHeader("Cache-Control") == "no-store" && it.getHeader("Authorization") != null })
        } finally { server.shutdown() }
    }

    @Test fun defaultScoresUseNativeWeekButExplicitDatesFilterTheActualLocalDay(): Unit = runBlocking {
        val oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        val server = setup { null }
        try {
            val provider = provider(server); val all = provider.scores(null)
            assertEquals(1, all.size); assertTrue(all.single().home.teamId!!.startsWith("nfl:")); assertTrue(all.single().home.isWinner)
            assertEquals(LiveContext.EMPTY, all.single().live)
            assertEquals(1, provider.scores(Dates.parseIso("2025-09-04T16:00:00Z")!!).size)
            assertTrue(provider.scores(at).isEmpty())
        } finally { server.shutdown(); TimeZone.setDefault(oldZone) }
    }

    @Test fun nativeStandingsRetainReportedOrderRankColumnsAndDivisionMetadata() {
        val metadata = teams()["teams"].array.associateBy { it["id"].string() }
        val raw = parseJson("""{"weeks":[{"season":2025,"seasonType":"REG","week":2,"standings":[{"team":{"id":"$awayId"},"overall":{"wins":99}}]},
          {"season":2025,"seasonType":"REG","week":1,"standings":[{"team":{"id":"$homeId"},"overall":{"wins":1,"losses":0,"ties":0,"winPct":"1.000","games":1,"streak":{"type":"W","length":1},"points":{"for":24,"against":20}},"division":{"rank":1},"conference":{"rank":1}},
          {"team":{"id":"$awayId"},"overall":{"wins":0,"losses":1},"division":{"rank":2}}]}]}""")
        val group = NativeFootballProvider.parseStandings(raw, metadata, NflWeek(2025, NflSeasonType.REG, 1)).single()
        assertEquals("NFL", group.source); assertEquals("NFC East", group.name)
        assertEquals(listOf("nfl:$homeId", "nfl:$awayId"), group.rows.map { it.teamId })
        assertEquals("1.000", group.rows.first().values["winpercent"]); assertEquals("24", group.rows.first().values["pointsfor"])
        assertEquals("W1", group.rows.first().values["streak"]); assertFalse(group.rows.last().values.containsKey("ties"))
        assertEquals("2", group.rows.last().values["divisionrank"])
    }
    @Test fun alphabeticalStandingsAreReorderedOnlyByTheReportedDivisionRank() {
        val metadata = teams()["teams"].array.associateBy { it["id"].string() }
        val raw = parseJson("""{"weeks":[{"standings":[{"team":{"id":"$awayId"},"overall":{"wins":0,"losses":1},"division":{"rank":2}},
          {"team":{"id":"$homeId"},"overall":{"wins":1,"losses":0},"division":{"rank":1}}]}]}""")
        val group = NativeFootballProvider.parseStandings(raw, metadata, NflWeek(2025, NflSeasonType.REG, 1)).single()
        assertEquals(listOf("nfl:$homeId", "nfl:$awayId"), group.rows.map { it.teamId })
        val absent = parseJson("""{"weeks":[{"standings":[{"team":{"id":"$awayId"},"overall":{"wins":0}},{"team":{"id":"$homeId"},"overall":{"wins":99},"division":{"rank":1}}]}]}""")
        assertEquals(listOf("nfl:$awayId", "nfl:$homeId"), NativeFootballProvider.parseStandings(absent, metadata, NflWeek(2025, NflSeasonType.REG, 1)).single().rows.map { it.teamId })
    }

    @Test fun injuriesAdvanceUsingPageTokenAndKeepActualPeopleStatusAndTeams(): Unit = runBlocking {
        val server = setup { request -> if (request.path!!.startsWith("/football/v2/injuries")) {
            val second = request.requestUrl!!.queryParameter("pageToken") != null
            MockResponse().setBody("""{"injuries":[{"person":{"id":"player-${if (second) 2 else 1}","displayName":"Reported Player"},"team":{"id":"$awayId"},"position":"QB","injuryStatus":"questionable","injuries":["Ankle"]}]${if (second) "" else ",\"pagination\":{\"token\":\"cursor/with +symbols\"}"}}""")
        } else null }
        try {
            val values = provider(server).injuries(); assertEquals(2, values.size)
            assertTrue(values.all { it.source == "NFL" && it.team == "DAL" && it.status == "Questionable" && it.detail == "Ankle" && it.athlete.id.startsWith("nfl:") })
            val requests = (0 until server.requestCount).map { server.takeRequest() }.filter { it.path!!.startsWith("/football/v2/injuries") }
            assertEquals(2, requests.size); assertNull(requests.first().requestUrl!!.queryParameter("pageToken"))
            assertEquals("cursor/with +symbols", requests.last().requestUrl!!.queryParameter("pageToken")); assertNull(requests.last().requestUrl!!.queryParameter("token"))
        } finally { server.shutdown() }
    }

    @Test fun repeatedInjuryCursorFailsRatherThanReturningAnIncompleteReport(): Unit = runBlocking {
        val server = setup { request -> if (request.path!!.startsWith("/football/v2/injuries")) MockResponse().setBody("""{"injuries":[],"pagination":{"token":"repeat"}}""") else null }
        try { assertTrue(runCatching { provider(server).injuries() }.exceptionOrNull() is IOException); assertEquals(5, server.requestCount) }
        finally { server.shutdown() }
    }

    @Test fun calendarTraversalUsesAnnouncedExclusiveWeekEndsRatherThanOneRequestPerDay(): Unit = runBlocking {
        val oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        val server = setup { request -> when {
            request.path!!.startsWith("/football/v2/weeks/date/") -> MockResponse().setBody(when (request.path!!.substringAfterLast('/')) {
                "2025-09-04" -> week(); "2025-09-10" -> week(2, "2025-09-10", "2025-09-17"); else -> week(3, "2025-09-17", "2025-09-24")
            })
            request.path!!.startsWith("/football/v2/experience/weekly-game-details") -> {
                val number = request.requestUrl!!.queryParameter("week")!!.toInt()
                val original = game(); val id = "game-$number"
                val updated = JsonObject(original.jsonObject + mapOf("id" to JsonPrimitive(id), "week" to JsonPrimitive(number), "time" to JsonPrimitive("2025-09-${if (number == 1) "05" else if (number == 2) "12" else "19"}T00:20:00Z"),
                    "summary" to JsonObject(original["summary"]!!.jsonObject + ("gameId" to JsonPrimitive(id)))))
                MockResponse().setBody(JsonArray(listOf(updated)).toString())
            }
            else -> null
        } }
        try {
            val values = provider(server).schedule(Dates.parseIso("2025-09-04T16:00:00Z")!!, 14)
            assertEquals(listOf("nfl:game-1", "nfl:game-2"), values.map { it.id })
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertEquals(3, requests.count { it.path!!.startsWith("/football/v2/weeks/date/") })
            assertEquals(3, requests.count { it.path!!.startsWith("/football/v2/experience/weekly-game-details") })
            assertTrue(requests.filter { it.path!!.startsWith("/football/v2/experience/weekly-game-details") }.all { it.requestUrl!!.queryParameter("includeDriveChart") == "false" })
        } finally { server.shutdown(); TimeZone.setDefault(oldZone) }
    }

    @Test fun malformedWeekBoundariesAndForeignNativeIdentitiesAreNeverGuessed(): Unit = runBlocking {
        assertNull(NativeFootballProvider.weekEnd(parseJson(week(end = "2025-08-10")), at))
        assertNull(NativeFootballProvider.weekEnd(parseJson(week(end = "2025-12-10")), at))
        assertNull(NativeFootballProvider.rawId("nfl:../../private")); assertNull(NativeFootballProvider.rawId("nhl:1"))
        assertNull(NativeFootballProvider.person(parseJson("""{"id":"../../one","displayName":"Bad"}""")))
        val server = setup { null }
        try {
            val client = NativeFootball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), { at })
            val match = NflCalendar.matches(fixture(), NflWeek(2025, NflSeasonType.REG, 1)).single()
            assertNull(client.load(match.copy(nativeReference = NativeGameReference("MLB", "1")), null)); assertEquals(0, server.requestCount)
            assertFalse(NativeFootball.teamMatches(game()["homeTeam"], match.home.copy(teamId = "nfl:another-team")))
        } finally { server.shutdown() }
    }

    @Test fun legacyFollowsResolveLeagueScopedAliasesWithoutMixingNativeNumbers() {
        val favorite = FavoriteTeam(league.path, "30", "Jacksonville Jaguars", "JAC")
        assertTrue(favorite.matches(Team("nfl:native-jax", "Jacksonville Jaguars", "Jaguars", "JAX", null, league)))
        assertFalse(favorite.matches(Team("nfl:30", "Dallas Cowboys", "Cowboys", "DAL", null, league)))
        assertTrue(TeamIdentity.sameAbbreviation(league.path, "WSH", "WAS")); assertFalse(TeamIdentity.sameAbbreviation("hockey/nhl", "JAC", "JAX"))
        assertTrue(TeamIdentity.sameAbbreviation(league.path, "AZ", "ARI")); assertFalse(TeamIdentity.sameAbbreviation("baseball/mlb", "AZ", "ARI"))
    }
    @Test fun finalDetailCorrectionsRecomputeWinnerAndBoxPlayersHaveNativeProfileIds() {
        val original = NflCalendar.matches(fixture(), NflWeek(2025, NflSeasonType.REG, 1)).single()
        val wrong = original.copy(home = original.home.copy(isWinner = false), away = original.away.copy(isWinner = true))
        val data = NativeFootball.parse(game(), wrong, at)!!
        assertTrue(data.liveMatch!!.home.isWinner); assertFalse(data.liveMatch!!.away.isWinner)
        assertTrue(data.boxScore.flatMap { it.groups }.flatMap { it.rows }.isNotEmpty())
        assertTrue(data.boxScore.flatMap { it.groups }.flatMap { it.rows }.all { it.athleteId!!.startsWith("nfl:") })
    }
    @Test fun liveDetailRefreshReusesItsSmallSeasonNameDirectoryInsteadOfDecodingRosterAgain(): Unit = runBlocking {
        val server = setup { null }
        try {
            val http = HttpGateway(OkHttpClient())
            val client = NativeFootball(http, server.url("/").toString().trimEnd('/'), { at })
            val match = NflCalendar.matches(fixture(), NflWeek(2025, NflSeasonType.REG, 1)).single()
            val first = client.load(match, null)!!
            http.invalidate() // Even without a cached response body, player-name mapping remains a bounded typed cache.
            assertNotNull(client.load(match, first))
            val requests = (0 until server.requestCount).map { server.takeRequest() }
            assertEquals(1, requests.count { it.path!!.startsWith("/football/v2/rosters") })
            assertEquals(2, requests.count { it.path!!.startsWith("/football/v2/experience/weekly-game-details") })
        } finally { server.shutdown() }
    }

    @Test fun qualifiedNativeFailuresNeverFallThroughToEspnAndBareLegacyIdsRemainEspn(): Unit = runBlocking {
        var espnRequests = 0
        val espn = EspnApi(HttpGateway(OkHttpClient.Builder().addInterceptor { espnRequests++; throw IOException("No external test requests") }.build()))
        val server = setup { request -> if (request.path!!.startsWith("/football/v2/rosters")) MockResponse().setResponseCode(503) else null }
        try {
            val repository = SportsRepository(mapOf(league.path to provider(server)), espn)
            assertTrue(runCatching { repository.roster(league, "nfl:$awayId") }.isFailure); assertEquals(0, espnRequests)
            assertTrue(runCatching { repository.athlete(league, Athlete("nfl:player", "Reported")) }.isFailure); assertEquals(0, espnRequests)
            val nativeCount = server.requestCount
            assertTrue(runCatching { repository.roster(league, "1") }.isFailure); assertTrue(espnRequests > 0)
            val afterRoster = espnRequests
            assertTrue(runCatching { repository.athlete(league, Athlete("2", "Legacy")) }.isFailure); assertTrue(espnRequests > afterRoster); assertEquals(nativeCount, server.requestCount)
        } finally { server.shutdown() }
    }
}
