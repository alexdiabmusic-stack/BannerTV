package com.alexdiab.stadiatv.playback

import com.alexdiab.stadiatv.domain.Match

/**
 * Holds recent score snapshots so a score can be shown `delaySeconds` late, matching a stream that runs behind
 * the sports feed. With no delay it simply returns the newest snapshot.
 */
class ScoreBuffer {
    private class Snapshot(val at: Long, val match: Match?)

    private val snapshots = ArrayList<Snapshot>()

    fun receive(match: Match?, now: Long = System.currentTimeMillis()) {
        snapshots += Snapshot(now, match)
        if (snapshots.size > MAX_SNAPSHOTS) snapshots.removeAt(0)
    }

    fun clear() = snapshots.clear()

    fun snapshot(delaySeconds: Int, now: Long = System.currentTimeMillis()): Match? {
        if (snapshots.isEmpty()) return null
        if (delaySeconds <= 0) return snapshots.last().match
        val cutoff = now - delaySeconds * 1000L
        return snapshots.lastOrNull { it.at <= cutoff }?.match
    }

    private companion object { const val MAX_SNAPSHOTS = 400 }
}
