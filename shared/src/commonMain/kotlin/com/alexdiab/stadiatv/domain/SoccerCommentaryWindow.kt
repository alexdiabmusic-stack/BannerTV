package com.alexdiab.stadiatv.domain

import androidx.compose.runtime.Immutable

/** New leading messages wait while reading history; corrections/removals and older pages still apply. */
@Immutable
data class SoccerCommentaryWindow(
    val initialized: Boolean = false,
    val displayed: List<SoccerComment> = emptyList(),
    val deferredIds: Set<String> = emptySet(),
) {
    fun update(incoming: List<SoccerComment>, atLatest: Boolean): SoccerCommentaryWindow {
        if (!initialized || atLatest) return SoccerCommentaryWindow(true, incoming)
        val visible = displayed.mapTo(HashSet()) { it.id }
        val firstVisible = incoming.indexOfFirst { it.id in visible }
        val leading = incoming.take(if (firstVisible < 0) incoming.size else firstVisible).map { it.id }
        val present = incoming.mapTo(HashSet()) { it.id }
        val pending = (deferredIds + leading).intersect(present)
        return SoccerCommentaryWindow(true, incoming.filterNot { it.id in pending }, pending)
    }
}
