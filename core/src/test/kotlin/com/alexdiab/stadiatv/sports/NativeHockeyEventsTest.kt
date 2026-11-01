package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Explicitly constructed provider-shaped cases, not purported match recordings. */
class NativeHockeyEventsTest {
    private val match = Match("espn-game", League.find("hockey/nhl")!!, 1704230000000, "Wild at Bruins", "MIN @ BOS", GameState.LIVE, "", TeamSide("Boston Bruins", "Bruins", "BOS", teamId = "espn-bos"), TeamSide("Minnesota Wild", "Wild", "MIN", teamId = "espn-min"))
    private val gameId = "2023020204"
    private val roster = """[{"playerId":1,"firstName":{"default":"Ada"},"lastName":{"default":"One"}},{"playerId":2,"firstName":{"default":"Bea"},"lastName":{"default":"Two"}},{"playerId":3,"firstName":{"default":"Cy"},"lastName":{"default":"Three"}}]"""
    private fun root(plays: String? = "[]", extra: String = ""): JsonElement = parseJson("""{"id":$gameId,"gameState":"LIVE","awayTeam":{"id":30,"abbrev":"MIN","score":1},"homeTeam":{"id":7,"abbrev":"BOS","score":0},"periodDescriptor":{"number":1,"periodType":"REG"},"clock":{"timeRemaining":"10:00"},"rosterSpots":$roster${plays?.let { ",\"plays\":$it" } ?: ""}$extra}""")
    private fun play(kind: String, details: String = "{}", event: Int = 10, order: Int = event, period: String = "REG", number: Int = 1, situation: String = "1551") =
        """{"eventId":$event,"sortOrder":$order,"typeDescKey":"$kind","periodDescriptor":{"number":$number,"periodType":"$period"},"timeInPeriod":"01:20","situationCode":"$situation","homeTeamDefendingSide":"right","details":$details}"""
    private fun landing(goals: String) = root(null, ""","summary":{"scoring":[{"periodDescriptor":{"number":1,"periodType":"REG"},"goals":$goals}]}""")

    @Test fun goalsMergeRichSummaryNamesTotalsStrengthAndReplayWithoutEspnIdentityReuse() {
        val goal = """[{"eventId":10,"playerId":1,"firstName":{"default":"Ada"},"lastName":{"default":"One"},"headshot":"https://example.com/ada.png","goalsToDate":12,"strength":"pp","homeScore":0,"awayScore":1,"teamAbbrev":{"default":"MIN"},"highlightClipSharingUrl":"https://www.nhl.com/video/goal","assists":[{"playerId":2,"firstName":{"default":"Bea"},"lastName":{"default":"Two"},"assistsToDate":20}]}]"""
        val raw = root("[${play("goal", """{"scoringPlayerId":1,"eventOwnerTeamId":30,"shotType":"wrist"}""", situation = "1541")}]")
        val event = NativeHockeyEvents.plays(raw, landing(goal), match).single()
        assertEquals("espn-min", event.teamId); assertEquals("nhl:$gameId:10", event.id)
        assertEquals("Power-play goal", event.hockeyEvent!!.strength); assertEquals(12, event.hockeyEvent!!.seasonGoals)
        assertEquals(20, event.hockeyEvent!!.assistSeasonTotals["2"]); assertEquals("Bea Two", event.hockeyEvent!!.assists.single().name)
        assertTrue(event.text.contains("Ada One scores")); assertTrue(event.text.contains("Wrist shot")); assertEquals("1", event.awayScore)
        assertEquals("https://www.nhl.com/player/1", event.hockeyEvent!!.primary!!.profileUrl)
        assertEquals("https://www.nhl.com/video/goal", event.videoUrl)
        assertEquals("1st period", event.period)
    }

    @Test fun penaltyAttributionIncludesDrawnServedAndBenchWithoutGuessingPlayer() {
        val full = play("penalty", """{"committedByPlayerId":1,"drawnByPlayerId":2,"servedByPlayerId":3,"duration":4,"descKey":"high-sticking-double-minor"}""")
        val bench = play("penalty", """{"servedByPlayerId":3,"duration":2,"descKey":"too-many-men-on-the-ice"}""", event = 11)
        val events = NativeHockeyEvents.plays(root("[$full,$bench]"), null, match)
        assertTrue(events.last().text.contains("Ada One — High-sticking (double minor)"))
        assertTrue(events.last().text.contains("Drawn by Bea Two")); assertTrue(events.last().text.contains("Served by Cy Three"))
        assertTrue(events.first().text.startsWith("Bench — Too many players on the ice")); assertNull(events.first().hockeyEvent!!.primary)
        assertTrue(events.all(HockeyPlayFilter.Penalties::includes)); assertFalse(events.any { it.isScoring })
    }

    @Test fun eventSpecificPlayersHumanizationUnknownKindsAndNumericFallbackStayDistinct() {
        val keys = listOf("hit" to """{"hittingPlayerId":1,"hitteePlayerId":2}""", "faceoff" to """{"winningPlayerId":1,"losingPlayerId":2}""",
            "blocked-shot" to """{"shootingPlayerId":1,"blockingPlayerId":2}""", "shot-on-goal" to """{"shootingPlayerId":999,"goalieInNetId":2}""")
        val events = NativeHockeyEvents.plays(root(keys.mapIndexed { i, (kind, d) -> play(kind, d, i + 1) }.joinToString(",", "[", "]")), null, match)
        assertTrue(events.any { it.text == "Ada One hits Bea Two" }); assertTrue(events.any { it.text == "Ada One wins faceoff vs. Bea Two" })
        assertTrue(events.any { it.text == "Bea Two blocks Ada One's shot" }); assertTrue(events.any { it.text == "Unknown player shot saved by Bea Two" })
        assertEquals("goal", NativeHockeyEvents.kind(parseJson("""{"typeCode":505}""")))
        assertEquals("future", NativeHockeyEvents.kind(parseJson("""{"typeDescKey":"future","typeCode":505}""")))
        assertFalse(NativeHockeyEvents.plays(root("[${play("future")}]"), null, match).single().isScoring)
    }

    @Test fun shootoutRoundsCountPerTeamAfterDedupAndNeverBecomeRegularScoresOrSeasonGoals() {
        val a = play("goal", """{"scoringPlayerId":1,"eventOwnerTeamId":30,"awayScore":4,"homeScore":3,"scoringPlayerTotal":99}""", 1, period = "SO", number = 5)
        val b = play("shot-on-goal", """{"shootingPlayerId":2,"eventOwnerTeamId":7}""", 2, period = "SO", number = 5)
        val c = play("missed-shot", """{"shootingPlayerId":1,"eventOwnerTeamId":30}""", 3, period = "SO", number = 5)
        val events = NativeHockeyEvents.plays(root("[$c,$a,$b,$a]"), null, match).asReversed()
        assertEquals(listOf(1, 1, 2), events.map { it.hockeyEvent!!.shootoutRound }); assertEquals(3, events.size)
        assertEquals(listOf("Goal", "Saved", "Missed"), events.map { it.hockeyEvent!!.subtitle })
        assertTrue(events.all { it.awayScore == null && it.homeScore == null && it.hockeyEvent!!.seasonGoals == null })
        assertEquals("2OT", NativeHockeyEvents.periodLabel(parseJson("""{"number":5,"periodType":"OT","maxRegulationPeriods":3}""")))
    }

    @Test fun rinkCoordinatesRotateBothAxesAndRejectInvalidGeometry() {
        val raw = parseJson(play("shot-on-goal", """{"xCoord":75,"yCoord":12}"""))
        assertEquals(HockeyLocation(-75f, -12f, true), NativeHockeyEvents.location(raw))
        assertNull(NativeHockeyEvents.location(parseJson(play("shot-on-goal", """{"xCoord":101,"yCoord":12}"""))))
        assertNull(NativeHockeyEvents.location(parseJson(play("shot-on-goal", """{"xCoord":"NaN","yCoord":12}"""))))
        assertNull(NativeHockeyEvents.location(parseJson(play("shot-on-goal"))))
        val unknown = JsonObject(raw.jsonObject - "homeTeamDefendingSide")
        assertEquals(HockeyLocation(75f, 12f, false), NativeHockeyEvents.location(unknown))
    }

    @Test fun landingOnlySummarySurvivesPlayFailureAndShowsAllGoalsChronologically() {
        val goals = (1..10).joinToString(",", "[", "]") { """{"eventId":$it,"playerId":1,"name":{"default":"Ada One"},"teamAbbrev":{"default":"MIN"},"awayScore":$it,"homeScore":0,"timeInPeriod":"01:00"}""" }
        val l = landing(goals)
        val parsed = NativeHockey.parse(l, l, null, null, match, 100)!!
        assertFalse(parsed.hockey!!.eventsLoaded); assertEquals(10, parsed.hockey!!.scoringSummary.size)
        assertEquals(10, NativeHockeyEvents.scoringTimeline(parsed).size)
        assertEquals("1", NativeHockeyEvents.scoringTimeline(parsed).first().awayScore)
        assertNull(NativeHockey.parse(l, l, null, null, match.copy(home = match.home.copy(abbreviation = "NYR")), 100))
    }

    @Test fun partialRefreshRetainsPlaysButNewSameIdentityCorrectsWithoutDuplication() {
        val raw = root("[${play("goal", """{"scoringPlayerId":1,"eventOwnerTeamId":30}""")}]")
        val old = NativeHockey.parse(raw, null, null, null, match, 100)!!
        val partial = NativeHockey.parse(root(null), null, null, null, match, 101)!!
        val retained = NativeHockey.reduce(old, partial)
        assertEquals(old.plays, retained.plays); assertEquals(old.providerStamp!!.sequence, retained.providerStamp!!.sequence)
        val changed = NativeHockey.parse(root("[${play("goal", """{"scoringPlayerId":2,"eventOwnerTeamId":30}""")}]") , null, null, null, match, 102)!!
        val corrected = NativeHockey.reduce(retained, changed)
        assertEquals(old.plays.single().id, corrected.plays.single().id); assertEquals("Bea Two", corrected.plays.single().hockeyEvent!!.primary!!.name)
    }

    @Test fun readingHistoryDefersNewEventsButAppliesCorrectionsAndRemovals() {
        fun p(id: String, text: String = id) = Play(id, "1st period", "", text, null, null, null, false)
        val window = HockeyTimelineWindow().update(listOf(p("b"), p("a")), true)
        val deferred = window.update(listOf(p("c"), p("b", "corrected")), false)
        assertEquals(listOf("b"), deferred.displayed.map { it.id }); assertEquals("corrected", deferred.displayed.single().text)
        assertEquals(setOf("c"), deferred.deferredIDs)
        assertEquals(2, deferred.update(listOf(p("c"), p("b")), true).displayed.size)
        assertTrue(deferred.update(listOf(p("c"), p("b")), true).deferredIDs.isEmpty())
    }

    @Test fun endpointCooldownIsIndependentAndDoesNotDiscardOtherNativeSurfaces(): Unit = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path!!.substringAfterLast('/')) {
                "play-by-play" -> MockResponse().setResponseCode(429).addHeader("Retry-After", "300")
                "landing" -> MockResponse().setBody(landing("[]").toString())
                "boxscore" -> MockResponse().setBody(root(null).toString())
                "right-rail" -> MockResponse().setBody("""{"teamGameStats":[{"category":"sog","awayValue":10,"homeValue":20}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val provider = NativeHockey(HttpGateway(OkHttpClient()), server.url("/").toString().trimEnd('/'), { 1000 })
            val previous = GameCentre(providerStamp = ProviderStamp("NHL", gameId, 1))
            val result = provider.load(match, previous)!!
            assertEquals(gameId, result.providerStamp!!.gameId); assertEquals("10", result.teamStats.single().away)
            assertTrue(result.hockey!!.errors.containsKey("play-by-play")); assertFalse(result.hockey!!.eventsLoaded)
            provider.load(match, result)
            val paths = (1..server.requestCount).map { server.takeRequest().path!! }
            assertEquals(1, paths.count { it.endsWith("/play-by-play") }); assertTrue(paths.none { "espn-game" in it })
        } finally { server.shutdown() }
    }

    @Test fun recordedIosNhlFixtureRetainsEveryEventAndMergesActualGoalAndPenaltyDetails() {
        fun fixture(name: String) = parseJson(javaClass.getResource("/parity/nhl-$name.recorded.json")!!.readText())
        val actualMatch = match.copy(home = TeamSide("Buffalo Sabres", "Sabres", "BUF", teamId = "espn-buf"))
        val pbp = fixture("final-play-by-play"); val landing = fixture("final-landing")
        val result = NativeHockey.parse(pbp, landing, fixture("final-boxscore"), null, actualMatch, 100)!!
        assertEquals(320, result.plays.size); assertEquals(5, result.hockey!!.scoringSummary.size)
        val kaprizov = result.plays.single { it.id == "nhl:$gameId:399" }
        assertEquals("Kirill Kaprizov", kaprizov.hockeyEvent!!.primary!!.name)
        assertEquals(2, kaprizov.hockeyEvent!!.assists.size); assertEquals("Power-play goal", kaprizov.hockeyEvent!!.strength)
        assertTrue(kaprizov.videoUrl!!.startsWith("https://nhl.com/")); assertTrue(result.plays.any { it.hockeyEvent!!.kind == "penalty" && it.text.contains("Drawn by") })
        assertEquals("Final", result.liveMatch!!.statusDetail); assertTrue(result.liveMatch!!.live.situation.isEmpty())
        assertTrue(result.boxScore.flatMap { it.groups }.flatMap { it.rows }.all { it.athleteId!!.startsWith("nhl:") && it.profileUrl!!.startsWith("https://www.nhl.com/player/") })
    }

    @Test fun missingNativeOwnershipDoesNotDefaultToAwayAndSummaryAcceptsKnownAbbreviationAliases() {
        val missing = parseJson("""{"id":$gameId,"plays":[${play("goal", """{"scoringPlayerId":1}""", situation = "1550")}]}""")
        val unknown = NativeHockeyEvents.plays(missing, null, match).single()
        assertNull(unknown.teamId); assertNull(unknown.hockeyEvent!!.strength)
        val kings = match.copy(away = match.away.copy(abbreviation = "LA", teamId = "espn-la"))
        val goal = """[{"eventId":1,"playerId":1,"teamAbbrev":{"default":"LAK"}}]"""
        assertEquals("espn-la", NativeHockeyEvents.summary(landing(goal), kings).single().teamId)
    }
}
