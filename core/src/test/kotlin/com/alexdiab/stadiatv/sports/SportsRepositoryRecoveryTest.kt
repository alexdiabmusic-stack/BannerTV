package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SportsRepositoryRecoveryTest {
    private val league = League.find("football/nfl")!!
    private class Primary(override val league: League) : SportsProvider {
        override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Standings, SportsCapability.GameDetails)
        val scoreCalls = AtomicInteger(); var standingsCalls = 0
        var fail: Throwable? = IOException("Constructed offline failure")
        override suspend fun scores(date: Long?): List<Match> { scoreCalls.incrementAndGet(); fail?.let { throw it }; return emptyList() }
        override suspend fun schedule(start: Long, days: Int) = emptyList<Match>()
        override suspend fun standings(): List<StandingGroup> { standingsCalls++; return listOf(StandingGroup("Native division", emptyList(), "NFL")) }
    }
    private fun repository(primary: Primary, now: () -> Long = { 1000L }, fallback: () -> Unit = {}): SportsRepository {
        // Every fallback response is intercepted in-process: no credentials or external network.
        val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain ->
            fallback()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"events":[],"children":[]}""".toResponseBody("application/json".toMediaType())).build()
        }.build())
        return SportsRepository(mapOf(league.path to primary), EspnApi(http), now)
    }

    @Test fun aBrokenScoreboardCannotDisableStandingsAndStandingsSuccessCannotCloseTheScoreBreaker(): Unit = runBlocking {
        val source = Primary(league); val repo = repository(source)
        repeat(3) { repo.scoreboard(league) }
        assertEquals(3, source.scoreCalls.get())
        assertEquals("NFL", repo.standings(league).single().source); assertEquals(1, source.standingsCalls)
        repo.scoreboard(league); assertEquals(3, source.scoreCalls.get())
        repo.schedule(league, 1000, 1); repo.scoreboard(league); assertEquals(3, source.scoreCalls.get())
    }

    @Test fun validatedNetworkRestorationReleasesTransportFailuresWithoutRestartingTheProcess(): Unit = runBlocking {
        val source = Primary(league); val repo = repository(source)
        repeat(3) { repo.scoreboard(league) }; repo.scoreboard(league)
        assertEquals(3, source.scoreCalls.get())
        source.fail = null; repo.onNetworkRestored(); repo.scoreboard(league)
        assertEquals(4, source.scoreCalls.get()); repo.scoreboard(league); assertEquals(5, source.scoreCalls.get())
    }

    @Test fun reconnectionDoesNotBypassHttpRateLimitBlockedOrNonIoFailureBackoff(): Unit = runBlocking {
        for (error in listOf(HttpStatusException(403), HttpStatusException(429, 300), HttpStatusException(503), IllegalArgumentException("Constructed schema change"))) {
            val source = Primary(league).apply { fail = error }; val repo = repository(source)
            repeat(3) { repo.scoreboard(league) }; source.fail = null
            repo.onNetworkRestored(); repo.scoreboard(league)
            assertEquals(error.toString(), 3, source.scoreCalls.get())
        }
    }

    @Test fun mixedHttpAndTransportFailuresKeepTheirBackoffUntilTheDeadline(): Unit = runBlocking {
        var clock = 1000L
        val source = Primary(league); val repo = repository(source, { clock })
        repo.scoreboard(league); source.fail = HttpStatusException(429); repo.scoreboard(league)
        source.fail = IOException("Constructed offline"); repo.scoreboard(league)
        repo.onNetworkRestored(); source.fail = null; repo.scoreboard(league); assertEquals(3, source.scoreCalls.get())
        clock += 300_000; repo.scoreboard(league); assertEquals(4, source.scoreCalls.get())
    }

    @Test fun expiredBreakerCanOpenForANewTransportOutageAndRecover(): Unit = runBlocking {
        var clock = 1000L; val source = Primary(league).apply { fail = HttpStatusException(503) }
        val repo = repository(source, { clock }); repeat(3) { repo.scoreboard(league) }
        clock += 300_000; source.fail = IOException("New network outage")
        repeat(3) { repo.scoreboard(league) }; repo.scoreboard(league); assertEquals(6, source.scoreCalls.get())
        source.fail = null; repo.onNetworkRestored(); repo.scoreboard(league); assertEquals(7, source.scoreCalls.get())
    }

    @Test fun cancellationAndFatalErrorsAreNotConvertedIntoFallbackOrCircuitFailures(): Unit = runBlocking {
        var fallbackCalls = 0; val source = Primary(league).apply { fail = CancellationException("Caller left") }
        val repo = repository(source, fallback = { fallbackCalls++ })
        repeat(3) { assertTrue(runCatching { repo.scoreboard(league) }.exceptionOrNull() is CancellationException) }
        source.fail = AssertionError("Constructed fatal error")
        assertTrue(runCatching { repo.scoreboard(league) }.exceptionOrNull() is AssertionError)
        source.fail = null; repo.scoreboard(league)
        assertEquals(5, source.scoreCalls.get()); assertEquals(0, fallbackCalls)
    }

    @Test fun simultaneousFailuresCountAtomicallyAndRemainCapabilityScoped(): Unit = runBlocking {
        val source = Primary(league); val repo = repository(source)
        coroutineScope { List(3) { async(Dispatchers.Default) { repo.scoreboard(league) } }.awaitAll() }
        repo.scoreboard(league); assertEquals(3, source.scoreCalls.get())
        assertEquals("NFL", repo.standings(league).single().source)
    }
}
