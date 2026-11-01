package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.domain.*

/** Incoming exact-identity updates are authoritative, but a stale schedule cannot roll a final/live game backward.
 * Cross-provider duplicates require a UNIQUE same-opponents/time counterpart in both directions. */
object MatchFeedMerger {
    fun merge(matches: List<Match>, now: Long): List<Match> {
        val exact = linkedMapOf<String, Match>()
        matches.forEach { incoming -> exact[incoming.key] = exact[incoming.key]?.let { previous -> accept(previous, incoming) } ?: incoming }
        val groups = exact.values.groupBy(::signature)
        val accepted = mutableListOf<Match>()
        for ((signature, values) in groups) {
            if (signature == null) { accepted += values; continue }
            val consumed = HashSet<String>()
            for (game in values) {
                if (game.key in consumed) continue
                fun counterparts(one: Match) = values.filter { other -> other.key != one.key && provider(other) != provider(one) &&
                    kotlin.math.abs(other.startMillis - one.startMillis) <= 15 * 60_000 }
                val counterpart = counterparts(game).singleOrNull()?.takeIf { counterparts(it).singleOrNull()?.key == game.key }
                if (counterpart == null) accepted += game
                else {
                    consumed += counterpart.key
                    // Never transplant ESPN IDs/teams into a native reference, or vice versa.
                    accepted += listOf(game, counterpart).maxWith(compareBy<Match> { it.state.ordinal }.thenBy { it.nativeReference != null }.thenBy { it.hasDisplayScore })
                }
                consumed += game.key
            }
        }
        return accepted.filterNot { it.state == GameState.FINAL && it.startMillis < now - 2L * 24 * 3_600_000 }.sortedBy { it.startMillis }
    }
    private fun accept(previous: Match, incoming: Match): Match {
        if (previous.state == GameState.FINAL && incoming.state != GameState.FINAL) return previous
        if (previous.state == GameState.LIVE && incoming.state == GameState.PRE && incoming.statusDetail.lowercase() !in setOf("postponed", "cancelled", "canceled")) return previous
        fun side(old: TeamSide, fresh: TeamSide, opponentScore: String?): TeamSide {
            if (!TeamIdentity.sameAbbreviation(incoming.league.path, old.abbreviation, fresh.abbreviation)) return fresh
            val score = if (incoming.state == GameState.PRE) null else fresh.score ?: old.score.takeIf { incoming.state == previous.state }
            return fresh.copy(logoUrl = fresh.logoUrl ?: old.logoUrl, record = fresh.record ?: old.record, teamId = fresh.teamId ?: old.teamId, score = score,
                isWinner = incoming.state == GameState.FINAL && score?.toIntOrNull()?.let { n -> opponentScore?.toIntOrNull()?.let { n > it } } == true)
        }
        return incoming.copy(away = side(previous.away, incoming.away, incoming.home.score ?: previous.home.score.takeIf { incoming.state == previous.state }),
            home = side(previous.home, incoming.home, incoming.away.score ?: previous.away.score.takeIf { incoming.state == previous.state }),
            broadcasts = incoming.broadcasts.ifEmpty { previous.broadcasts }, venue = incoming.venue ?: previous.venue)
    }
    private fun provider(match: Match) = match.nativeReference?.provider ?: "ESPN"
    private fun signature(match: Match): String? {
        if (match.league.group in setOf(SportGroup.Racing, SportGroup.Golf, SportGroup.Tennis)) return null
        val away = TeamIdentity.abbreviation(match.league.path, match.away.abbreviation)
        val home = TeamIdentity.abbreviation(match.league.path, match.home.abbreviation)
        if (away.isBlank() || home.isBlank() || away == home || away in setOf("TBD", "TBA") || home in setOf("TBD", "TBA")) return null
        return "${match.league.path}:$away:$home"
    }
}
