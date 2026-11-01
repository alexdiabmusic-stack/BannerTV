package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.TestHttp
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.foundation.SportsTime
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CflFixtureParityTest {
    private fun fixture(name:String)=parseJson(requireNotNull(javaClass.getResource("/ios/cfl/$name.json")).readText())
    private fun http(fixtureName: String? = null)=TestHttp { url ->
        when {
            "/fixtures?" in url -> fixtureName?.let { JsonArray(listOf(fixture(it))) } ?: fixture("fixtures-season75")
            "/fixtures/6638" in url -> fixture("fixture-finished")
            "/leaders/" in url -> fixture("leaders-2026")
            "/standings/" in url -> fixture("standings-2026")
            "/roster" in url -> fixture("roster-team19")
            else -> fixture(url.substringAfterLast('/'))
        }
    }
    @Test fun usesSeason75AndExplicitPaginationForAllNinetyFiveFixtures() = runTest {
        val http=http()
        assertEquals(95,fixture("fixtures-season75").array.size)
        val matches=CflProvider(http).scores(SportsTime.parseIso("2026-08-29T12:00:00Z"))
        assertTrue(matches.isNotEmpty())
        assertTrue(http.calls.any { it.endsWith("fixtures?season_id=75&limit=300") })
        val game=matches.single { it.id == "cfl:6638" }
        assertEquals(GameState.FINAL,game.state)
        assertEquals("28",game.home.score); assertEquals("23",game.away.score)
        assertEquals(75,game.nativeReference?.season)
    }
    @Test fun scheduledFixtureHasNoInventedScoreAndUnresolvedGreyCupIsOmitted() = runTest {
        val provider=CflProvider(http("fixture-scheduled")) { 1_700_000_000_000 }
        val match=provider.scores(SportsTime.parseIso("2026-10-24T12:00:00Z")).single()
        assertEquals(GameState.PRE,match.state); assertNull(match.home.score)
        assertTrue(CflProvider(http("fixture-greycup-tbd")).scores().isEmpty())
    }
    @Test fun standingsRostersAndLeadersUseRecordedIOSShapes() = runTest {
        val provider=CflProvider(http()) { SportsTime.parseIso("2026-10-01T12:00:00Z")!! }
        assertTrue(provider.standings().flatMap { it.rows }.isNotEmpty())
        assertTrue(provider.roster("cfl:19").flatMap { it.athletes }.isNotEmpty())
        assertTrue(provider.leaders().any { it.title.contains("passing") })
        assertFailsWith<Exception> { provider.roster("19") }
    }
}
