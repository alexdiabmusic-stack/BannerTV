package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import org.junit.Assert.*
import org.junit.Test

class NativeGameInsightsTest {
    private fun shots(json: String) = NativeGameInsights.nbaShots(parseJson(json).array, "GS")
    @Test fun `native coordinates use iOS tenths of feet conversion and team aliases`() {
        val shot = shots("""[{"actionNumber":1,"actionType":"3pt","shotResult":"Made","xLegacy":150,"yLegacy":200,"shotDistance":25,"teamTricode":"GSW","period":2}]""").single()
        assertEquals(15f, shot.x, 0.001f); assertEquals(20f, shot.y, 0.001f); assertTrue(shot.home); assertTrue(shot.made)
        assertEquals(3, shot.value); assertEquals(25f, shot.distanceFeet!!, 0.001f)
    }
    @Test fun `shot value comes from action type not descriptive subtype and missing distance uses real geometry`() {
        val shot = shots("""[{"actionNumber":1,"actionType":"3pt","subType":"Pullup Jump shot","xLegacy":150,"yLegacy":200,"teamTricode":"GSW"}]""").single()
        assertEquals(3, shot.value); assertEquals(25f, shot.distanceFeet!!, 0.001f)
        val two = shots("""[{"actionNumber":2,"actionType":"2pt","subType":"3 steps","xLegacy":10,"yLegacy":10,"teamTricode":"GSW"}]""").single()
        assertEquals(2, two.value); assertEquals(1.4f, two.distanceFeet!!, 0.001f)
    }
    @Test fun `negative reported distance is invalid even for a rim sentinel`() {
        assertTrue(shots("""[{"actionNumber":1,"actionType":"2pt","xLegacy":0,"yLegacy":0,"shotDistance":-1,"teamTricode":"GSW"}]""").isEmpty())
    }
    @Test fun `missing sentinel contradictory and non shot geometry is never fabricated`() {
        val values = shots("""[
          {"actionNumber":1,"actionType":"3pt","shotDistance":25,"teamTricode":"GSW"},
          {"actionNumber":2,"actionType":"2pt","xLegacy":0,"yLegacy":0,"shotDistance":9,"teamTricode":"GSW"},
          {"actionNumber":3,"actionType":"3pt","xLegacy":150,"yLegacy":200,"shotDistance":3,"teamTricode":"GSW"},
          {"actionNumber":4,"actionType":"freethrow","xLegacy":0,"yLegacy":150,"teamTricode":"GSW"},
          {"actionNumber":5,"actionType":"2pt","xLegacy":9999,"yLegacy":200,"teamTricode":"GSW"}
        ]""")
        assertTrue(values.isEmpty())
    }
    @Test fun `rim location is accepted only with a reported near zero distance and review replaces event`() {
        val values = shots("""[
          {"actionNumber":1,"actionType":"2pt","xLegacy":0,"yLegacy":0,"shotDistance":0,"shotResult":"Made","teamTricode":"LAL"},
          {"actionNumber":1,"actionType":"2pt","xLegacy":0,"yLegacy":0,"shotDistance":0,"shotResult":"Missed","teamTricode":"LAL"}
        ]""")
        assertEquals(1, values.size); assertFalse(values.single().made); assertFalse(values.single().home)
    }
    @Test fun `native event resolution requires both teams in the correct away home order`() {
        val match = Match("401", League.ALL.first(), 0, "", "", GameState.LIVE, "", TeamSide("", "", "GS"), TeamSide("", "", "LAL"))
        assertTrue(NativeGameInsights.sameTeams("LAL", "GSW", match))
        assertFalse(NativeGameInsights.sameTeams("GSW", "LAL", match))
        assertFalse(NativeGameInsights.sameTeams("", "GSW", match))
    }
    @Test fun `blocked native hosts back off and rate limit retry after takes precedence`() {
        assertEquals(300_000L, NativeGameInsights.cooldownMillis(403, null))
        assertEquals(3_600_000L, NativeGameInsights.cooldownMillis(429, 3_600))
        assertEquals(120_000L, NativeGameInsights.cooldownMillis(500, -1))
    }
}
