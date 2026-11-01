package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.HttpGateway
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class SportsRepositoryCompletionTest {
    @Test fun finiteScoreboardBatchCompletesAfterAllProviders(): Unit = runBlocking {
        val leagues = League.ALL.take(3)
        val providers = leagues.associate { league -> league.path to object : SportsProvider {
            override val league = league
            override val capabilities = setOf(SportsCapability.Scores)
            override suspend fun scores(date: Long?) = emptyList<Match>()
            override suspend fun schedule(start: Long, days: Int) = emptyList<Match>()
        } }
        val repository = SportsRepository(providers, EspnApi(HttpGateway(OkHttpClient())))
        val results = withTimeout(2000) { repository.liveScores(leagues).toList() }
        assertEquals(3, results.size); assertEquals(leagues.toSet(), results.map { it.league }.toSet())
        assertTrue(results.all { it.error == null })
        assertTrue(withTimeout(2000) { repository.liveScores(emptyList()).toList() }.isEmpty())
    }
}
