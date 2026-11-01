package com.alexdiab.stadiatv.mobile

import org.junit.Assert.*
import org.junit.Test

class PhonePaneGeometryTest {
    private fun vertical(left: Int, right: Int = left, top: Int = 0, bottom: Int = 1200,
        separating: Boolean = true, occluding: Boolean = false) =
        PhoneFold(PhonePaneRect(left, top, right, bottom), PhoneFoldAxis.Vertical, separating, occluding)
    private fun horizontal(top: Int, bottom: Int = top) =
        PhoneFold(PhonePaneRect(0, top, 1200, bottom), PhoneFoldAxis.Horizontal, true, true)

    @Test fun `compact windows stack in the full content region`() {
        assertEquals(PhonePanePlan(PhonePaneRect(0, 0, 412, 800)), phonePanePlan(412, 800))
    }

    @Test fun `expanded threshold and readable pane widths must both pass`() {
        assertEquals(PhonePaneMode.Single, phonePanePlan(699, 800).mode)
        assertEquals(PhonePaneMode.Columns, phonePanePlan(700, 800).mode)
        assertEquals(PhonePaneMode.Single, phonePanePlan(900, 800, minPaneWidth = 480).mode)
        assertEquals(PhonePaneMode.Columns, phonePanePlan(1000, 800, minPaneWidth = 480).mode)
    }

    @Test fun `empty secondary leaves no blank second column`() {
        assertEquals(PhonePaneRect(0, 0, 1000, 800), phonePanePlan(1000, 800, hasSecondary = false).primary)
    }

    @Test fun `real off centre hinge determines the panes not half the width`() {
        val p = phonePanePlan(1000, 800, folds = listOf(vertical(400, 440)))
        assertEquals(PhonePaneMode.Columns, p.mode)
        assertEquals(PhonePaneRect(0, 0, 400, 800), p.primary)
        assertEquals(PhonePaneRect(440, 0, 1000, 800), p.secondary)
    }

    @Test fun `zero width separating folds still reserve the inter column gap`() {
        val p = phonePanePlan(1000, 800, folds = listOf(vertical(450)))
        assertEquals(PhonePaneRect(0, 0, 438, 800), p.primary)
        assertEquals(PhonePaneRect(462, 0, 1000, 800), p.secondary)
    }

    @Test fun `window bounds translate through app bar and content insets`() {
        val p = phonePanePlan(900, 700, originX = 100, originY = 140,
            folds = listOf(vertical(500, 540, top = 90, bottom = 1000)))
        assertEquals(PhonePaneRect(0, 0, 400, 700), p.primary)
        assertEquals(PhonePaneRect(440, 0, 900, 700), p.secondary)
    }

    @Test fun `nonseparating flat unoccluded fold is only a guideline`() {
        assertEquals(phonePanePlan(1000, 800), phonePanePlan(1000, 800,
            folds = listOf(vertical(100, 100, separating = false))))
    }

    @Test fun `occluded hinge must be avoided even if not flagged separating`() {
        val p = phonePanePlan(1000, 800, folds = listOf(vertical(450, 490, separating = false, occluding = true)))
        assertEquals(450, p.primary.right)
        assertEquals(490, p.secondary!!.left)
    }

    @Test fun `a fold outside this subtree does not change its layout`() {
        val ordinary = phonePanePlan(412, 700)
        assertEquals(ordinary, phonePanePlan(412, 700, originX = 600, folds = listOf(vertical(450, 490))))
        assertEquals(ordinary, phonePanePlan(412, 700, originY = 1300, folds = listOf(vertical(200))))
    }

    @Test fun `unusable second pane falls back to larger hinge free region`() {
        val p = phonePanePlan(1000, 800, folds = listOf(vertical(180, 220)))
        assertEquals(PhonePaneMode.Single, p.mode)
        assertEquals(PhonePaneRect(220, 0, 1000, 800), p.primary)
        assertNull(p.secondary)
    }

    @Test fun `single column pages also avoid a physical hinge`() {
        assertEquals(PhonePaneRect(440, 0, 1000, 800), phonePanePlan(1000, 800,
            hasSecondary = false, folds = listOf(vertical(400, 440))).primary)
    }

    @Test fun `tabletop chooses safe region unless caller explicitly supports rows`() {
        val fold = horizontal(330, 370)
        assertEquals(PhonePaneRect(0, 370, 1000, 800), phonePanePlan(1000, 800, folds = listOf(fold)).primary)
        val p = phonePanePlan(1000, 800, folds = listOf(fold), allowRows = true)
        assertEquals(PhonePaneMode.Rows, p.mode)
        assertEquals(PhonePaneRect(0, 0, 1000, 330), p.primary)
        assertEquals(PhonePaneRect(0, 370, 1000, 800), p.secondary)
    }

    @Test fun `multiple reported folds never put controls through either hinge`() {
        val p = phonePanePlan(1400, 800, folds = listOf(vertical(400, 430), vertical(950, 990)))
        assertEquals(PhonePaneMode.Single, p.mode)
        assertEquals(PhonePaneRect(430, 0, 950, 800), p.primary)
    }

    @Test fun `RTL changes reading order not physical hinge coordinates`() {
        val p = phonePanePlan(1000, 800, folds = listOf(vertical(400, 440)), rtl = true)
        assertEquals(PhonePaneRect(440, 0, 1000, 800), p.primary)
        assertEquals(PhonePaneRect(0, 0, 400, 800), p.secondary)
    }

    @Test fun `equal safe regions have deterministic reading direction tie break`() {
        val fold = vertical(480, 520)
        assertEquals(0, phonePanePlan(1000, 800, hasSecondary = false, folds = listOf(fold)).primary.left)
        assertEquals(520, phonePanePlan(1000, 800, hasSecondary = false, folds = listOf(fold), rtl = true).primary.left)
    }

    @Test fun `edge and malformed features cannot create negative layout bounds`() {
        assertEquals(PhonePaneRect(30, 0, 1000, 800), phonePanePlan(1000, 800, folds = listOf(vertical(-20, 30))).primary)
        assertEquals(phonePanePlan(1000, 800), phonePanePlan(1000, 800, folds = listOf(vertical(500, 400))))
        assertEquals(0, phonePanePlan(-1, 0).primary.width)
    }
}
