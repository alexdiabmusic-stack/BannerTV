package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.net.HttpGateway
import com.alexdiab.stadiatv.net.parseJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Constructed search responses. No catalog recommendation or listening action is injected into runtime. */
class TeamPodcastResolverTest {
    private val team = FavoriteTeam("hockey/nhl", "nhl:9", "Ottawa Senators", "OTT")
    private val seed = TeamPodcastSeed("nhl-ottawa-senators", "NHL", "Hockey", team.name,
        listOf(team.name, "Senators"), "CA", listOf("Ottawa Senators podcast", "Locked On Ottawa Senators"))
    private fun show(title: String, feed: String = "https://publisher.example/feed") = TeamPodcastCandidate(feed, title)
    private fun body(title: String = "Ottawa Senators Daily", feed: String = "https://publisher.example/feed") =
        """{"results":[{"collectionName":"$title","feedUrl":"$feed","artistName":"Independent publisher"}]}"""

    @Test fun sourceWeightsKeepTeamEvidenceSeparateFromGenericSearchResults() {
        assertEquals(195, TeamPodcastResolver.score(show("Ottawa Senators Daily"), seed))
        assertEquals(75, TeamPodcastResolver.score(show("Locked On Senators NHL"), seed))
        assertEquals(55, TeamPodcastResolver.score(show("Senators"), seed.copy(aliases = seed.aliases + "Senators")))
        assertEquals(20, TeamPodcastResolver.score(show("Locked On Senatorship NHL"), seed))
        assertTrue(TeamPodcastResolver.rank(listOf(show("A general hockey roundup"), show("Senators")), seed).isEmpty())
        assertEquals(1, TeamPodcastResolver.rank(listOf(show("Ottawa Senators Daily")), seed).size)
        val giants = seed.copy(teamName = "New York Giants", league = "NFL", aliases = listOf("New York Giants", "Giants"))
        assertTrue(TeamPodcastResolver.rank(listOf(show("Locked On Giants MLB")), giants).isEmpty())
    }
    @Test fun seedIdentityIsLeagueScopedExactAndNeverAProviderNumberJoin() {
        assertEquals(seed, TeamPodcastResolver.seedFor(team.copy(teamId = "different-provider:9"), listOf(seed)))
        assertEquals(seed, TeamPodcastResolver.seedFor(team.copy(name = "Senators"), listOf(seed)))
        assertNull(TeamPodcastResolver.seedFor(team.copy(leaguePath = "baseball/mlb"), listOf(seed)))
        assertNull(TeamPodcastResolver.seedFor(team.copy(name = "Ottawa Senators Academy"), listOf(seed)))
        assertNull(TeamPodcastResolver.seedFor(team, listOf(seed, seed.copy(id = "ambiguous"))))
    }
    @Test fun feedIdentityNormalizesOnlySafeUrlComponentsWithoutMergingDistinctPathsOrQueries() {
        assertEquals("https://example.com/feed", TeamPodcastResolver.feedKey("https://EXAMPLE.com:443/feed#top"))
        assertNull(TeamPodcastResolver.feedKey("ftp://example.com/feed"))
        assertNull(TeamPodcastResolver.feedKey("https://user:password@example.com/feed"))
        assertNotEquals(TeamPodcastResolver.feedKey("https://example.com/Feed"), TeamPodcastResolver.feedKey("https://example.com/feed"))
        val ranked = TeamPodcastResolver.rank(listOf(show("Ottawa Senators", "https://EXAMPLE.com:443/feed#one"),
            show("Ottawa Senators", "https://example.com/feed#two"), show("Ottawa Senators", "https://example.com/feed?edition=2")), seed)
        assertEquals(2, ranked.size)
    }
    @Test fun publicSearchUsesSeedCountryAndQueriesAndRejectsUnrelatedReturnedFeeds(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(body()))
            server.enqueue(MockResponse().setBody(body("A generic NHL roundup", "https://other.example/feed")))
            val resolver = TeamPodcastResolver(HttpGateway(OkHttpClient()), server.url("/search").toString(), now = { 1234 })
            val result = resolver.resolve(team, listOf(seed), emptyList())
            assertFalse(result.unavailable); assertEquals(1234L, result.checkedAt)
            assertEquals(listOf("Ottawa Senators Daily"), result.shows.map { it.title })
            for (query in seed.searchTerms) {
                val request = server.takeRequest()
                assertEquals("/search", request.requestUrl!!.encodedPath)
                assertEquals(query, request.requestUrl!!.queryParameter("term")); assertEquals("CA", request.requestUrl!!.queryParameter("country"))
                assertEquals("50", request.requestUrl!!.queryParameter("limit")); assertEquals("podcast", request.requestUrl!!.queryParameter("entity"))
                assertEquals("max-age=0", request.getHeader("Cache-Control")); assertNull(request.getHeader("Authorization"))
            }
        } finally { server.shutdown() }
    }
    @Test fun completeNonemptyResolutionCachesForSevenDaysAndExplicitRefreshRevalidates(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            var clock = 1000L
            repeat(4) { server.enqueue(MockResponse().setBody(body())) }
            val resolver = TeamPodcastResolver(HttpGateway(OkHttpClient()), server.url("/search").toString(), now = { clock })
            val initial = resolver.resolve(team, listOf(seed), emptyList())
            clock += 6 * 86_400_000
            assertEquals(initial, resolver.resolve(team, listOf(seed), emptyList())); assertEquals(2, server.requestCount)
            val updated = resolver.resolve(team, listOf(seed), emptyList(), refresh = true)
            assertEquals(clock, updated.checkedAt); assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun denialRetainsOriginalCheckTimeAndCannotBeBypassedByRefreshOrAnotherTeam(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            var clock = 1000L
            repeat(2) { server.enqueue(MockResponse().setBody(body())) }
            server.enqueue(MockResponse().setResponseCode(403))
            val resolver = TeamPodcastResolver(HttpGateway(OkHttpClient()), server.url("/search").toString(), now = { clock })
            resolver.resolve(team, listOf(seed), emptyList())
            clock += 8 * 86_400_000L
            val retained = resolver.resolve(team, listOf(seed), emptyList())
            assertTrue(retained.unavailable); assertTrue(retained.retainedPrevious); assertEquals(1000L, retained.checkedAt)
            assertEquals(1, retained.shows.size); assertEquals(3, server.requestCount)
            resolver.resolve(team, listOf(seed), emptyList(), refresh = true)
            val other = resolver.resolve(team.copy(name = "Boston Bruins"), listOf(seed), emptyList(), refresh = true)
            assertTrue(other.unavailable); assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun outageKeepsVerifiedPublisherAndArtworkAheadOfAThinnerDuplicateInTheBundledCatalog(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val rich = body().replace("\"artistName\":", "\"artworkUrl600\":\"https://publisher.example/cover.jpg\",\"artistName\":")
            repeat(2) { server.enqueue(MockResponse().setBody(rich)) }
            server.enqueue(MockResponse().setResponseCode(403))
            val resolver = TeamPodcastResolver(HttpGateway(OkHttpClient()), server.url("/search").toString(), now = { 1000 })
            val local = listOf(show("Ottawa Senators Catalog"))
            resolver.resolve(team, listOf(seed), local)
            val retained = resolver.resolve(team, listOf(seed), local, refresh = true)
            assertTrue(retained.unavailable); assertTrue(retained.retainedPrevious)
            assertEquals(1, retained.shows.size)
            assertEquals("Ottawa Senators Daily", retained.shows.single().title)
            assertEquals("Independent publisher", retained.shows.single().publisher)
            assertEquals("https://publisher.example/cover.jpg", retained.shows.single().image)
        } finally { server.shutdown() }
    }
    @Test fun successfulEmptyIsNotSevenDayCoverageAndMalformedSearchKeepsOnlyDirectCatalogMatches(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            var clock = 1000L
            repeat(2) { server.enqueue(MockResponse().setBody("""{"results":[]}""")) }
            repeat(2) { server.enqueue(MockResponse().setBody("{}")) }
            val resolver = TeamPodcastResolver(HttpGateway(OkHttpClient()), server.url("/search").toString(), now = { clock })
            val empty = resolver.resolve(team, listOf(seed), emptyList()); assertFalse(empty.unavailable); assertTrue(empty.shows.isEmpty())
            clock += 61_000
            val local = listOf(show("Ottawa Senators Independent"), show("A generic hockey show", "https://generic.example/feed"))
            val result = resolver.resolve(team, listOf(seed), local)
            assertTrue(result.unavailable); assertEquals(listOf("Ottawa Senators Independent"), result.shows.map { it.title })
            assertEquals(4, server.requestCount); assertEquals(1000L, result.checkedAt)
        } finally { server.shutdown() }
    }
    @Test fun callerCancellationPropagatesInsteadOfBeingReportedAsACompletedEmptySearch(): Unit = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody(body()))
            val resolver = TeamPodcastResolver(HttpGateway(OkHttpClient()), server.url("/search").toString())
            assertTrue(runCatching { withTimeout(150) { resolver.resolve(team, listOf(seed), emptyList()) } }.exceptionOrNull() is TimeoutCancellationException)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun registryDecodingBoundsQueriesAndRejectsAmbiguousOrMissingIdentities() {
        val row = """{"id":"nhl-ottawa-senators","league":"NHL","sport":"Hockey","team_name":"Ottawa Senators","aliases":["Ottawa Senators","Senators"],"country_storefront":"CA","search_terms":["Ottawa Senators podcast"]}"""
        val parsed = TeamPodcastResolver.seeds(parseJson("""{"teams":[$row]}""")).single()
        assertEquals("CA", parsed.storefront); assertEquals(team.name, parsed.teamName)
        assertTrue(runCatching { TeamPodcastResolver.seeds(parseJson("""{"teams":[$row,$row]}""")) }.isFailure)
        assertTrue(runCatching { TeamPodcastResolver.seeds(parseJson("{}")) }.isFailure)
        assertTrue(runCatching { TeamPodcastResolver.seeds(parseJson("""{"teams":[${row.replace("\"CA\"", "\"not-a-country\"")}]}""")) }.isFailure)
    }
}
