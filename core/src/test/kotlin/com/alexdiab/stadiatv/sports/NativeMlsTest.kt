package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.data.FavoriteTeam
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
import java.io.IOException

/** Pinned iOS fixtures plus explicitly constructed failures; never runtime seed data or a live-coverage claim. */
class NativeMlsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val at = Dates.parseIso("2026-10-23T20:00:00Z")!!
    private val id = "MLS-MAT-0009LX"; private val season = "MLS-SEA-0001KA"
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/mls-$name.source.json")!!.readText())
    private fun overview() = fixture("match-overview-fulltime")
    private fun match() = NativeMlsMapping.game(overview(), true)!!
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun server(reply: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = reply(request) }; start()
    }
    private fun client(server: MockWebServer, now: () -> Long = { at }) = NativeMlsClient(HttpGateway(OkHttpClient()), server.url("/").toString(), now)
    private fun response(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        val raw = when {
            path.endsWith("key_events") -> fixture("key-events")
            path.endsWith("commentary") -> fixture("commentary")
            path.startsWith("/statistics/players/matches/") -> fixture("player-match-stats")
            path.startsWith("/statistics/clubs/matches/") -> fixture("team-match-stats")
            path == "/matches/$id" -> overview()
            else -> error("Unexpected constructed path $path")
        }
        return MockResponse().setBody(raw.toString())
    }
    private fun snapshot() = NativeMlsSnapshotCache.Snapshot().accept(mapOf("overview" to overview(), "lineups" to overview(), "officials" to overview(),
        "events" to fixture("key-events"), "statistics" to fixture("team-match-stats"), "playerStats" to fixture("player-match-stats"), "commentary" to fixture("commentary")), at, true)

    @Test fun actualSourceGameUsesOpaqueIdentitiesIntegerScoresAndFinalClock() {
        val game = match()
        assertEquals("mls:$id", game.id); assertEquals("mls:MLS-CLU-000008", game.home.teamId)
        assertEquals(NativeGameReference("MLS", id, 2026, NativeMlsClient.REGULAR), game.nativeReference)
        assertEquals("2–2", game.displayScore); assertEquals(GameState.FINAL, game.state)
        assertEquals(Dates.parseIso("2026-09-20T23:13:16.289Z"), game.startMillis)
        assertEquals(LiveContext.EMPTY, game.live); assertFalse(game.home.isWinner); assertNull(game.home.logoUrl)
        assertNull(NativeMlsMapping.rawId("epl:123", "MAT")); assertNull(NativeMlsMapping.rawId("mls:../$id", "MAT"))
        assertNull(NativeMlsMapping.rawId("MLS-OBJ-000396", "MAT"))
    }
    @Test fun sourceScheduleDoesNotDisplayPregameZerosAndUnknownStatusRemainsVisible() {
        val scheduled = NativeMlsMapping.game(fixture("schedule")["schedule"].array.first())!!
        assertNull(scheduled.home.score); assertFalse(scheduled.home.isWinner)
        assertEquals("90+6′", NativeMlsMapping.status("secondHalf", "90+6").runningClock)
        assertEquals("Half-time", NativeMlsMapping.status("halfTime", "45+6").label)
        assertEquals("FutureState", NativeMlsMapping.status("FutureState", "90+6").label)
        assertNull(NativeMlsMapping.status("finalWhistle", "90+6").runningClock)
    }
    @Test fun sourceLineupsUseLiteralBooleanMembershipAndNeverGuessTacticalRows() {
        val lineups = NativeMlsMapping.lineups(overview())
        assertEquals(40, lineups.sumOf { it.players.size }); assertTrue(lineups.none { it.hasPitch })
        val home = lineups.first { it.home }; assertEquals("4-3-3", home.formation); assertEquals("Cristian González", home.manager)
        val messi = home.players.single { it.player.id == "mls:MLS-OBJ-000396" }
        assertTrue(messi.starter); assertTrue(messi.captain); assertEquals("10", messi.player.number)
        val unknown = replace(overview()["home"]["players"].array.first(), "starting", JsonNull)
        val block = replace(overview()["home"], "players", JsonArray(listOf(unknown)))
        assertFalse(NativeMlsMapping.lineups(replace(overview(), "home", block)).first { it.home }.players.single().membershipKnown)
        assertEquals(6, NativeMlsMapping.officials(overview()).size)
    }
    @Test fun sourceEventsPreserveAddedTimeExplicitClubAndAllGoalParticipants() {
        val plays = NativeMlsDetails.events(fixture("key-events"), match(), season, overview())
        assertEquals(78, plays.size); assertEquals(4, plays.count { it.isScoring })
        assertEquals("90+6′", plays.first().clock)
        val goal = plays.single { it.id.endsWith("1245300001274") }
        assertEquals("mls:MLS-CLU-000065", goal.teamId); assertEquals("Anders Dreyer", goal.soccerEvent!!.primary!!.name)
        assertNotNull(goal.soccerEvent!!.secondary); assertNotNull(goal.soccerEvent!!.secondAssist)
        assertTrue(plays.all { it.homeScore == null && it.awayScore == null })
        val subs = plays.first { it.soccerEvent!!.kind == SoccerEventKind.Substitution }
        assertNotEquals(subs.soccerEvent!!.primary!!.id, subs.soccerEvent!!.secondary!!.id)
        val wrongInfo = replace(fixture("key-events"), "match_info", replace(fixture("key-events")["match_info"], "match_id", JsonPrimitive("MLS-MAT-000BAD")))
        assertTrue(runCatching { NativeMlsDetails.events(wrongInfo, match(), season, overview()) }.exceptionOrNull() is IOException)
    }
    @Test fun missingEventTeamUsesOnlyUniqueMembershipAndForeignExplicitTeamIsRejected() {
        val source = fixture("key-events")
        val wrapper = source["events"].array.first { it["type"].string() == "shot_at_goals" }
        val missing = replace(wrapper, "event", JsonObject(wrapper["event"].obj.orEmpty() - "team_id"))
        val raw = replace(source, "events", JsonArray(listOf(missing)))
        assertEquals("mls:MLS-CLU-000065", NativeMlsDetails.events(raw, match(), season, overview()).single().teamId)
        val foreign = replace(wrapper, "event", replace(wrapper["event"], "team_id", JsonPrimitive("MLS-CLU-000BAD")))
        assertTrue(runCatching { NativeMlsDetails.events(replace(source, "events", JsonArray(listOf(foreign))), match(), season, overview()) }.exceptionOrNull() is IOException)
        val ambiguous = replace(overview(), "home", replace(overview()["home"], "players", JsonArray(overview()["home"]["players"].array + overview()["away"]["players"].array)))
        assertNull(NativeMlsDetails.events(raw, match(), season, ambiguous).single().teamId)
    }
    @Test fun statisticsUseVerifiedScopeIdentityAndAerialNotGuessedGroundTackles() {
        val stats = NativeMlsDetails.stats(fixture("team-match-stats"), match(), season)
        assertEquals("48.46%", stats.single { it.label == "Possession" }.home)
        assertEquals("2.13", stats.single { it.label == "Expected goals" }.home)
        assertEquals("3", stats.single { it.label == "Aerial duels won" }.home)
        assertTrue(stats.none { it.label == "Tackles" }); assertTrue(stats.any { it.label == "Pass accuracy" })
        assertEquals("6", stats.single { it.label == "Attacking zone 1 entries" }.home)
        val player = NativeMlsDetails.playerStats(fixture("player-match-stats"), match(), season).single { it.player.id == "mls:MLS-OBJ-000396" }
        assertEquals("5", player.stats["Shots On Target"]); assertEquals("90", player.stats["Normalized Player Minutes"])
        assertEquals("1", player.stats["Goals"]); assertTrue(player.home)
        val negative = fixture("player-match-stats")["match_statistics"]["player_statistics"].array.first { (it["xG_efficiency"].string().toDoubleOrNull() ?: 0.0) < 0 }
        val signed = NativeMlsDetails.playerStats(fixture("player-match-stats"), match(), season).single { it.player.id == "mls:${negative["player_id"].string()}" }
        assertTrue(signed.stats.getValue("XG Efficiency").startsWith("-"))
        assertTrue(runCatching { NativeMlsDetails.stats(fixture("team-match-stats"), match(), "MLS-SEA-000BAD") }.exceptionOrNull() is IOException)
    }
    @Test fun commentaryRetainsProviderAddedTimeAndHigherVersionCorrections() {
        val comments = NativeMlsDetails.comments(fixture("commentary"), match(), season)
        assertEquals(20, comments.size); assertEquals("90'+6", comments.first().clock)
        val original = comments.first(); val corrected = original.copy(text = "Constructed correction", version = original.version + 1)
        val first = NativeSoccerReducer.apply(null, NativeSoccerUpdate(match(), id, at, "MLS", "MLS", commentary = listOf(corrected)))!!
        val next = NativeSoccerReducer.apply(first, NativeSoccerUpdate(match(), id, at + 1000, "MLS", "MLS", commentary = listOf(original)))!!
        assertEquals(corrected, next.soccer!!.commentary.single())
    }
    @Test fun sourceTablesRemainSeparateConferenceAndSupportersShieldWithPublishedPoints() {
        val conferences = NativeMlsStandings.groups(fixture("standings-conference"), 2026, season, NativeMlsClient.REGULAR)
        assertEquals(listOf("EASTERN CONFERENCE", "WESTERN CONFERENCE"), conferences.map { it.name })
        assertEquals("57", conferences.first().rows.first().values["points"])
        assertEquals("Supporters' Shield", NativeMlsStandings.groups(fixture("standings-overall"), 2026, season, NativeMlsClient.REGULAR).single().name)
        val live = NativeMlsStandings.table(fixture("standings-conference"), 2026, season, NativeMlsClient.REGULAR, true, at)
        assertTrue(live.provisional); assertEquals(2, live.groups.size)
        assertTrue(runCatching { NativeMlsStandings.groups(fixture("standings-conference"), 2025, season, NativeMlsClient.REGULAR) }.exceptionOrNull() is IOException)
    }
    @Test fun pageTokensAreEncodedBoundedAndRepeatedOrMalformedPagesAreNotSuccessfulEmpty(): Unit = runBlocking {
        val source = fixture("schedule")
        val network = server { request -> MockResponse().setBody(if (request.requestUrl!!.queryParameter("page_token") == null)
            replace(source, "next_page_token", JsonPrimitive("opaque+/=cursor")).toString() else """{"schedule":[]}""") }
        try {
            assertEquals(5, client(network).pages("/matches/seasons/$season", "schedule", mapOf("per_page" to "100", "match_date[gte]" to "2026-09-20"), 0).size)
            val first = network.takeRequest(); val second = network.takeRequest()
            assertEquals("2026-09-20", first.requestUrl!!.queryParameter("match_date[gte]"))
            assertEquals("opaque+/=cursor", second.requestUrl!!.queryParameter("page_token")); assertFalse(first.path!!.startsWith("/v1"))
        } finally { network.shutdown() }
        for (body in listOf("{}", """{"schedule":[],"next_page_token":"same"}""", """{"schedule":[],"next_page_token":{}}""")) {
            val bad = server { MockResponse().setBody(body) }
            try { assertTrue(runCatching { client(bad).pages("/matches/seasons/$season", "schedule", emptyMap(), 0) }.exceptionOrNull() is IOException); assertTrue(bad.requestCount <= 2) }
            finally { bad.shutdown() }
        }
    }
    @Test fun seasonResolutionUsesRequestedYearAndDoesNotDeriveOpaqueIds(): Unit = runBlocking {
        val network = server { MockResponse().setBody("""{"seasons":[{"season":2025,"season_id":"MLS-SEA-000AAA"},{"season":2026,"season_id":"$season"}]}""") }
        try {
            val api = client(network); assertEquals("MLS-SEA-000AAA", api.season(2025)); assertEquals(season, api.season(2026)); assertNull(api.season(2024))
            assertEquals(1, network.requestCount); assertEquals("/competitions/MLS-COM-000001/seasons", network.takeRequest().requestUrl!!.encodedPath)
        } finally { network.shutdown() }
    }
    @Test fun gatewayCooldownAppliesAcrossResourcesAndConnectivityCannotBypassProviderRejection(): Unit = runBlocking {
        var clock = at
        val network = server { MockResponse().setResponseCode(429).addHeader("Retry-After", "300") }
        try {
            val api = client(network) { clock }; val service = NativeMls(api, { clock })
            assertTrue(runCatching { api.get("/matches/$id") }.exceptionOrNull() is HttpStatusException)
            service.onNetworkRestored()
            assertTrue(runCatching { api.get("/matches/$id/key_events") }.exceptionOrNull() is IOException); assertEquals(1, network.requestCount)
            clock += 300_001; assertTrue(runCatching { api.get("/matches/$id") }.exceptionOrNull() is HttpStatusException); assertEquals(2, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun realFixtureNetworkLoadIsFiveRequestsAndLightTabsDoNotRepollHeavyPlayerStats(): Unit = runBlocking {
        var clock = at; val network = server(::response)
        try {
            val service = NativeMls(client(network) { clock }, { clock })
            val first = service.load(match(), null); assertEquals(5, network.requestCount)
            val initialRequests = (1..5).map { network.takeRequest() }
            assertEquals("100", initialRequests.single { it.requestUrl!!.encodedPath.endsWith("key_events") }.requestUrl!!.queryParameter("per_page"))
            assertEquals(78, first.plays.size); assertEquals(40, first.soccer!!.playerStats.size)
            clock += 10_000
            val light = service.load(match(), first, tab = "Lineups")
            assertEquals(6, network.requestCount); assertEquals(at, light.soccer!!.surfaceUpdatedAt["playerStats"])
            assertEquals(at + 10_000, light.soccer!!.surfaceUpdatedAt["overview"]); assertEquals(at, light.soccer!!.lastFullRefresh)
        } finally { network.shutdown() }
    }
    @Test fun failedIndependentSurfaceRetainsOriginalDataAndTransportRestorationRetriesImmediately(): Unit = runBlocking {
        var clock = at; var broken = false; val eventCalls = java.util.concurrent.atomic.AtomicInteger()
        val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.encodedPath.endsWith("key_events")) { eventCalls.incrementAndGet(); if (broken) throw IOException("Constructed offline events") }
            chain.proceed(chain.request())
        }.build())
        val network = server(::response)
        try {
            val service = NativeMls(NativeMlsClient(http, network.url("/").toString(), { clock }), { clock })
            val first = service.load(match(), null); clock += 10_000; broken = true
            val stale = service.load(match(), first, tab = "Timeline")
            assertEquals(first.plays, stale.plays); assertEquals(at, stale.soccer!!.surfaceUpdatedAt["events"]); assertTrue("events" in stale.soccer!!.errors)
            broken = false; service.onNetworkRestored(); clock += 1000
            val recovered = service.load(match(), stale, tab = "Timeline")
            assertEquals(3, eventCalls.get()); assertTrue(recovered.soccer!!.errors.isEmpty()); assertEquals(clock, recovered.soccer!!.surfaceUpdatedAt["events"])
        } finally { network.shutdown() }
    }
    @Test fun coldSnapshotPreservesTypedDataResourceTimesAndHistoryCursor(): Unit = runBlocking {
        val cache = NativeMlsSnapshotCache(temporary.newFolder(), { at + 30_000 })
        val saved = snapshot().accept(mapOf("overview" to overview()), at + 10_000, false)
        val older = replace(fixture("commentary"), "next_page_token", JsonPrimitive("history-tail"))
        cache.save(id, saved.older(older)); val cold = cache.load(id)!!.details(match())!!
        assertEquals(at + 10_000, cold.providerStamp!!.requestedAt); assertEquals(at, cold.soccer!!.surfaceUpdatedAt["events"])
        assertEquals("history-tail", cold.soccer!!.nextCursor); assertEquals(1, cold.soccer!!.commentaryPages)
        assertTrue(cold.soccer!!.savedSnapshot); assertEquals(40, cold.soccer!!.playerStats.size)
        assertEquals(78, cold.plays.size); assertEquals("2–2", cold.liveMatch!!.displayScore)
        cache.save(id, snapshot().accept(mapOf("overview" to overview()), at + 20_000, false))
        assertEquals("history-tail", cache.load(id)!!.details()!!.soccer!!.nextCursor)
    }
    @Test fun cacheRejectsForeignProviderSeasonBundledLineupsFutureExpiryAndTraversal(): Unit = runBlocking {
        var clock = at; val folder = temporary.newFolder(); val cache = NativeMlsSnapshotCache(folder, { clock })
        cache.save(id, snapshot()); assertNotNull(cache.load(id)); assertNull(cache.load("../$id"))
        val foreign = replace(overview(), "match_information", replace(overview()["match_information"], "match_id", JsonPrimitive("MLS-MAT-000BAD")))
        val wrong = snapshot().copy(surfaces = snapshot().surfaces + ("lineups" to NativeMlsSnapshotCache.Surface(at, foreign)))
        val other = NativeMlsSnapshotCache(temporary.newFolder(), { at }); other.save(id, wrong); assertNull(other.load(id))
        clock = at - 300_001; assertNull(cache.load(id)); clock = at + 31L * 86_400_000; assertNull(cache.load(id))
        clock = at
        val file = java.io.File(folder, "$id.json"); file.writeText(file.readText().replace("\"provider\":\"MLS\"", "\"provider\":\"EPL\"")); assertNull(cache.load(id))
    }
    @Test fun nativeColdCalendarIsCacheFirstAndNeverUsesEspnOrASeasonScan(): Unit = runBlocking {
        val cache = NativeMlsSnapshotCache(temporary.newFolder(), { at }); cache.save(id, snapshot())
        val network = server { MockResponse().setResponseCode(503) }
        try {
            val service = NativeMls(client(network), { at }, cache)
            val provider = NativeMlsProvider(client(network), { at }, cachedMatch = service::cachedMatch)
            assertEquals(match().id, provider.calendarGame("mls:$id")!!.id); assertEquals(0, network.requestCount)
            assertTrue(runCatching { service.load(match(), service.cached(match())) }.exceptionOrNull() is IOException)
            assertEquals(at, service.cached(match())!!.providerStamp!!.requestedAt)
            assertTrue(runCatching { provider.roster("mls:MLS-CLU-000008") }.exceptionOrNull() is UnsupportedCapabilityException)
            assertEquals(1, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun paginatedPlayerProfileFiltersSeasonDeduplicatesReportsAndDoesNotSumRatiosOrMaximumSpeed(): Unit = runBlocking {
        fun report(game: String, year: Int, goals: Int) = parseJson("""{"match_id":"MLS-MAT-$game","season":$year,"season_id":"${if (year == 2026) season else "MLS-SEA-000OLD"}","competition_id":"MLS-COM-000001","player_statistics":{"player_id":"MLS-OBJ-000396","goals":$goals,"assists":0,"normalized_player_minutes":90,"passes_conversion_rate":0.8,"maximum_speed":34,"xG":0.25}}""")
        val first = report("000AAA", 2026, 1); val second = report("000AAB", 2026, 2); val old = report("000AAC", 2025, 99)
        val network = server { request ->
            MockResponse().setBody(when {
                request.requestUrl!!.encodedPath.endsWith("/seasons") -> """{"seasons":[{"season":2026,"season_id":"$season"}]}"""
                request.requestUrl!!.queryParameter("page_token") == null -> buildJsonObject { put("match_log", JsonArray(listOf(first, old))); put("next_page_token", "second") }.toString()
                else -> buildJsonObject { put("match_log", JsonArray(listOf(first, second))) }.toString()
            })
        }
        try {
            val person = Athlete("mls:MLS-OBJ-000396", "Selected identity", "10")
            val profile = NativeMlsProvider(client(network), { at }).athlete(person)
            assertEquals(person, profile.athlete); assertEquals("3", profile.headlineStats["Goals"])
            assertEquals("0", profile.headlineStats["Assists"]); assertEquals("180", profile.headlineStats["Minutes (normalized)"])
            assertEquals("2", profile.bio["Reported matches"]); assertEquals("MLS", profile.bio["Source"])
            assertEquals("0.5", profile.stats["XG"]); assertFalse(profile.stats.containsKey("Maximum Speed")); assertFalse(profile.stats.containsKey("Passes Conversion Rate"))
            assertEquals(3, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun scheduleUsesTheVerifiedRegularSeasonAndFiltersTheRequestedLocalDay(): Unit = runBlocking {
        val date = Dates.parseIso("2026-09-20T12:00:00Z")!!
        val network = server { request ->
            val path = request.requestUrl!!.encodedPath
            MockResponse().setBody(when {
                path.contains(NativeMlsClient.PLAYOFFS) -> """{"seasons":[]}"""
                path.endsWith("/seasons") -> """{"seasons":[{"season":2026,"season_id":"$season"}]}"""
                else -> replace(fixture("schedule"), "next_page_token", JsonNull).toString()
            })
        }
        try {
            val matches = NativeMlsProvider(client(network), { at }).schedule(date, 1)
            assertTrue(matches.isNotEmpty()); assertTrue(matches.all { Dates.isSameDay(it.startMillis, date) })
            val requests = (1..network.requestCount).map { network.takeRequest() }
            val query = requests.single { it.requestUrl!!.encodedPath == "/matches/seasons/$season" }.requestUrl!!
            assertEquals(NativeMlsClient.REGULAR, query.queryParameter("competition_id")); assertNotNull(query.queryParameter("match_date[gte]"))
            assertTrue(requests.none { "espn" in it.path.orEmpty() || "/v1" in it.path.orEmpty() })
            assertTrue(requests.none { NativeMlsClient.PLAYOFFS in it.path.orEmpty() || it.requestUrl!!.queryParameter("competition_id") == NativeMlsClient.PLAYOFFS })
        } finally { network.shutdown() }
    }
    @Test fun snapshotFileCountSizeAndPendingWritesAreBounded(): Unit = runBlocking {
        val folder = temporary.newFolder(); val cache = NativeMlsSnapshotCache(folder, { at })
        for (number in 1..14) {
            val newId = "MLS-MAT-${number.toString().padStart(6, '0')}"
            val raw = replace(overview(), "match_information", replace(overview()["match_information"], "match_id", JsonPrimitive(newId)))
            cache.save(newId, NativeMlsSnapshotCache.Snapshot().accept(mapOf("overview" to raw), at, true))
        }
        assertEquals(12, folder.listFiles()!!.count { it.extension == "json" }); assertFalse(folder.listFiles()!!.any { it.extension == "pending" })
        val huge = replace(overview(), "unusedPadding", JsonPrimitive("x".repeat(2 * 1024 * 1024)))
        val smallFolder = temporary.newFolder(); val smallCache = NativeMlsSnapshotCache(smallFolder, { at })
        smallCache.save(id, NativeMlsSnapshotCache.Snapshot().accept(mapOf("overview" to huge), at, true))
        assertNull(smallCache.load(id)); assertTrue(smallFolder.listFiles().orEmpty().isEmpty())
    }
    @Test fun eventPagesAreCollectedCompletelyAndEveryPageKeepsItsNativeIdentity(): Unit = runBlocking {
        val source = fixture("key-events"); val all = source["events"].array
        for (foreign in listOf(false, true)) {
            val network = server { request ->
                val page = if (request.requestUrl!!.queryParameter("page_token") == null) replace(replace(source, "events", JsonArray(all.take(40))), "next_page_token", JsonPrimitive("tail"))
                else replace(replace(source, "events", JsonArray(all.drop(40))), "match_info", if (foreign) replace(source["match_info"], "match_id", JsonPrimitive("MLS-MAT-000BAD")) else source["match_info"]!!)
                MockResponse().setBody(page.toString())
            }
            try {
                val result = runCatching { client(network).complete("/matches/$id/key_events", "events", mapOf("per_page" to "100")) { NativeMlsMapping.resourceIdentity(it["match_info"], match(), season) } }
                if (foreign) assertTrue(result.exceptionOrNull() is IOException) else {
                    val raw = result.getOrThrow(); assertNull(NativeMlsClient.cursor(raw))
                    assertEquals(78, NativeMlsDetails.events(raw, match(), season, overview()).size)
                }
                assertEquals(2, network.requestCount)
            } finally { network.shutdown() }
        }
    }
    @Test fun repeatedEventCursorIsUnavailableRatherThanASuccessfulPartialTimeline(): Unit = runBlocking {
        val network = server { MockResponse().setBody(replace(replace(fixture("key-events"), "events", JsonArray(emptyList())), "next_page_token", JsonPrimitive("same")).toString()) }
        try {
            val result = runCatching { client(network).complete("/matches/$id/key_events", "events", mapOf("per_page" to "100")) { true } }
            assertTrue(result.exceptionOrNull() is IOException); assertEquals(2, network.requestCount)
        } finally { network.shutdown() }
    }
    @Test fun savedFollowsBridgeSourceAliasesButCannotOverrideAnExplicitDifferentNativeClub() {
        val club = NativeMlsMapping.club(fixture("clubs")["clubs"].array.first())!!
        assertTrue(FavoriteTeam(club.league.path, "constructed-legacy-id", "Legacy name", "LAF").matches(club))
        val native = FavoriteTeam.from(club)
        assertTrue(native.matches(club)); assertFalse(native.matches(club.copy(id = "mls:MLS-CLU-000BAD")))
        assertTrue(TeamIdentity.sameAbbreviation(club.league.path, "RBN", "RBNY"))
        assertFalse(TeamIdentity.sameAbbreviation("hockey/nhl", "RBN", "RBNY"))
    }
}
