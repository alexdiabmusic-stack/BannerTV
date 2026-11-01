package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** In-process miniature feed: no provider credentials, external traffic, or runtime fixture injection. */
class NativeGameCentresRecoveryTest {
    private val overview = """{"matchId":"900007","competitionId":"8","season":"2026","kickoff":"2026-10-23 19:00:00","period":"FullTime","homeTeam":{"id":"100","name":"Test Home","abbr":"HOM","score":1},"awayTeam":{"id":"200","name":"Test Away","abbr":"AWY","score":0}}"""
    private val match = NativePremierLeagueMapping.game(parseJson(overview))!!
    private fun response(path: String): String = when (path.substringAfterLast('/')) {
        "900007" -> overview
        "events" -> """{"matchId":"900007","homeTeam":{"id":"100"},"awayTeam":{"id":"200"}}"""
        "lineups" -> """{"matchId":"900007","home_team":{},"away_team":{}}"""
        "stats" -> "[]"
        "officials" -> """{"matchId":"900007","matchOfficials":[]}"""
        "commentary" -> """{"matchId":"900007","data":[]}"""
        else -> error("Unexpected constructed path $path")
    }
    @Test fun immediateNetworkRestorationRetriesTheFinishedGameWithoutWaitingForAnotherPoll(): Unit = runBlocking {
        var offline = true; var clock = 1000L; val calls = AtomicInteger()
        val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            if (offline) throw IOException("Constructed offline transport")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response(chain.request().url.encodedPath).toResponseBody("application/json".toMediaType())).build()
        }.build())
        val centres = NativeGameCentres(http, soccer = NativeSoccer(http, "https://miniature.invalid", { clock }), now = { clock })
        assertNull(centres.load(match, null)); assertEquals(1, calls.get())
        offline = false; clock += 1000
        assertNull(centres.load(match, null, force = true)); assertEquals(1, calls.get())
        centres.onNetworkRestored()
        val recovered = centres.load(match, null)!!
        assertEquals("1", recovered.liveMatch!!.home.score); assertTrue(recovered.soccer!!.errors.isEmpty())
        assertEquals(7, calls.get())
    }
    @Test fun validatedConnectivityDoesNotBypassHttpOrSchemaBackoff(): Unit = runBlocking {
        for (failure in listOf(HttpStatusException(503), HttpStatusException(429, 300), HttpStatusException(403), IllegalArgumentException("Constructed schema failure"))) {
            var blocked = true; var clock = 1000L; val calls = AtomicInteger()
            val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet(); if (blocked && failure is IOException) throw failure
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if (blocked) "{" else response(chain.request().url.encodedPath)).toResponseBody("application/json".toMediaType())).build()
            }.build())
            val centres = NativeGameCentres(http, soccer = NativeSoccer(http, "https://miniature.invalid", { clock }), now = { clock })
            assertNull(centres.load(match, null)); blocked = false
            centres.onNetworkRestored(); assertNull(centres.load(match, null)); assertEquals(failure.toString(), 1, calls.get())
            clock += 86_400_001
            assertNotNull(centres.load(match, null)); assertEquals(7, calls.get())
        }
    }
    @Test fun reconnectAlsoReleasesIndependentTransportFailuresButKeepsProviderRejections(): Unit = runBlocking {
        for (transport in listOf(true, false)) {
            var blocked = true; val eventCalls = AtomicInteger()
            val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                if (path.endsWith("events")) {
                    eventCalls.incrementAndGet()
                    if (blocked) throw if (transport) IOException("Constructed lost event connection") else HttpStatusException(503)
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response(path).toResponseBody("application/json".toMediaType())).build()
            }.build())
            val centres = NativeGameCentres(http, soccer = NativeSoccer(http, "https://miniature.invalid", { 1000L }), now = { 1000L })
            val first = centres.load(match, null)!!; assertTrue("events" in first.soccer!!.errors)
            blocked = false; centres.onNetworkRestored()
            val recovered = centres.load(match, first, force = true)!!
            assertEquals(if (transport) 2 else 1, eventCalls.get())
            assertEquals(!transport, "events" in recovered.soccer!!.errors)
        }
    }
}
