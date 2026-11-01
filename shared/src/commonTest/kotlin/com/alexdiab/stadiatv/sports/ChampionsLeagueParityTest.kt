package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.TestHttp
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.foundation.SportsIOException
import com.alexdiab.stadiatv.net.parseJson
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChampionsLeagueParityTest {
    private fun row(status: String) = parseJson("""{"id":123,"home":{"id":5,"name":"Home"},"away":{"id":6,"name":"Away"},"status":{"utcTime":"2026-10-30T20:00:00Z","scoreStr":"2 - 1",$status}}""")
    private val provider=ChampionsLeagueProvider(NativeFotMobClient(TestHttp { error("No network expected") }))
    @Test fun mapsLongKeyHalftimeAndAddedTimeLikeIOS() {
        val half=provider.match(row(""""started":true,"reason":{"longKey":"halftime"}"""))!!
        assertEquals(GameState.LIVE,half.state); assertEquals("HT",half.statusDetail)
        val added=provider.match(row(""""started":true,"reason":{"shortKey":"2nd_half"},"liveTime":{"minute":90,"added":4}"""))!!
        assertEquals("90+4'",added.statusDetail); assertEquals("2",added.home.score); assertEquals("1",added.away.score)
        assertEquals("FotMob",added.nativeReference?.provider)
    }
    @Test fun unknownAndPostponedStatesDoNotFabricateLiveScores() {
        for (reason in listOf("postponed","unknown_provider_status")) {
            val game=provider.match(row(""""started":true,"reason":{"shortKey":"$reason"}"""))!!
            assertEquals(GameState.PRE,game.state); assertNull(game.home.score)
        }
        assertEquals(GameState.FINAL,provider.match(row(""""finished":true"""))!!.state)
    }
    @Test fun neverSubstitutesAnotherCompetition() = runTest {
        val http=TestHttp { parseJson("""{"details":{"id":87},"fixtures":{"allMatches":[]}}""") }
        assertFailsWith<SportsIOException> { ChampionsLeagueProvider(NativeFotMobClient(http)).teams() }
        assertTrue(http.calls.single().contains("id=42"))
    }
}
