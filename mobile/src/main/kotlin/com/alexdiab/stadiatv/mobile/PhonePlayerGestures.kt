package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.alexdiab.stadiatv.playback.PlayerGesturePolicy
import kotlin.math.abs

/** A single owner arbitrates pinch and drag; the separate tap detector is cancelled only after actual movement. */
fun Modifier.phoneVideoGestures(
    identity: String, landscape: Boolean, locked: Boolean,
    onAdjustment: (Boolean, Float) -> Unit,
    onMinimize: () -> Unit, onFullscreen: () -> Unit, onPinch: (Float) -> Unit,
    onInteraction: (Boolean) -> Unit,
): Modifier = pointerInput(identity, landscape, locked) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (locked) return@awaitEachGesture
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var last = down.position
        var totalX = 0f; var totalY = 0f; var zoom = 1f
        var multi = false; var dragging = false
        var interacted = false
        try {
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1) {
                    multi = true
                    zoom *= event.calculateZoom()
                    if (abs(zoom - 1f) > .03f) { event.changes.forEach { it.consume() }; interacted = true; onInteraction(true) }
                } else if (!multi) {
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change != null) {
                        val delta = change.position - last; last = change.position
                        totalX += delta.x; totalY += delta.y
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!dragging && abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX) * 1.2f) dragging = true
                        if (dragging) {
                            change.consume(); interacted = true; onInteraction(true)
                            if (landscape) onAdjustment(down.position.x < size.width / 2, delta.y / size.height.coerceAtLeast(1))
                        }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (multi) onPinch(zoom)
            else if (!landscape && dragging) when (PlayerGesturePolicy.portrait(down.position.y / size.height.coerceAtLeast(1), totalX / density, totalY / density, tracker.calculateVelocity().y / density)) {
                PlayerGesturePolicy.Action.Minimize -> onMinimize()
                PlayerGesturePolicy.Action.Fullscreen -> onFullscreen()
                null -> Unit
            }
        } finally { if (interacted) onInteraction(false) }
    }
}
