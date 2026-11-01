package com.alexdiab.stadiatv.playback

import org.junit.Assert.*
import org.junit.Test

class PlayerInteractionTest {
    @Test fun seekingRequiresRealBoundedWindowAndClampsBothDirections() {
        assertNull(PlaybackTimeline(duration = 60_000).target(10_000))
        assertNull(PlaybackTimeline(seekable = true).target(10_000))
        val window = PlaybackTimeline(position = 20_000, duration = 60_000, seekable = true)
        assertEquals(0L, window.relativeTarget(-30_000)); assertEquals(60_000L, window.relativeTarget(100_000)); assertEquals(60_000L, window.relativeTarget(Long.MAX_VALUE))
        assertFalse(window.copy(live = true).shouldResetLiveOnResume(90_000))
        assertTrue(PlaybackTimeline(live = true).shouldResetLiveOnResume(30_001))
        assertFalse(PlaybackTimeline(live = false).shouldResetLiveOnResume(90_000))
        // A provider with a deliberate 30-second live delay is still live at its recommended position.
        assertFalse(window.copy(live = true, position = 30_000, liveOffset = 30_000, recommendedPosition = 30_000).behindLive)
        assertTrue(window.copy(live = true, position = 20_000, liveOffset = 40_000, recommendedPosition = 30_000).behindLive)
    }
    @Test fun movingLiveWindowKeepsScrubbedMediaTimeRatherThanOldSliderCoordinate() {
        val old = PlaybackTimeline(duration = 60_000, seekable = true, live = true, windowStart = 100_000)
        val current = old.copy(windowStart = 110_000)
        assertEquals(20_000L, current.targetFrom(old, 30_000))
        assertEquals(0L, current.targetFrom(old, 5_000))
        assertEquals(30_000L, current.copy(live = false).targetFrom(old, 30_000))
    }
    @Test fun actionsKeepOrderRejectUnknownAndDuplicatesAndHaveNonEmptyDefault() {
        assertEquals(listOf("PiP", "Guide", "Recent"), PlayerQuickActions.normalize(listOf("invalid", "PiP", "Guide", "PiP", "Recent", "Channels")))
        assertEquals(PlayerQuickActions.defaults, PlayerQuickActions.normalize(emptyList()))
        assertEquals(listOf("Channels", "Guide", "Recent"), PlayerQuickActions.move(PlayerQuickActions.defaults, "Channels", -1))
        assertEquals(PlayerQuickActions.defaults, PlayerQuickActions.move(PlayerQuickActions.defaults, "Guide", -1))
    }
    @Test fun portraitSwipesRespectDirectionStartAreaAndVelocityThresholds() {
        assertEquals(PlayerGesturePolicy.Action.Minimize, PlayerGesturePolicy.portrait(.2f, 0f, 160f))
        assertEquals(PlayerGesturePolicy.Action.Fullscreen, PlayerGesturePolicy.portrait(.2f, 0f, -100f, -500f))
        assertNull(PlayerGesturePolicy.portrait(.8f, 0f, 200f))
        assertNull(PlayerGesturePolicy.portrait(.2f, 190f, 200f))
        assertNull(PlayerGesturePolicy.portrait(.2f, 0f, 40f))
        assertNull(PlayerGesturePolicy.portrait(.2f, Float.NaN, 200f))
    }
    @Test fun pinchPreservesAspectAndDoesNotStretchVideo() {
        assertEquals(AspectMode.Zoom, PlayerGesturePolicy.pinch(AspectMode.Fit, 1.2f))
        assertEquals(AspectMode.Fit, PlayerGesturePolicy.pinch(AspectMode.Zoom, .8f))
        assertNull(PlayerGesturePolicy.pinch(AspectMode.Fit, 1.05f))
    }
}
