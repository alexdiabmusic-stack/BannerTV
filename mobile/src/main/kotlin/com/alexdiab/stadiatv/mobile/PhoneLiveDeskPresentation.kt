package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match

/** Keep the featured fixture still while scores refresh; only a more relevant tier may replace it. */
internal fun phoneDeskFeaturedMatch(
    matches: List<Match>,
    now: Long,
    previousKey: String?,
    isFavorite: (Match) -> Boolean,
    inScope: (Match) -> Boolean,
): Match? {
    fun tier(match: Match): Int = when {
        match.state == GameState.LIVE && isFavorite(match) -> 0
        match.state == GameState.LIVE && inScope(match) -> 1
        match.state == GameState.LIVE -> 2
        isFavorite(match) -> 3
        inScope(match) -> 4
        else -> 5
    }
    val candidates = matches.distinctBy { it.key }.filter {
        it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMillis >= now)
    }
    val bestTier = candidates.minOfOrNull(::tier) ?: return null
    val relevant = candidates.filter { tier(it) == bestTier }
    val earliest = relevant.minWithOrNull(compareBy<Match> { it.startMillis }.thenBy { it.key })
    return relevant.firstOrNull { it.key == previousKey && (it.state == GameState.LIVE || it.startMillis == earliest?.startMillis) }
        ?: earliest
}

internal fun phoneDeskScope(matches: List<Match>, sport: String, inScope: (Match) -> Boolean): List<Match> =
    matches.distinctBy { it.key }.filter {
        when (sport) {
            "For you" -> inScope(it)
            "All sports" -> true
            else -> it.league.group.label == sport
        }
    }

/** Live rows prioritize followed teams, but never reorder on a score change. */
internal fun phoneDeskLive(matches: List<Match>, featuredKey: String?, isFavorite: (Match) -> Boolean): List<Match> =
    matches.filter { it.state == GameState.LIVE && it.key != featuredKey }
        .sortedWith(compareBy<Match> { !isFavorite(it) }.thenBy { it.startMillis }.thenBy { it.key })

/** Include earlier finals and in-progress games in a real date-ordered schedule, not only future starts. */
internal fun phoneDeskSchedule(matches: List<Match>, selection: String, now: Long): List<Match> {
    val range = scheduleRange(selection, now)
    return matches.filter { it.startMillis in range }
        .sortedWith(compareBy<Match> { it.startMillis }.thenBy { it.key })
}

/** A provider's exceptional phase must not become an invented kickoff countdown. */
internal fun phoneDeskUpcomingStatus(match: Match, now: Long): String {
    val exceptional = Regex("postpon|cancel|suspend|delay|\\btbd\\b|to be (determined|announced)|time.*(tba|tbd)", RegexOption.IGNORE_CASE)
        .containsMatchIn(match.statusDetail)
    return if (exceptional) "${dateLabel(match.startMillis)} · ${match.statusDetail}"
    else "${dateLabel(match.startMillis)} · ${clockTime(match.startMillis)} · ${countdownLabel(match.startMillis, now)}"
}
