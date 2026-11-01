package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.GameShot
import org.junit.Assert.*
import org.junit.Test

class PhoneBasketballCourtTest {
    private fun shot(x: Float = 0f, y: Float = 0f, made: Boolean = true) = GameShot("a", x, y, made, true, 1, "Player", "Reported")

    @Test fun `half court uses unmodified basket origin feet and preserves off-canvas coordinates`() {
        val hoop = PhoneBasketballCourt.point(0f, 0f)
        assertEquals(.5f, hoop.u, .00001f); assertEquals(1f - 5.25f / 47f, hoop.v, .00001f)
        assertEquals(0f, PhoneBasketballCourt.point(-25f, 41.75f).u, 0f)
        assertEquals(0f, PhoneBasketballCourt.point(-25f, 41.75f).v, 0f)
        assertTrue(PhoneBasketballCourt.point(0f, 60f).v < 0f)
        assertTrue(PhoneBasketballCourt.point(30f, 0f).u > 1f)
    }
    @Test fun `beyond half and other outside counts are distinct without relocating an attempt`() {
        val shots = listOf(shot(), shot(y = 41.75f), shot(y = 41.76f), shot(x = 28f), shot(y = -6f), shot(x = Float.NaN))
        val on = shots.count(PhoneBasketballCourt::onCourt)
        val beyond = shots.count(PhoneBasketballCourt::beyondHalf)
        assertEquals(2, on); assertEquals(1, beyond); assertEquals(3, shots.size - on - beyond)
    }
    @Test fun `court lines join corners and restricted semicircle in real units`() {
        val three = PhoneBasketballCourt.threePointLine()
        assertEquals(PhoneBasketballCourt.point(-22f, -5.25f), three.first())
        assertEquals(PhoneBasketballCourt.point(22f, -5.25f), three.last())
        assertEquals(PhoneBasketballCourt.point(0f, 23.75f).v, three[26].v, .001f)
        val restricted = PhoneBasketballCourt.restrictedArea()
        assertEquals(PhoneBasketballCourt.point(4f, 0f).u, restricted.first().u, .001f)
        assertEquals(PhoneBasketballCourt.point(-4f, 0f).u, restricted.last().u, .001f)
        assertEquals(PhoneBasketballCourt.point(0f, 4f).v, restricted[18].v, .001f)
    }
    @Test fun `summary includes off-canvas attempts and three point markers are larger`() {
        assertEquals("Shot chart. 1 made of 2 attempts.", PhoneBasketballCourt.summary(listOf(shot(), shot(y = 60f, made = false))))
        assertTrue(PhoneBasketballCourt.markerRadius(3) > PhoneBasketballCourt.markerRadius(2))
        assertEquals(4f, PhoneBasketballCourt.markerRadius(null), 0f)
    }
}
