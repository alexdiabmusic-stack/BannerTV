package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.TestHttp
import com.alexdiab.stadiatv.net.HttpStatusException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import kotlin.test.*

class NhlInteropContractTest {
    private fun fixture(name: String) = javaClass.getResource("/nhl/$name")!!.readText()

    private suspend fun CoroutineScope.scores(provider: SportsProvider, date: Long = 0): Pair<String?, SportsFailure?> {
        val result = CompletableDeferred<Pair<String?, SportsFailure?>>()
        AppleNhlDiscovery(provider, this).scores(date, date == 0L) { value, error -> result.complete(value to error) }
        return withTimeout(10_000) { result.await() }
    }

    @Test fun pinnedAppleFixtureProducesTheSwiftWireContract() = runBlocking {
        val http = TestHttp { Json.parseToJsonElement("""{"games":[${fixture("provider-live.json")}]}""") }
        val result = scores(NativeHockeyProvider(http))
        assertNull(result.second)
        assertEquals(Json.parseToJsonElement(fixture("score-v1.json")), Json.parseToJsonElement(result.first!!))
        assertTrue(http.calls.single().endsWith("/score/now"))

        // Optional integration probe exercises the same facade against the real provider.
        System.getProperty("banner.nhl.liveOutput")?.let { path ->
            val liveHttp = TestHttp { url ->
                val uri = URI(url)
                require(uri.scheme == "https" && uri.host == "api-web.nhle.com")
                val connection = uri.toURL().openConnection().apply { connectTimeout = 8000; readTimeout = 8000 }
                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                Json.parseToJsonElement(json)
            }
            val date = java.time.Instant.parse("2024-06-24T16:00:00Z").toEpochMilli()
            val response = scores(NativeHockeyProvider(liveHttp), date)
            assertNull(response.second)
            val payload = requireNotNull(response.first)
            assertTrue(Json.parseToJsonElement(payload).jsonObject.getValue("games").jsonArray.any {
                it.jsonObject.getValue("id").jsonPrimitive.content == "2023030417"
            })
            File(path).writeText(payload)
        }
        Unit
    }

    @Test fun intermissionStopsTheClockAndKeepsTheSameAppleGameIdentity() = runBlocking {
        val provider = NativeHockeyProvider(TestHttp { Json.parseToJsonElement("""{"games":[${fixture("provider-intermission.json")}]}""") })
        val game = Json.parseToJsonElement(scores(provider).first!!).jsonObject.getValue("games").jsonArray.single().jsonObject
        assertEquals("2023020204", game.getValue("id").jsonPrimitive.content)
        assertEquals(JsonNull, game["clock"])
        assertEquals(JsonNull, game["seconds"])
        assertEquals(false, game.getValue("running").jsonPrimitive.boolean)
    }

    @Test fun failureDoesNotReinterpretNhlIdsOrLeakProviderErrors() = runBlocking {
        val result = scores(NativeHockeyProvider(TestHttp { throw HttpStatusException(403, message="sensitive diagnostics") }))
        assertNull(result.first)
        assertEquals(403, result.second?.httpStatus)
        assertFalse(result.second!!.message.contains("sensitive"))
    }

    @Test fun longSchedulesUseAdjacentCalendarWindowsAndKeepOneGameIdentity() = runBlocking {
        val windows = mutableListOf<Pair<Long, Int>>()
        val game = NativeHockeyProvider.game(Json.parseToJsonElement(fixture("provider-live.json")))!!
        val unused = NativeHockeyProvider(TestHttp { error("unexpected network request") })
        val provider = object : SportsProvider by unused {
            override suspend fun schedule(start: Long, days: Int): List<com.alexdiab.stadiatv.domain.Match> {
                windows += start to days
                return listOf(game)
            }
        }
        val result = CompletableDeferred<String>()
        val start = java.time.Instant.parse("2024-03-08T16:00:00Z").toEpochMilli()
        AppleNhlDiscovery(provider, this).schedule(start, 370) { value, error ->
            if (error != null) result.completeExceptionally(AssertionError(error.message))
            else result.complete(requireNotNull(value))
        }
        val games = Json.parseToJsonElement(withTimeout(5000) { result.await() }).jsonObject.getValue("games").jsonArray
        assertEquals(1, games.size)
        assertEquals(listOf(366, 4), windows.map { it.second })
        assertEquals(com.alexdiab.stadiatv.foundation.SportsTime.addDays(windows[0].first, 366), windows[1].first)
    }

    @Test fun cancellingTheSwiftRequestReleasesTheProviderRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val provider = NativeHockeyProvider(TestHttp {
            started.complete(Unit)
            try { awaitCancellation() } finally { released.complete(Unit) }
        })
        val request = AppleNhlDiscovery(provider, this).scores(0, true) { _, _ -> fail("Cancelled requests must not deliver stale results") }
        withTimeout(5000) { started.await() }
        request.cancel()
        withTimeout(5000) { released.await() }
    }
}
