package com.alexdiab.stadiatv.mobile

/** Pixel geometry, independent of Android/Compose so actual hinge positions are regression-testable. */
internal data class PhonePaneRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = (right - left).coerceAtLeast(0)
    val height get() = (bottom - top).coerceAtLeast(0)
    val area get() = width.toLong() * height
}

internal enum class PhoneFoldAxis { Vertical, Horizontal }
internal data class PhoneFold(val bounds: PhonePaneRect, val axis: PhoneFoldAxis, val separating: Boolean, val occluding: Boolean)
internal enum class PhonePaneMode { Single, Columns, Rows }
internal data class PhonePanePlan(val primary: PhonePaneRect, val secondary: PhonePaneRect? = null, val mode: PhonePaneMode = PhonePaneMode.Single)

internal data class PhonePlayerPanePlan(val panes: PhonePanePlan, val automaticCompanion: Boolean = false)

/** Never make Back depend on an orientation request the system is allowed to ignore. */
internal fun phonePlayerBackRotates(landscape: Boolean, multiWindow: Boolean, smallestWidthDp: Int): Boolean =
    landscape && !multiWindow && smallestWidthDp in 1..599

/** Retain a game's companion across stream copies, never restore another game's hidden tab state. */
internal fun phonePlayerCompanionKey(gameKey: String?, channelKey: String): String {
    val identity = gameKey?.let { "game:$it" } ?: "channel:$channelKey"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return "companion:" + buildString(64) {
        digest.forEach { byte -> val n = byte.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) }
    }
}

/** Same single video in classic portrait, classic landscape, book and tabletop arrangements. */
internal fun phonePlayerPanePlan(
    width: Int, height: Int, landscape: Boolean, pip: Boolean, attachedMatch: Boolean, gamePanel: Boolean,
    originX: Int = 0, originY: Int = 0, folds: List<PhoneFold> = emptyList(), expandedWidth: Int = 700,
    minPaneWidth: Int = 320, minPaneHeight: Int = 180, gap: Int = 24,
    portraitMin: Int = 300, portraitMax: Int = 420, landscapePanelWidth: Int = 300, rtl: Boolean = false,
): PhonePlayerPanePlan {
    val safe = phonePanePlan(width, height, originX, originY, folds,
        hasSecondary = !pip && attachedMatch, expandedWidth = if (attachedMatch) expandedWidth else Int.MAX_VALUE,
        minPaneWidth = minPaneWidth, minPaneHeight = minPaneHeight, gap = gap,
        allowRows = attachedMatch, rtl = rtl)
    if (pip) return PhonePlayerPanePlan(safe)
    if (safe.secondary != null) return PhonePlayerPanePlan(safe, automaticCompanion = true)
    val rect = safe.primary
    if (landscape && !gamePanel) return PhonePlayerPanePlan(safe)
    if (landscape) {
        // The explicitly opened classic panel remains available on smaller landscape phones.
        // If neither column is usable, stack within the safe region instead of hiding the action.
        val panelWidth = landscapePanelWidth.coerceAtMost(rect.width / 2)
        if (panelWidth >= minPaneWidth.coerceAtMost(landscapePanelWidth) && rect.height >= minPaneHeight) {
            val video = if (rtl) rect.copy(left = rect.left + panelWidth) else rect.copy(right = rect.right - panelWidth)
            val panel = if (rtl) rect.copy(right = rect.left + panelWidth) else rect.copy(left = rect.right - panelWidth)
            return PhonePlayerPanePlan(PhonePanePlan(video, panel, PhonePaneMode.Columns))
        }
    }
    val videoHeight = (rect.height * .43f).toInt().coerceIn(portraitMin.coerceAtLeast(0), portraitMax.coerceAtLeast(portraitMin.coerceAtLeast(0)))
        .coerceAtMost((rect.height * .65f).toInt()).coerceAtLeast(0)
    return PhonePlayerPanePlan(PhonePanePlan(rect.copy(bottom = rect.top + videoHeight),
        rect.copy(top = rect.top + videoHeight), PhonePaneMode.Rows))
}

/**
 * WindowManager bounds are in WINDOW coordinates, not this content's coordinates. Preserve real
 * off-centre hinges, including zero-width separating folds. Unsupported/multiple divisions fall
 * back to the largest unobstructed rectangle instead of spanning inaccessible pixels.
 */
internal fun phonePanePlan(
    width: Int, height: Int, originX: Int = 0, originY: Int = 0, folds: List<PhoneFold> = emptyList(),
    hasSecondary: Boolean = true, expandedWidth: Int = 700, minPaneWidth: Int = 320,
    minPaneHeight: Int = 180, gap: Int = 24, allowRows: Boolean = false, rtl: Boolean = false,
): PhonePanePlan {
    val full = PhonePaneRect(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
    if (full.area == 0L) return PhonePanePlan(full)
    val active = folds.asSequence().filter { it.separating || it.occluding }.map { fold ->
        fold.copy(bounds = PhonePaneRect(fold.bounds.left - originX, fold.bounds.top - originY,
            fold.bounds.right - originX, fold.bounds.bottom - originY))
    }.filter { f ->
        val b = f.bounds
        b.left <= b.right && b.top <= b.bottom && when (f.axis) {
            PhoneFoldAxis.Vertical -> b.left < width && b.right > 0 && b.top < height && b.bottom > 0
            PhoneFoldAxis.Horizontal -> b.top < height && b.bottom > 0 && b.left < width && b.right > 0
        }
    }.distinct().take(8).toList()
    fun split(rect: PhonePaneRect, fold: PhoneFold, spacing: Int): List<PhonePaneRect> {
        val b = fold.bounds
        if (b.left >= rect.right || b.right <= rect.left || b.top >= rect.bottom || b.bottom <= rect.top) return listOf(rect)
        return when (fold.axis) {
            PhoneFoldAxis.Vertical -> {
                val extra = (spacing - b.width).coerceAtLeast(0)
                listOf(rect.copy(right = (b.left - extra / 2).coerceIn(rect.left, rect.right)),
                    rect.copy(left = (b.right + extra - extra / 2).coerceIn(rect.left, rect.right)))
            }
            PhoneFoldAxis.Horizontal -> {
                val extra = (spacing - b.height).coerceAtLeast(0)
                listOf(rect.copy(bottom = (b.top - extra / 2).coerceIn(rect.top, rect.bottom)),
                    rect.copy(top = (b.bottom + extra - extra / 2).coerceIn(rect.top, rect.bottom)))
            }
        }.filter { it.area > 0 }
    }
    if (active.isNotEmpty()) {
        if (active.size == 1 && hasSecondary) {
            val fold = active.single()
            val panes = split(full, fold, gap.coerceAtLeast(0))
            if (panes.size == 2 && panes.all { it.width >= minPaneWidth && it.height >= minPaneHeight }) {
                if (fold.axis == PhoneFoldAxis.Vertical && width >= expandedWidth) {
                    return PhonePanePlan(panes[if (rtl) 1 else 0], panes[if (rtl) 0 else 1], PhonePaneMode.Columns)
                }
                if (fold.axis == PhoneFoldAxis.Horizontal && allowRows) return PhonePanePlan(panes[0], panes[1], PhonePaneMode.Rows)
            }
        }
        val regions = active.fold(listOf(full)) { regions, fold -> regions.flatMap { split(it, fold, 0) } }
        val largest = regions.sortedWith(compareByDescending<PhonePaneRect> { it.area }.thenBy { it.top }
            .thenBy { if (rtl) -it.left else it.left }).firstOrNull() ?: PhonePaneRect(0, 0, 0, 0)
        return PhonePanePlan(largest)
    }
    val spacing = gap.coerceAtLeast(0)
    val half = ((width - spacing) / 2).coerceAtLeast(0)
    if (hasSecondary && width >= expandedWidth && half >= minPaneWidth && height >= minPaneHeight) {
        val left = PhonePaneRect(0, 0, half, height)
        val right = PhonePaneRect(half + spacing, 0, width, height)
        return PhonePanePlan(if (rtl) right else left, if (rtl) left else right, PhonePaneMode.Columns)
    }
    return PhonePanePlan(full)
}
