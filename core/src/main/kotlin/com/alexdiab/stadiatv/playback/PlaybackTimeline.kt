package com.alexdiab.stadiatv.playback

/** Positions are relative to the current provider window, never to the XMLTV programme length. */
data class PlaybackTimeline(
    val position: Long = 0,
    val duration: Long = 0,
    val seekable: Boolean = false,
    val live: Boolean = false,
    val liveOffset: Long? = null,
    val windowStart: Long? = null,
    val ended: Boolean = false,
    val recommendedPosition: Long? = null,
) {
    fun shouldResetLiveOnResume(pausedMillis: Long): Boolean = live && !seekable && pausedMillis > 30_000
    val behindLive: Boolean get() = live && (recommendedPosition?.let { it - position > 3_000 } ?: (liveOffset?.let { it > 15_000 } ?: false))
    fun target(requested: Long): Long? = if (seekable && duration > 0) requested.coerceIn(0, duration) else null
    fun relativeTarget(delta: Long): Long? = target(if (delta > 0 && position > Long.MAX_VALUE - delta) Long.MAX_VALUE else (position + delta).coerceAtLeast(0))
    /** Anchor a drag to media time so a moving live window cannot reinterpret an old slider position. */
    fun targetFrom(snapshot: PlaybackTimeline, requested: Long): Long? {
        val shifted = if (live && snapshot.live && snapshot.windowStart != null && windowStart != null) requested + snapshot.windowStart - windowStart else requested
        return target(shifted)
    }
}

object PlayerQuickActions {
    val known = listOf("Guide", "Channels", "Recent", "PiP")
    val defaults = known.take(3)
    fun normalize(values: List<String>): List<String> = values.filter { it in known }.distinct().take(3).ifEmpty { defaults }
    fun move(values: List<String>, action: String, delta: Int): List<String> {
        val list = normalize(values).toMutableList(); val index = list.indexOf(action)
        if (index >= 0) list.add((index + delta).coerceIn(0, list.lastIndex), list.removeAt(index))
        return list
    }
}
