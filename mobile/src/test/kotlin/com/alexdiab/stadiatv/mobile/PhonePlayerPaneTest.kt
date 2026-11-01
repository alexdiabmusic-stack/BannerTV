package com.alexdiab.stadiatv.mobile

import org.junit.Assert.*
import org.junit.Test

class PhonePlayerPaneTest {
    @Test fun `stream copy switches keep the same actual game companion scope`() {
        assertEquals(phonePlayerCompanionKey("nhl:game-one", "playlist-one|10"), phonePlayerCompanionKey("nhl:game-one", "playlist-two|20"))
    }

    @Test fun `another game or unlinked channel never restores a prior hidden companion`() {
        val first = phonePlayerCompanionKey("nhl:game-one", "playlist|10")
        assertNotEquals(first, phonePlayerCompanionKey("nhl:game-two", "playlist|10"))
        assertNotEquals(first, phonePlayerCompanionKey(null, "playlist|10"))
        assertNotEquals(phonePlayerCompanionKey(null, "playlist|10"), phonePlayerCompanionKey(null, "playlist|20"))
    }

    @Test fun `companion state bundle keys never serialize raw channel identities or provider URLs`() {
        val key = phonePlayerCompanionKey(null, "private-playlist|https://example.invalid/live?token=private")
        assertTrue(key.matches(Regex("companion:[a-f0-9]{64}")))
        assertFalse(key.contains("private"))
        assertFalse(key.contains("https"))
    }

    @Test fun `Back only rotates a small fullscreen landscape phone`() {
        assertTrue(phonePlayerBackRotates(true, false, 412))
        assertFalse(phonePlayerBackRotates(false, false, 412))
        assertFalse(phonePlayerBackRotates(true, true, 412))
    }

    @Test fun `Back exits instead of waiting for ignored large screen orientation`() {
        assertFalse(phonePlayerBackRotates(true, false, 600))
        assertFalse(phonePlayerBackRotates(true, false, 840))
        assertFalse(phonePlayerBackRotates(true, false, 0))
    }

    @Test fun `portrait retains classic video above companion`() {
        val p = phonePlayerPanePlan(412, 800, landscape = false, pip = false, attachedMatch = true, gamePanel = false)
        assertEquals(PhonePaneMode.Rows, p.panes.mode)
        assertEquals(PhonePaneRect(0, 0, 412, 344), p.panes.primary)
        assertEquals(PhonePaneRect(0, 344, 412, 800), p.panes.secondary)
        assertFalse(p.automaticCompanion)
    }

    @Test fun `classic landscape stays full video until explicit game action`() {
        val closed = phonePlayerPanePlan(900, 400, true, false, false, false)
        assertNull(closed.panes.secondary)
        val open = phonePlayerPanePlan(900, 400, true, false, false, true)
        assertEquals(600, open.panes.primary.width)
        assertEquals(300, open.panes.secondary!!.width)
        assertFalse(open.automaticCompanion)
    }

    @Test fun `expanded attached match automatically uses side by side`() {
        val p = phonePlayerPanePlan(1000, 800, true, false, true, false)
        assertEquals(PhonePaneMode.Columns, p.panes.mode)
        assertTrue(p.automaticCompanion)
    }

    @Test fun `no guessed match never forces a wide game companion`() {
        val p = phonePlayerPanePlan(1000, 800, true, false, false, false)
        assertEquals(PhonePaneMode.Single, p.panes.mode)
        assertFalse(p.automaticCompanion)
    }

    @Test fun `PiP never includes a companion even when game panel was open`() {
        val p = phonePlayerPanePlan(1000, 800, true, true, true, true)
        assertEquals(PhonePanePlan(PhonePaneRect(0, 0, 1000, 800)), p.panes)
    }

    @Test fun `tabletop reserves actual horizontal hinge above game companion`() {
        val fold = PhoneFold(PhonePaneRect(0, 360, 1000, 400), PhoneFoldAxis.Horizontal, true, true)
        val p = phonePlayerPanePlan(1000, 800, true, false, true, false, folds = listOf(fold))
        assertEquals(PhonePaneMode.Rows, p.panes.mode)
        assertEquals(PhonePaneRect(0, 0, 1000, 360), p.panes.primary)
        assertEquals(PhonePaneRect(0, 400, 1000, 800), p.panes.secondary)
        assertTrue(p.automaticCompanion)
    }

    @Test fun `book uses off centre reported hinge and window content translation`() {
        val fold = PhoneFold(PhonePaneRect(430, 0, 470, 1000), PhoneFoldAxis.Vertical, true, true)
        val p = phonePlayerPanePlan(1000, 800, true, false, true, false, originX = 30, originY = 40, folds = listOf(fold))
        assertEquals(400, p.panes.primary.right)
        assertEquals(440, p.panes.secondary!!.left)
    }

    @Test fun `larger text suppresses unusable automatic columns`() {
        val p = phonePlayerPanePlan(800, 1000, false, false, true, false, minPaneWidth = 480)
        assertEquals(PhonePaneMode.Rows, p.panes.mode)
        assertFalse(p.automaticCompanion)
    }

    @Test fun `classic RTL keeps large video area and puts small panel at leading edge`() {
        val p = phonePlayerPanePlan(900, 400, true, false, false, true, rtl = true)
        assertEquals(PhonePaneRect(300, 0, 900, 400), p.panes.primary)
        assertEquals(PhonePaneRect(0, 0, 300, 400), p.panes.secondary)
    }

    @Test fun `too narrow explicit game action stays reachable in stacked region`() {
        val p = phonePlayerPanePlan(500, 350, true, false, false, true)
        assertEquals(PhonePaneMode.Rows, p.panes.mode)
        assertNotNull(p.panes.secondary)
        assertTrue(p.panes.primary.height > 0)
        assertTrue(p.panes.secondary!!.height > 0)
    }
}
