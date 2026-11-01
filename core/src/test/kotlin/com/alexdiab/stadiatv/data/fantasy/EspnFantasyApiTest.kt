package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.net.HttpGateway
import com.alexdiab.stadiatv.net.array
import com.alexdiab.stadiatv.net.get
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class EspnFantasyApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: EspnFantasyApi
    private val connection = FantasyConnection(FantasySport.Hockey.code, 2027, "12345", teamId = 1)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = EspnFantasyApi(HttpGateway(OkHttpClient()), baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun failure(cookies: EspnCookies? = null): FantasyException? = runBlocking {
        try {
            api.league(connection, cookies)
            null
        } catch (error: FantasyException) {
            error
        }
    }

    @Test
    fun `asks for the league with every view the screens need and the right path`() {
        server.enqueue(MockResponse().setBody("""{"teams": [{"id": 1}]}"""))
        val root = runBlocking { api.league(connection, cookies = null) }
        assertEquals(1, root["teams"].array.size)

        val request = server.takeRequest()
        assertEquals("/apis/v3/games/fhl/seasons/2027/segments/0/leagues/12345", request.requestUrl!!.encodedPath)
        // One repeated `view` parameter per view, which is how ESPN takes several.
        assertEquals(listOf("mSettings", "mTeam", "mRoster", "mMatchupScore", "mScoreboard", "mStatus"), request.requestUrl!!.queryParameterValues("view"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun `private league cookies go out as a cookie header and nowhere else`() {
        server.enqueue(MockResponse().setBody("""{"teams": []}"""))
        runBlocking { api.league(connection, EspnCookies("secret-token", "{ABC}")) }
        val request = server.takeRequest()
        assertEquals("espn_s2=secret-token; SWID={ABC}", request.getHeader("Cookie"))
        assertTrue(!request.path!!.contains("secret-token"))
    }

    @Test
    fun `a missing league is reported as not found`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"messages": ["Not Found"]}"""))
        assertTrue(failure() is FantasyException.LeagueNotFound)
    }

    @Test
    fun `a private league without cookies asks for them`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertTrue(failure() is FantasyException.AuthRequired)
    }

    @Test
    fun `the not-visible marker in a normal response also asks for cookies`() {
        server.enqueue(MockResponse().setBody("""{"details": [{"type": "AUTH_LEAGUE_NOT_VISIBLE"}]}"""))
        assertTrue(failure() is FantasyException.AuthRequired)
    }

    @Test
    fun `an HTML page is not mistaken for league data`() {
        server.enqueue(MockResponse().setBody("<!doctype html><html><body>Sign in</body></html>"))
        assertTrue(failure() is FantasyException.BadResponse)
    }

    @Test
    fun `server errors are not disguised as fantasy errors`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500)) }
        try {
            runBlocking { api.league(connection, null) }
            fail("expected an HTTP error")
        } catch (error: FantasyException) {
            fail("a 500 should surface as an HTTP error, not $error")
        } catch (_: Exception) {
            // the gateway's status exception
        }
    }
}
