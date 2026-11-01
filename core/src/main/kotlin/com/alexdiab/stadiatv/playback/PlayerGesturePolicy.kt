package com.alexdiab.stadiatv.playback

import kotlin.math.abs

object PlayerGesturePolicy {
    enum class Action { Minimize, Fullscreen }
    /** Portrait only: don't confuse a horizontal swipe or a gesture starting below the video with dismissal. */
    fun portrait(startFraction: Float, dxDp: Float, dyDp: Float, velocityDp: Float = 0f): Action? {
        if (!listOf(startFraction, dxDp, dyDp, velocityDp).all { it.isFinite() } || abs(dyDp) <= abs(dxDp) * 1.5f) return null
        val predicted = dyDp + velocityDp * .2f
        return when {
            startFraction <= .46f && dyDp > 18 && (dyDp > 150 || predicted > 300) -> Action.Minimize
            startFraction <= .55f && dyDp < -80 && predicted < -160 -> Action.Fullscreen
            else -> null
        }
    }
    /** The Android crop mode is called Zoom; Fill stretches rather than preserving the video's aspect ratio. */
    fun pinch(mode: AspectMode, scale: Float): AspectMode? = when {
        !scale.isFinite() -> null
        scale > 1.15f && mode == AspectMode.Fit -> AspectMode.Zoom
        scale < .87f && mode != AspectMode.Fit -> AspectMode.Fit
        else -> null
    }
}
