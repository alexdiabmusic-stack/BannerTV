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
import java.io.IOException

/** Source fixtures plus explicitly constructed network/identity cases, never runtime seed data. */
class NativePremierLeagueProviderTest {
    private val at = Dates.parseIso("2026-10-23T17:00:00Z")!!
    private fun fixture(name: String) = javaClass.getResource("/parity/epl-match-$name.source.json")!!.readText()
    private fun game() = parseJson(fixture("fulltime"))
    private fun setup(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }; start()
    }
    private fun client(server: MockWebServer, now: () -> Long = { at }) = NativePremierLeagueClient(HttpGateway(OkHttpClient()), server.url("/").toString(), now)
    private fun page(rows: List<JsonElement>, next: String? = null): String = buildJsonObject {
        put("data", JsonArray(rows)); put("pagination", buildJsonObject { next?.let { put("_next", it) } })
    }.toString()

    @Test fun sourceFullTimeHasQualifiedIdentityRealLondonKickoffAndFinalWinner() {
        val match = NativePremierLeagueMapping.game(game())!!
        assertEquals("epl:2645241", match.id); assertEquals("epl:43", match.home.teamId)
        assertEquals(NativeGameReference("EPL", "2645241", 2026), match.nativeReference)
        assertEquals(Dates.parseIso("2026-09-20T13:00:00Z"), match.startMillis)
        assertEquals("5", match.home.score); assertTrue(match.home.isWinner); assertFalse(match.away.isWinner)
        assertEquals(LiveContext.EMPTY, match.live); assertEquals("Full time", match.statusDetail)
    }
    @Test fun sourcePregameHasNoInventedScoresOrWinnerAndStoppageDependsOnHalf() {
        val pre = NativePremierLeagueMapping.game(parseJson(fixture("prematch")))!!
        assertNull(pre.home.score); assertNull(pre.away.score); assertFalse(pre.home.isWinner)
        assertEquals("45+2′", NativePremierLeagueMapping.game(parseJson(fixture("firsthalf-stoppage")))!!.statusDetail)
        assertEquals("90+4′", NativePremierLeagueMapping.game(parseJson(fixture("secondhalf-stoppage")))!!.statusDetail)
        assertEquals("Half-time", NativePremierLeagueMapping.status("HalfTime", "47").label)
        assertEquals("UnknownFutureState", NativePremierLeagueMapping.status("UnknownFutureState", "94").label)
        assertEquals(GameState.PRE, NativePremierLeagueMapping.status("UnknownFutureState", "94").state)
    }
    @Test fun interruptionsOverrideClockAndCrossCompetitionIdentityAndInvalidDatesAreRejected() {
        assertEquals("Postponed", NativePremierLeagueMapping.status("Postponed", "94").label)
        assertEquals("Suspended", NativePremierLeagueMapping.status("Suspended", "94").label)
        assertNull(NativePremierLeagueMapping.game(JsonObject(game().jsonObject + ("competitionId" to JsonPrimitive("99")))))
        assertNull(NativePremierLeagueMapping.kickoff("2026-02-30 12:00:00"))
        assertNull(NativePremierLeagueMapping.kickoff("2026-10-23 12:00:00junk"))
        assertNull(NativePremierLeagueMapping.rawId("mls:43")); assertNull(NativePremierLeagueMapping.rawId("43"))
        assertNull(NativePremierLeagueMapping.rawId("epl:../43")); assertNull(NativePremierLeagueMapping.rawId("epl:0"))
    }
    @Test fun seasonUsesActualIosJulyBoundaryAndLondonDayRatherThanUtcDay() {
        val june = Dates.parseIso("2026-06-30T22:30:00Z")!!
        assertEquals(2025, NativePremierLeagueMapping.season(june))
        assertEquals(2026, NativePremierLeagueMapping.season(june + 3_600_000))
        assertEquals("2026-07-01", NativePremierLeagueMapping.day(june + 3_600_000))
    }
    @Test fun pagedScheduleUsesOpaqueCursorDeduplicatesAndFiltersRequestedLondonRange(): Unit = runBlocking {
        val server = setup { request ->
            if (request.requestUrl!!.queryParameter("_next") == null) MockResponse().setBody(page(listOf(game()), "opaque+cursor/=next"))
            else MockResponse().setBody(page(listOf(game(), parseJson(fixture("prematch")))))
        }
        try {
            val result = NativePremierLeagueProvider(client(server)).schedule(Dates.parseIso("2026-09-20T00:00:00Z")!!, 1)
            assertEquals(listOf("epl:2645241"), result.map { it.id }); assertEquals(2, server.requestCount)
            val first = server.takeRequest(); val next = server.takeRequest()
            assertEquals("8", first.requestUrl!!.queryParameter("competition"))
            assertEquals("2026-09-19", first.requestUrl!!.queryParameter("kickoff>"))
            assertEquals("opaque+cursor/=next", next.requestUrl!!.queryParameter("_next"))
            assertEquals("application/json", first.getHeader("Accept"))
        } finally { server.shutdown() }
    }
    @Test fun repeatedCursorMalformedEnvelopeAndForeignSeasonDoNotBecomeSuccessfulEmptySchedules(): Unit = runBlocking {
        val foreign = JsonObject(game().jsonObject + ("season" to JsonPrimitive("2025")))
        for (body in listOf("{}", page(listOf(foreign)), page(emptyList(), "same"))) {
            val server = setup { MockResponse().setBody(body) }
            try {
                assertTrue(runCatching { NativePremierLeagueProvider(client(server), { at }).scores(at) }.exceptionOrNull() is IOException)
                assertTrue(server.requestCount <= 2)
            } finally { server.shutdown() }
        }
    }
    @Test fun emptyValidPageIsEmptyAndUndecodableMatchIsNotHidden(): Unit = runBlocking {
        val server = setup { request -> MockResponse().setBody(if (request.requestUrl!!.queryParameter("season") == "2026") page(emptyList()) else "{}") }
        try { assertTrue(NativePremierLeagueProvider(client(server), { at }).scores(at).isEmpty()) } finally { server.shutdown() }
        val broken = setup { MockResponse().setBody(page(listOf(JsonObject(game().jsonObject - "kickoff")))) }
        try { assertTrue(runCatching { NativePremierLeagueProvider(client(broken), { at }).scores(at) }.exceptionOrNull() is IOException) } finally { broken.shutdown() }
    }
    @Test fun gatewayRateLimitIsSharedAcrossResourcesAndDoesNotRetryImmediately(): Unit = runBlocking {
        var clock = at
        val server = setup { MockResponse().setResponseCode(429).addHeader("Retry-After", "300") }
        try {
            val client = client(server) { clock }
            assertTrue(runCatching { client.get("/api/v2/matches/1") }.exceptionOrNull() is HttpStatusException)
            assertTrue(runCatching { client.get("/api/v1/competitions/8/seasons/2026/teams") }.exceptionOrNull() is IOException)
            assertEquals(1, server.requestCount)
            clock += 300_001
            assertTrue(runCatching { client.get("/api/v2/matches/1") }.exceptionOrNull() is HttpStatusException)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun directoryIsDiscoveredNotAClubAllowlistAndSquadsUseNativeSeasonAndIdentity(): Unit = runBlocking {
        val server = setup { request -> MockResponse().setBody(if (request.requestUrl!!.encodedPath.endsWith("squad"))
            """{"teamId":"999","players":[{"id":"701","name":{"display":"New Player"},"shirtNum":"9","position":"Forward","country":{"country":"Canada"}}]}"""
            else """{"data":[{"id":"999","name":"New Promoted Club","shortName":"New Club","abbr":"NEW"}]}""") }
        try {
            val provider = NativePremierLeagueProvider(client(server), { at })
            assertEquals("epl:999", provider.teams().single().id)
            val person = provider.roster("epl:999").single().athletes.single()
            assertEquals("epl:701", person.id); assertEquals("New Player", person.name); assertEquals("Canada", person.details)
            assertTrue(runCatching { provider.roster("999") }.exceptionOrNull() is IOException)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun officialTableUsesPublishedRankPointsAndMissingCellsNotDerivedTiebreakers(): Unit = runBlocking {
        val server = setup { MockResponse().setBody("""{"tables":[{"entries":[{"team":{"id":"1","name":"Club One","abbr":"ONE"},"overall":{"position":2,"played":10,"points":15,"won":4,"drawn":3,"lost":3,"goalsFor":14,"goalsAgainst":10}},{"team":{"id":"2","name":"Club Two","abbr":"TWO"},"overall":{"position":1,"points":20}}]}]}""") }
        try {
            val group = NativePremierLeagueProvider(client(server), { at }).standings().single()
            assertEquals("EPL", group.source); assertEquals("epl:2", group.rows.first().teamId)
            assertEquals("20", group.rows.first().values["points"]); assertNull(group.rows.first().values["wins"])
            assertEquals("4", group.rows.last().values["pointdifferential"])
            assertEquals("false", server.takeRequest().requestUrl!!.queryParameter("live"))
        } finally { server.shutdown() }
    }
    @Test fun playerStatsPreserveSelectedAthleteAndReportedCellsRatherThanInventingMissingStats(): Unit = runBlocking {
        val server = setup { MockResponse().setBody("""{"playerId":"701","season":"2026","stats":{"goals":4,"gamesPlayed":7,"goalAssists":0}}""") }
        try {
            val person = Athlete("epl:701", "Selected Player", "9", "Forward")
            val overview = NativePremierLeagueProvider(client(server), { at }).athlete(person)
            assertEquals(person, overview.athlete); assertEquals("4", overview.headlineStats["Goals"])
            assertEquals("0", overview.headlineStats["Goal Assists"]); assertFalse("Clean Sheets" in overview.stats)
            assertEquals("2026–27 season", overview.statlineLabel)
        } finally { server.shutdown() }
    }
    @Test fun nestedPublicPlayerIdentityAndDecimalCountsAreMappedWithoutChangingTheSelectedIdentity(): Unit = runBlocking {
        val server = setup { MockResponse().setBody("""{"player":{"id":"154561","name":"David Raya","position":"Goalkeeper","country":{"country":"Spain"},"currentTeam":{"name":"Arsenal"}},"stats":{"gamesPlayed":7.0,"cleanSheets":4.0,"ratio":0.125}}""") }
        try {
            val result = NativePremierLeagueProvider(client(server), { at }).athlete(Athlete("epl:154561", "Raya", jersey = "1"))
            assertEquals("epl:154561", result.athlete.id); assertEquals("David Raya", result.athlete.name); assertEquals("1", result.athlete.jersey)
            assertEquals("7", result.headlineStats["Games Played"]); assertEquals("4", result.headlineStats["Clean Sheets"])
            assertEquals("0.125", result.stats["Ratio"]); assertEquals("Spain", result.bio["Nationality"])
        } finally { server.shutdown() }
        for (id in listOf("701", "", "../154561")) {
            val wrong = setup { MockResponse().setBody("""{"player":{"id":"$id"},"stats":{"gamesPlayed":7}}""") }
            try { assertTrue(runCatching { NativePremierLeagueProvider(client(wrong), { at }).athlete(Athlete("epl:154561", "Raya")) }.exceptionOrNull() is IOException) } finally { wrong.shutdown() }
        }
    }
    @Test fun actualSquadBiographyUsesReportedBirthCountryMeasurementsAndAgeWithoutInventedFields(): Unit = runBlocking {
        val server = setup { MockResponse().setBody("""{"team":{"id":"3"},"players":[{"id":"154561","name":{"display":"David Raya"},"shirtNum":1,"position":"Goalkeeper","country":{"country":"Spain"},"countryOfBirth":"Spain","height":183,"weight":80,"dates":{"birth":"1995-09-15"}}]}""") }
        try {
            val person = NativePremierLeagueProvider(client(server), { at }).roster("epl:3").single().athletes.single()
            assertEquals(31, person.age); assertEquals("183 cm", person.height); assertEquals("80 kg", person.weight)
            assertEquals("Spain", person.birthPlace); assertEquals("Spain", person.details)
        } finally { server.shutdown() }
        assertNull(NativePremierLeagueMapping.age("2026-02-30", at)); assertNull(NativePremierLeagueMapping.age("2027-01-01", at))
        assertEquals(30, NativePremierLeagueMapping.age("1995-09-15", Dates.parseIso("2026-09-14T12:00:00Z")!!))
    }
    @Test fun liveProjectedOrForeignSeasonTablesCannotOverwriteTheOfficialSlot(): Unit = runBlocking {
        for (body in listOf("""{"season":{"id":"2025"},"tables":[]}""", """{"competition":{"id":"99"},"tables":[]}""", """{"live":true,"tables":[]}""")) {
            val server = setup { MockResponse().setBody(body) }
            try { assertTrue(runCatching { NativePremierLeagueProvider(client(server), { at }).standings() }.exceptionOrNull() is IOException) } finally { server.shutdown() }
        }
    }
    @Test fun nativeCalendarAndDetailUseKnownIdWithoutScheduleOrEspnLookup(): Unit = runBlocking {
        val server = setup { request -> MockResponse().setBody(when {
            request.requestUrl!!.encodedPath.endsWith("2645241") -> fixture("fulltime")
            request.requestUrl!!.encodedPath.endsWith("stats") -> "[]"
            request.requestUrl!!.encodedPath.endsWith("events") -> """{"homeTeam":{},"awayTeam":{}}"""
            request.requestUrl!!.encodedPath.endsWith("commentary") -> page(emptyList())
            else -> "{}"
        }) }
        try {
            val http = HttpGateway(OkHttpClient()); val client = NativePremierLeagueClient(http, server.url("/").toString(), { at })
            val soccer = NativeSoccer(http, server.url("/").toString(), { at }, client)
            val provider = NativePremierLeagueProvider(client, { at }, { soccer.load(it, null) })
            val match = provider.calendarGame("epl:2645241")!!
            val detail = provider.gameCentre(match)
            assertEquals("5", detail.liveMatch!!.home.score); assertTrue(detail.providerStamp!!.final)
            assertTrue((1..server.requestCount).map { server.takeRequest().requestUrl!!.encodedPath }.none { it == "/api/v2/matches" })
        } finally { server.shutdown() }
    }
}
