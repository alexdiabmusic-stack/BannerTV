package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import java.util.Locale

data class BaseballBattingLeader(val player: Athlete, val team: String, val line: String)

fun baseballContactLine(play: BaseballAtBat): String? = play.events.lastOrNull { it.hitDistance != null || it.exitVelocity != null }?.let { hit ->
    listOfNotNull(hit.hitDistance?.let { "${String.format(Locale.getDefault(), "%.0f", it)} ft" },
        hit.exitVelocity?.let { "${String.format(Locale.getDefault(), "%.1f", it)} mph exit velocity" }).joinToString(" · ").takeIf(String::isNotBlank)
}

fun baseballMatchupLine(player: SportPlayer, boxes: List<TeamBox>, pitching: Boolean): String? = boxes.asSequence()
    .flatMap { it.groups.asSequence() }.filter { it.title.equals(if (pitching) "Pitching" else "Batting", true) }
    .mapNotNull { group -> group.rows.firstOrNull { it.athleteId == "mlb:${player.id}" }?.let { group.labels.zip(it.stats).toMap() } }
    .firstOrNull()?.let { values -> (if (pitching) listOf("IP", "H", "ER", "SO", "P") else listOf("H", "AB", "HR", "RBI"))
        .mapNotNull { key -> values[key]?.takeIf { it.isNotBlank() && it != "–" }?.let { "$it ${if (key == "P") "pitches" else key}" } }.joinToString(" · ").takeIf(String::isNotBlank) }

/** iOS MLBGameLeadersView: only hitters with a reported hit; RBI then hits, not invented fantasy points. */
fun baseballBattingLeaders(boxes: List<TeamBox>): List<BaseballBattingLeader> = boxes.flatMap { team ->
    team.groups.filter { it.title.equals("Batting", true) }.flatMap { group -> group.rows.mapNotNull { row ->
        val stats = group.labels.zip(row.stats).toMap()
        val hits = stats["H"]?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val id = row.athleteId?.takeIf { it.startsWith("mlb:") } ?: return@mapNotNull null
        Triple(stats["RBI"]?.toIntOrNull() ?: -1, hits, BaseballBattingLeader(
            Athlete(id, row.name, position = row.detail, headshotUrl = row.headshot), team.abbreviation,
            listOf("H", "RBI", "HR").mapNotNull { key -> stats[key]?.takeIf { it != "–" && it.isNotBlank() }?.let { "$it $key" } }.joinToString(" · ")))
    } }
}.sortedWith(compareByDescending<Triple<Int, Int, BaseballBattingLeader>> { it.first }.thenByDescending { it.second })
    .distinctBy { it.third.player.id }.take(3).map { it.third }

/** Corrections/removals update visible rows immediately; new identities wait while reading history. */
data class BaseballTimelineWindow(val initialized: Boolean = false, val displayed: List<BaseballAtBat> = emptyList(), val deferredIds: Set<String> = emptySet()) {
    fun update(incoming: List<BaseballAtBat>, atLiveEdge: Boolean): BaseballTimelineWindow {
        if (!initialized || atLiveEdge) return BaseballTimelineWindow(true, incoming)
        val visible = displayed.map { it.id }.toSet()
        return BaseballTimelineWindow(true, incoming.filter { it.id in visible }, incoming.map { it.id }.toSet() - visible)
    }
}
