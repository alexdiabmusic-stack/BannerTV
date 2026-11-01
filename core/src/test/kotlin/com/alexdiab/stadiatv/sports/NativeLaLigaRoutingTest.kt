package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NativeLaLigaRoutingTest {
    @Test fun nativeOptaAndClubFailuresNeverFallThroughToEspnButLegacyNumbersKeepTheirProvider(): Unit = runBlocking {
        val league = NativeLaLigaMapping.league
        var fallback = 0; var native = 0
        val provider = object : SportsProvider {
            override val league = league
            override val providerId = "LaLiga"
            override val capabilities = setOf(SportsCapability.Rosters, SportsCapability.Players, SportsCapability.GameDetails)
            override suspend fun scores(date: Long?) = emptyList<Match>()
            override suspend fun schedule(start: Long, days: Int) = emptyList<Match>()
            override suspend fun roster(teamId: String): List<RosterGroup> { native++; throw IOException("Constructed native failure") }
            override suspend fun athlete(athlete: Athlete): AthleteOverview { native++; throw IOException("Constructed native failure") }
            override suspend fun gameCentre(match: Match): GameCentre { native++; throw IOException("Constructed native failure") }
        }
        val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain -> fallback++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val repo = SportsRepository(mapOf(league.path to provider), EspnApi(http))
        assertTrue(runCatching { repo.roster(league, "laliga:15") }.exceptionOrNull() is IOException)
        assertTrue(runCatching { repo.athlete(league, Athlete("laliga:p60772", "Courtois")) }.exceptionOrNull() is IOException)
        val raw = parseJson(javaClass.getResource("/parity/laliga-official-matches-finished.source.json")!!.readText())["matches"].array.first()
        assertTrue(runCatching { repo.gameCentre(NativeLaLigaMapping.match(raw, 2026, "verified-2026")!!) }.exceptionOrNull() is IOException)
        assertEquals(3, native); assertEquals(0, fallback)
        assertTrue(runCatching { repo.athlete(league, Athlete("fotmob:60772", "Foreign")) }.isFailure)
        assertEquals(0, fallback); assertEquals(3, native)
        runCatching { repo.roster(league, "15") }; runCatching { repo.athlete(league, Athlete("60772", "Legacy")) }
        assertEquals(3, native); assertTrue(fallback >= 2)
    }
}
