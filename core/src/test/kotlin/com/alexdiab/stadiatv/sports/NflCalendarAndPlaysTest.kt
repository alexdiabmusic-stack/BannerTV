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
import java.util.concurrent.atomic.AtomicInteger

/** Recorded fixtures are named explicitly; unusual/failure cases below are constructed, not live recordings. */
class NflCalendarAndPlaysTest {
    private val week = NflWeek(2025, NflSeasonType.REG, 1)
    private fun fixture() = parseJson(javaClass.getResource("/parity/nfl-regular-final.json")!!.readText())
    private fun nativeMatch() = NflCalendar.matches(fixture(), week).single()
    private fun event(type: String, scoring: String = "UNSPECIFIED", stats: String = "[]", text: String = "Provider description", id: Int = 1) = parseJson("""{"playId":$id,"playSequenceNumber":$id,"quarter":1,"clockTime":"10:00","down":2,"yardsRemaining":5,"playIsGoalToGo":false,"yardLine":"DAL 20","playType":"$type","scoringPlayType":"$scoring","playDescription":"$text","stats":$stats}""")
    private fun map(raw: JsonElement) = NativeFootballPlays.map(listOf(raw), emptyList(), "native-game", nativeMatch(), "a", "h", mapOf("p" to "Full Player Name")).single()

    @Test fun recordedCalendarPreservesNativeGameIdentityAndNeverSuppliesEspnTeamIds() {
        val match = nativeMatch()
        assertEquals("DAL @ PHI", match.shortName); assertEquals("20–24", match.displayScore)
        assertEquals(GameState.FINAL, match.state); assertEquals("Final", match.statusDetail)
        assertEquals("NFL", match.nativeReference!!.provider); assertEquals(2025, match.nativeReference!!.season)
        assertEquals("nfl:${match.nativeReference!!.gameId}", match.id)
        assertTrue(match.home.teamId!!.startsWith("nfl:")); assertTrue(match.away.teamId!!.startsWith("nfl:"))
        assertTrue(match.home.logoUrl!!.startsWith("https://static.www.nfl.com/image/upload/f_png,w_192/"))
        assertTrue(NflCalendar.matches(fixture(), week.copy(number = 2)).isEmpty())
    }

    @Test fun recordedWeekChoicesAreOrderedAndRetainAnnouncedByes() {
        val raw = parseJson(javaClass.getResource("/parity/nfl-weeks.recorded.json")!!.readText())
        val rows = NflCalendar.choices(raw, 2026, NflSeasonType.REG)
        assertEquals((1..18).toList(), rows.map { it.week.number })
        assertTrue(rows.single { it.week.number == 7 }.byeTeams.contains("Buffalo Bills"))
        assertTrue(rows.first().byeTeams.isEmpty())
    }

    @Test fun postseasonLabelsAndMalformedWeekShapesAreNotGuessedFromWeekNumbers() {
        val raw = parseJson("""{"weeks":[{"week":4,"weekType":"SB"},{"week":1,"weekType":"WC"},{"week":2,"weekType":"DIV"},{"week":3,"weekType":"CONF"},{"week":31},{"week":2,"season":2024}]}""")
        assertEquals(listOf("Wild Card", "Divisional", "Conference Championship", "Super Bowl"), NflCalendar.choices(raw, 2025, NflSeasonType.POST).map { it.label })
        assertNull(NflCalendar.week(parseJson("""{"season":2025,"seasonType":"REG","week":1.5}""")))
        try { NflCalendar.choices(parseJson("{}"), 2025, NflSeasonType.REG); fail("Expected unavailable weeks, not an empty success") } catch (_: IOException) { }
        try { NflCalendar.rows(parseJson("{}")); fail("Expected invalid games shape") } catch (_: IOException) { }
    }

    @Test fun statusesDistinguishHalftimeFinalOvertimeAndUnavailableStates() {
        assertEquals(GameState.LIVE to "Halftime", NflCalendar.phase("IN_PROGRESS", "END_OF_HALF"))
        assertEquals(GameState.FINAL to "Final · OT", NflCalendar.phase("FINAL_OVERTIME", "HALFTIME"))
        assertEquals(GameState.PRE to "Postponed", NflCalendar.phase("POSTPONED"))
        assertEquals(GameState.PRE to "Status unavailable", NflCalendar.phase("future-status"))
        assertEquals("OT 2", NativeFootballPlays.quarter(6))
    }

    @Test fun gsisCodesResolveMissedKicksIncompletePassInterceptionAndFumbleWithoutFakeTurnovers() {
        fun kind(raw: String, codes: Set<Int>) = NativeFootballPlays.kind(raw, null, "neutral", codes)
        assertEquals("Missed field goal", kind("FIELD_GOAL", setOf(69)))
        assertEquals("Missed extra point", kind("XP_KICK", setOf(74)))
        assertEquals("Incomplete pass", kind("PASS", setOf(14)))
        assertEquals("Interception", kind("PASS", setOf(19)))
        assertEquals("Fumble", kind("RUSH", setOf(52)))
        assertEquals("Touchdown", NativeFootballPlays.kind("RUSH", "TOUCHDOWN", "", setOf(52)))
        val fumble = map(event("RUSH", stats = """[{"statType":52}]"""))
        assertEquals("Fumble", fumble.type); assertFalse(fumble.footballEvent!!.turnover)
        assertTrue(map(event("PASS", stats = """[{"statType":19}]""")).footballEvent!!.turnover)
    }

    @Test fun sourceTextFallbacksAndUnknownTypesRemainHonest() {
        assertEquals("Kneel", NativeFootballPlays.kind("RUSH", null, "Quarterback kneels", emptySet()))
        assertEquals("Spike", NativeFootballPlays.kind("PASS", null, "Pass spiked", emptySet()))
        assertEquals("Review", NativeFootballPlays.kind("COMMENT", null, "Replay challenge", emptySet()))
        val future = map(event("NEW_KIND", text = " Provider words "))
        assertEquals("Play", future.type); assertEquals("NEW_KIND", future.footballEvent!!.rawType)
        assertEquals("Provider words", future.text); assertFalse(future.isScoring)
    }

    @Test fun participantDedupNamesAndFirstDownFlagsPreserveNativeIdentity() {
        val stats = """[{"playStatId":1,"personId":"p","gsisPlayerName":"P.Short","statType":3},{"playStatId":1,"personId":"p","statType":3},{"playStatId":2,"personId":"u","gsisPlayerName":"Unknown Full","statType":777,"yards":0}]"""
        val play = map(event("PASS", stats = stats))
        assertEquals(2, play.footballEvent!!.participants.size); assertTrue(play.footballEvent!!.firstDown)
        assertEquals("Full Player Name", play.footballEvent!!.participants.first().name)
        assertEquals(777, play.footballEvent!!.participants.last().statType); assertEquals(0, play.footballEvent!!.participants.last().yards)
        assertEquals("2nd & 5", play.footballEvent!!.downDistance)
        val goal = JsonObject(event("RUSH").jsonObject + mapOf("playIsGoalToGo" to JsonPrimitive(true), "yardsRemaining" to JsonNull))
        assertEquals("2nd & Goal", map(goal).footballEvent!!.downDistance)
        val unknown = JsonObject(event("RUSH").jsonObject - "yardsRemaining")
        assertEquals("2nd & –", map(unknown).footballEvent!!.downDistance)
    }

    @Test fun correctionsAndDeletionsRebuildSnapshotWithoutDuplicatingPlays() {
        val first = event("PASS", id = 1); val corrected = event("RUSH", id = 1); val next = event("PUNT", id = 2)
        val deleted = JsonObject(next.jsonObject + ("playDeleted" to JsonPrimitive(true)))
        val accepted = NativeFootballPlays.accepted(listOf(first, next, corrected, deleted))
        assertEquals(1, accepted.size); assertEquals("RUSH", accepted.single()["playType"].string())
    }

    @Test fun driveFilteringAndLiveExpansionUseReportedCompletion() {
        fun drive(result: String, scoring: Boolean = false) = GameDrive("d", "DAL", result, "", 0, "", "", FootballDrive(1, 1, null, null, null, scoring, null))
        assertEquals("d", NativeFootballPlays.currentDrive(listOf(drive("")), true))
        assertNull(NativeFootballPlays.currentDrive(listOf(drive("Touchdown")), true))
        assertNull(NativeFootballPlays.currentDrive(listOf(drive("", true)), true))
        assertNull(NativeFootballPlays.currentDrive(listOf(drive("")), false))
        val penalty = map(event("PASS", text = "PENALTY enforced"))
        assertTrue(FootballPlayFilter.Penalties.includes(penalty)); assertFalse(FootballPlayFilter.Turnovers.includes(penalty))
    }

    @Test fun realQuarterScoresDoNotAddUnusedOvertimeOrStaleFinalSituation() {
        val game = NativeFootball.parse(fixture().array.single(), nativeMatch(), 1)!!
        assertEquals(listOf("Q1", "Q2", "Q3", "Q4"), game.lineScore!!.periods)
        assertEquals(listOf("7", "13", "0", "0"), game.lineScore!!.away)
        assertEquals("24", game.lineScore!!.homeTotal); assertEquals(LiveContext.EMPTY, game.liveMatch!!.live)
        assertNull(game.football!!.field); assertNull(game.football!!.currentDriveId)
    }

    @Test fun nativeCalendarGamesCannotBeAccidentallySentToEspn(): Unit = runBlocking {
        val calls = AtomicInteger()
        val http = HttpGateway(OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("No network permitted in test") }.build())
        assertSame(GameCentre.EMPTY, SportsRepository(emptyMap(), EspnApi(http)).gameCentre(nativeMatch()))
        assertSame(GameCentre.EMPTY, EspnApi(http).summary(nativeMatch()))
        assertEquals(0, calls.get())
        assertNull(NativeFootball.parse(fixture().array.single(), nativeMatch().copy(nativeReference = nativeMatch().nativeReference!!.copy(gameId = "wrong")), 1))
    }

    @Test fun calendarRoutesUseNativeWeekAndThinScheduleWithOneBoundedTokenRefresh(): Unit = runBlocking {
        val server = MockWebServer(); val tokens = AtomicInteger(); val weeks = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/token") -> MockResponse().setBody("""{"accessToken":"unit-${tokens.incrementAndGet()}"}""")
                "/weeks/date/" in request.path!! -> if (weeks.incrementAndGet() == 1) MockResponse().setResponseCode(401) else MockResponse().setBody("""{"season":2025,"seasonType":"REG","week":1}""")
                "/weeks/season/" in request.path!! -> MockResponse().setBody("""{"weeks":[{"week":1}]}""")
                "/weekly-game-details" in request.path!! -> MockResponse().setBody(fixture().toString())
                else -> MockResponse().setResponseCode(404)
            }
        }; server.start()
        try {
            val provider = NativeFootball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'))
            val actual = provider.weekFor(Dates.parseIso("2025-09-05T00:20:00Z")!!)
            assertEquals(week, actual); assertEquals(2, tokens.get()); assertEquals(2, weeks.get())
            assertEquals(1, provider.weeks(2025, NflSeasonType.REG).size); assertEquals(1, provider.schedule(actual).size)
            val requests = (1..server.requestCount).map { server.takeRequest() }
            assertTrue(requests.any { it.path!!.contains("/weeks/date/2025-09-04") })
            val schedule = requests.single { it.path!!.contains("/weekly-game-details") }
            assertEquals("false", schedule.requestUrl!!.queryParameter("includeDriveChart"))
            assertEquals("2025", schedule.requestUrl!!.queryParameter("season")); assertEquals("REG", schedule.requestUrl!!.queryParameter("type"))
        } finally { server.shutdown() }
    }

    @Test fun nativeCalendarDetailSkipsDateResolutionAndKeepsFullDriveFeed(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/token") -> MockResponse().setBody("""{"accessToken":"unit-test"}""")
                "/weekly-game-details" in request.path!! -> MockResponse().setBody(fixture().toString())
                "/rosters?" in request.path!! -> MockResponse().setBody("""{"rosters":[]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }; server.start()
        try {
            val provider = NativeFootball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'))
            val result = provider.load(nativeMatch(), null)!!
            assertTrue(result.football!!.playsLoaded); assertTrue(result.plays.isNotEmpty())
            assertEquals("NFL Shield play-by-play", result.playsSource)
            val requests = (1..server.requestCount).map { server.takeRequest() }
            assertTrue(requests.none { "/weeks/date/" in it.path!! })
            assertEquals("true", requests.single { "/weekly-game-details" in it.path!! }.requestUrl!!.queryParameter("includeDriveChart"))
        } finally { server.shutdown() }
    }

    @Test fun repeatedRateLimitedWeekRequestHonorsCooldown(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"accessToken":"unit-test"}""")); server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "300"))
            val provider = NativeFootball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'))
            repeat(2) { try { provider.weeks(2025, NflSeasonType.POST, force = true); fail("Expected unavailable") } catch (_: IOException) { } }
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun missingPlayFeedRetainsPriorSnapshotWhileAuthoritativeEmptyCanClearIt() {
        val raw = fixture().array.single(); val match = nativeMatch()
        val old = NativeFootball.parse(raw, match, 1)!!
        val absent = JsonObject(raw.jsonObject + ("driveChart" to JsonObject(raw["driveChart"]!!.jsonObject - "plays")))
        val partial = NativeFootball.parse(absent, match, 2)!!
        assertEquals(old.plays, NativeFootball.reduce(old, partial).plays)
        val empty = JsonObject(raw.jsonObject + ("driveChart" to JsonObject(raw["driveChart"]!!.jsonObject + ("plays" to JsonArray(emptyList())))))
        val authoritative = NativeFootball.reduce(old, NativeFootball.parse(empty, match, 3)!!)
        assertTrue(authoritative.plays.isEmpty()); assertEquals("NFL Shield play-by-play", authoritative.playsSource)
    }

    @Test fun scoreIdentityChecksMissingDistanceAndZeroScoreOvertimeAreNotInvented() {
        val raw = fixture().array.single(); val summary = raw["summary"]!!.jsonObject
        val bad = JsonObject(raw.jsonObject + ("summary" to JsonObject(summary + ("homeTeam" to JsonObject(summary["homeTeam"]!!.jsonObject + ("teamId" to JsonPrimitive("wrong")))))))
        assertNull(NativeFootball.parse(bad, nativeMatch(), 1))
        val unknownDistance = JsonObject(raw.jsonObject + ("summary" to JsonObject(summary + mapOf("phase" to JsonPrimitive("IN_PROGRESS"), "quarter" to JsonPrimitive("Q2"), "down" to JsonPrimitive(2), "distance" to JsonNull, "isGoalToGo" to JsonPrimitive(false)))))
        assertFalse(NativeFootball.parse(unknownDistance, nativeMatch(), 1)!!.liveMatch!!.live.situation.any { "GOAL" in it })
        val ot = parseJson("""{"summary":{"phase":"FINAL_OT","quarter":"OVERTIME","awayTeam":{"score":{"q1":0,"ot":0,"total":0}},"homeTeam":{"score":{"q1":0,"ot":0,"total":0}}}}""")
        assertEquals(listOf("Q1", "OT"), NativeFootballPlays.lineScore(ot, nativeMatch())!!.periods)
    }

    @Test fun nativeReminderLinksResolveByNativeWeekAndIdWithoutEspnOrUnscopedDateFallback(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val provider = NativeFootball(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'))
            assertNull(provider.calendarGame("nfl:unsafe/path", 1)); assertNull(provider.calendarGame(nativeMatch().id, null)); assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"accessToken":"unit-test"}"""))
            server.enqueue(MockResponse().setBody("""{"season":2025,"seasonType":"REG","week":1}"""))
            server.enqueue(MockResponse().setBody(fixture().toString()))
            val restored = provider.calendarGame(nativeMatch().id, nativeMatch().startMillis)!!
            assertEquals(nativeMatch().nativeReference, restored.nativeReference)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
