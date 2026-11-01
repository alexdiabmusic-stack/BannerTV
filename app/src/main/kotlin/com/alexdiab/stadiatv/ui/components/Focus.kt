package com.alexdiab.stadiatv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/**
 * "Pivot" scrolling for D-pad navigation. The default behaviour scrolls only as far as needed to
 * reveal the focused item, which makes content lurch in uneven steps. A pivot keeps the focused item
 * at a fixed anchor so rows glide one card at a time, and pages bring the focused shelf to a steady
 * position under the top bar.
 *
 * @param startOffset distance from the start edge at which the focused item is anchored.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(startOffset: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(startOffset, density) {
        val pivot = with(density) { startOffset.toPx() }
        object : BringIntoViewSpec {
            @Suppress("OVERRIDE_DEPRECATION") // still the hook that sets the scroll animation
            override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 300, easing = FocusEasing)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // Keep fully-visible, already-comfortable items still; otherwise pin to the pivot.
                val itemEnd = offset + size
                val fits = offset >= pivot - 1f && itemEnd <= containerSize
                return if (fits && offset <= pivot + 1f) 0f else offset - pivot
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Vertical variant: anchors at a fraction of the container so the next shelf peeks below. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PagePivot(anchorFraction: Float = 0.3f, content: @Composable () -> Unit) {
    val spec = remember(anchorFraction) {
        object : BringIntoViewSpec {
            @Suppress("OVERRIDE_DEPRECATION") // still the hook that sets the scroll animation
            override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 360, easing = FocusEasing)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val pivot = containerSize * anchorFraction
                // Tall items (a hero) stay put while any part of them is already near the anchor.
                if (size > containerSize * 0.5f && offset >= 0f && offset < pivot) return 0f
                return offset - pivot
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

private val FocusEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

val ShelfSpacing = 20.dp
