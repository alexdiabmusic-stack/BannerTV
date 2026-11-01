@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable data class NativeFantasyPlayer(val id: String, val name: String, val team: String, val position: String, val eligible: Set<String>, val injury: String? = null, val image: String? = null, val priorPoints: Double? = null)
@Serializable data class NativeFantasyEntry(val id: String, val player: NativeFantasyPlayer, val acquired: Long)
@Serializable data class NativeFantasyTeam(val id: String, val name: String, val cpu: Boolean, val roster: List<NativeFantasyEntry> = emptyList())
@Serializable data class NativeFantasyPick(val team: String, val round: Int, val number: Int, val player: String? = null, val name: String? = null)
@Serializable data class NativeFantasyTransaction(val id: String, val team: String, val text: String, val time: Long)
@Serializable data class NativeFantasyLeague(val id: String, val name: String, val sport: String, val personal: Boolean, val slots: Map<String, Int>, val scoringType: String, val points: Map<String, Double>, val categories: Set<String>, val teams: List<NativeFantasyTeam>, val picks: List<NativeFantasyPick>, val lineups: Map<String, Map<String, String>> = emptyMap(), val transactions: List<NativeFantasyTransaction> = emptyList(), val created: Long) {
    val userTeam: NativeFantasyTeam get() = teams.first { !it.cpu }
    val nextPick: NativeFantasyPick? get() = picks.firstOrNull { it.player == null }
    val phase: String get() = if (nextPick != null) "Drafting" else "In season"
}
@Serializable data class NativeFantasyLibrary(val schema: Int = 1, val selected: String? = null, val leagues: List<NativeFantasyLeague> = emptyList())
data class NativeFantasyScore(val points: Double?, val categories: Map<String, Double>)

/** iOS local backend translated into immutable transactions: rejected edits cannot corrupt the saved roster. */
object NativeFantasyEngine {
    val sports = listOf("nfl", "nhl", "nba", "mlb")
    fun slots(sport: String): Map<String, Int> = when (sport) {
        "nfl" -> linkedMapOf("QB" to 1, "RB" to 2, "WR" to 2, "TE" to 1, "FLEX" to 1, "K" to 1, "DST" to 1, "BN" to 6, "IR" to 2)
        "nhl" -> linkedMapOf("C" to 2, "LW" to 2, "RW" to 2, "D" to 4, "G" to 2, "UTIL" to 1, "BN" to 5, "IR" to 2)
        "nba" -> linkedMapOf("PG" to 1, "SG" to 1, "SF" to 1, "PF" to 1, "C" to 1, "GARD" to 1, "F" to 1, "UTIL" to 2, "BN" to 5, "IR" to 2)
        "mlb" -> linkedMapOf("C" to 1, "1B" to 1, "2B" to 1, "3B" to 1, "SS" to 1, "OF" to 3, "UTIL" to 1, "SP" to 2, "RP" to 2, "P" to 2, "BN" to 5, "IL" to 2)
        else -> throw IllegalArgumentException("Unsupported fantasy sport")
    }
    /** iOS permits a custom hockey forward slot even though its standard roster starts with none. */
    fun configurableSlots(sport: String): Map<String, Int> = slots(sport) + if (sport == "nhl") mapOf("F" to 0) else emptyMap()
    fun points(sport: String): Map<String, Double> = when (sport) {
        "nfl" -> linkedMapOf("passingYards" to .04, "passingTouchdowns" to 4.0, "interceptions" to -2.0, "rushingYards" to .04, "rushingTouchdowns" to 6.0, "receptions" to .5, "receivingYards" to .04, "receivingTouchdowns" to 6.0, "fieldGoalsMade" to 3.0, "extraPointsMade" to 1.0, "defensiveSacks" to 1.0, "defensiveTakeaways" to 2.0)
        "nhl" -> linkedMapOf("goals" to 3.0, "assists" to 2.0, "plusMinus" to 0.0, "penaltyMinutes" to 0.0, "powerPlayPoints" to .5, "shortHandedPoints" to 1.0, "shotsOnGoal" to .4, "hits" to .2, "blockedShots" to .5, "goalieWins" to 4.0, "saves" to .2, "goalsAgainst" to -1.0, "shutouts" to 3.0, "overtimeLosses" to 1.0, "goalsAgainstAverage" to 0.0, "savePercentage" to 0.0)
        "nba" -> linkedMapOf("points" to 1.0, "rebounds" to 1.2, "assists" to 2.0, "steals" to 3.0, "blockedShots" to .5, "threePointersMade" to 1.0, "fieldGoalPercentage" to 0.0, "freeThrowPercentage" to 0.0, "turnovers" to -2.0)
        "mlb" -> linkedMapOf("runs" to 1.0, "homeRuns" to 4.0, "runsBattedIn" to 1.0, "stolenBases" to 2.0, "battingAverage" to 0.0, "onBasePercentage" to 0.0, "hitsAllowed" to 0.0, "totalBases" to 1.0, "pitcherWins" to 5.0, "savesPitching" to 5.0, "strikeouts" to 1.0, "earnedRunAverage" to 0.0, "walksHitsPerInningPitched" to 0.0, "inningsPitched" to 3.0, "qualityStarts" to 5.0)
        else -> throw IllegalArgumentException("Unsupported fantasy sport")
    }
    fun eligible(position: String, sport: String): Set<String> {
        val normalized = position.trim().uppercase()
        return when (sport) {
        "nfl" -> when (normalized) { "QB" -> setOf("QB"); "RB", "WR", "TE" -> setOf(normalized, "FLEX"); "K", "PK" -> setOf("K"); "DST", "D/ST" -> setOf("DST"); else -> setOf("BN") }
        "nhl" -> when (val role = when (normalized) { "L" -> "LW"; "R" -> "RW"; else -> normalized }) {
            "C", "LW", "RW" -> setOf(role, "F", "UTIL"); "F" -> setOf("F", "UTIL"); "D" -> setOf("D", "UTIL"); "G" -> setOf("G"); else -> setOf("BN") }
        "nba" -> normalized.split('-', '/').flatMap { role -> when (role.trim()) {
            "PG", "SG" -> setOf(role.trim(), "GARD", "UTIL"); "SF", "PF" -> setOf(role.trim(), "F", "UTIL")
            "G" -> setOf("GARD", "UTIL"); "F" -> setOf("F", "UTIL"); "C" -> setOf("C", "UTIL"); else -> emptySet()
        } }.toSet().ifEmpty { setOf("BN") }
        "mlb" -> when (normalized) { "C" -> setOf("C", "UTIL"); "1B", "2B", "3B", "SS" -> setOf(normalized, "UTIL"); "OF", "LF", "CF", "RF" -> setOf("OF", "UTIL"); "DH", "UTIL" -> setOf("UTIL"); "SP", "RP" -> setOf(normalized, "P"); "P" -> setOf("P"); else -> setOf("BN") }
        else -> emptySet()
        }
    }
    fun create(name: String, team: String, sport: String, personal: Boolean, teamCount: Int = 8, roster: Map<String, Int> = slots(sport), scoringType: String = "Points", rules: Map<String, Double> = points(sport), categories: Set<String> = rules.keys, now: Long = nowMillis()): NativeFantasyLeague {
        require(name.trim().isNotEmpty() && team.trim().isNotEmpty()) { "Enter a league and team name." }
        require(sport in sports && scoringType in setOf("Points", "Categories", "Rotisserie"))
        require(roster.keys.all { it in configurableSlots(sport) } && roster.values.all { it in 0..20 } && roster.filterKeys { it !in setOf("IR", "IL") }.values.sum() in 1..40) { "Roster must contain 1–40 draft slots." }
        require(rules.keys.all { it in points(sport) } && rules.values.all { it.isFinite() && it in -1000.0..1000.0 }) { "Scoring rules must contain finite values." }
        val cpuNames = listOf("Ice Breakers", "Goal Line", "Fast Break", "Diamond Club", "Power Play", "Red Zone", "Baseline", "Bullpen", "North Stars", "City Skaters", "Sunday Squad", "Late Shift")
        val teams = listOf(NativeFantasyTeam(id(), team.trim().take(80), false)) + if (personal) emptyList() else (0 until (teamCount.coerceIn(2, 12) - 1)).map { NativeFantasyTeam(id(), cpuNames[it % cpuNames.size], true) }
        val rounds = roster.filterKeys { it !in setOf("IR", "IL") }.values.sum()
        val picks = (1..rounds).flatMap { round -> (if (round % 2 == 0) teams.asReversed() else teams).mapIndexed { index, t -> NativeFantasyPick(t.id, round, (round - 1) * teams.size + index + 1) } }
        return NativeFantasyLeague(id(), name.trim().take(100), sport, personal, roster, scoringType, rules, categories.intersect(rules.keys), teams, picks, created = now)
    }
    fun draft(league: NativeFantasyLeague, player: NativeFantasyPlayer, pool: List<NativeFantasyPlayer>, now: Long = nowMillis()): NativeFantasyLeague {
        val pick = league.nextPick ?: throw IllegalArgumentException("The draft is complete. Use free agency to add players.")
        require(pick.team == league.userTeam.id) { "It is not your pick." }
        var next = applyPick(league, pick, player, now)
        while (next.nextPick != null && next.teams.first { it.id == next.nextPick!!.team }.cpu) {
            val cpuPick = next.nextPick!!; val roster = next.teams.first { it.id == cpuPick.team }.roster
            val drafted = next.teams.flatMap { it.roster }.map { it.player.id }.toSet()
            val eligible = pool.filter { it.id !in drafted }
            val chosen = eligible.maxByOrNull { candidate -> cpuScore(candidate, roster, next.slots) } ?: break
            next = applyPick(next, cpuPick, chosen, now)
        }
        return next
    }
    private fun applyPick(league: NativeFantasyLeague, pick: NativeFantasyPick, player: NativeFantasyPlayer, now: Long): NativeFantasyLeague {
        require(league.teams.flatMap { it.roster }.none { it.player.id == player.id }) { "That player is already rostered." }
        return league.copy(teams = league.teams.map { if (it.id == pick.team) it.copy(roster = it.roster + NativeFantasyEntry(id(), player, now)) else it }, picks = league.picks.map { if (it.number == pick.number) it.copy(player = player.id, name = player.name) else it }, transactions = transaction(league, pick.team, "Drafted ${player.name}", now))
    }
    private fun cpuScore(player: NativeFantasyPlayer, roster: List<NativeFantasyEntry>, slots: Map<String, Int>): Double {
        val filled = roster.flatMap { it.player.eligible }.groupingBy { it }.eachCount()
        val need = player.eligible.maxOfOrNull { slot -> val count = slots[slot] ?: 0; if (count <= 0) 0.0 else (count - (filled[slot] ?: 0)).coerceAtLeast(0).toDouble() / count } ?: 0.0
        return need + (if (player.eligible.any { it in setOf("G", "QB", "SP") }) .15 else 0.0) - (if (player.injury != null) .35 else 0.0) + player.id.sumOf { it.code }.mod(100) / 10000.0
    }
    fun add(league: NativeFantasyLeague, player: NativeFantasyPlayer, drop: String? = null, now: Long = nowMillis()): NativeFantasyLeague {
        require(league.nextPick == null) { "Complete the draft before adding free agents." }
        require(league.teams.flatMap { it.roster }.none { it.player.id == player.id }) { "That player is already rostered." }
        val current = league.userTeam
        require(drop == null || current.roster.any { it.id == drop }) { "The dropped player is not on your roster." }
        val roster = current.roster.filterNot { it.id == drop } + NativeFantasyEntry(id(), player, now)
        require(roster.size <= league.slots.values.sum()) { "Roster full. Choose a player to drop first." }
        return league.copy(teams = league.teams.map { if (it.id == current.id) it.copy(roster = roster) else it }, lineups = league.lineups.mapValues { (_, values) -> values.filterKeys { it != drop } }, transactions = transaction(league, current.id, "Added ${player.name}" + if (drop != null) " · dropped ${current.roster.first { it.id == drop }.player.name}" else "", now))
    }
    fun drop(league: NativeFantasyLeague, entryId: String, now: Long = nowMillis()): NativeFantasyLeague {
        val team = league.userTeam; val entry = team.roster.firstOrNull { it.id == entryId } ?: throw IllegalArgumentException("Player not on your roster.")
        return league.copy(teams = league.teams.map { if (it.id == team.id) it.copy(roster = it.roster.filterNot { e -> e.id == entryId }) else it }, lineups = league.lineups.mapValues { (_, values) -> values - entryId }, transactions = transaction(league, team.id, "Dropped ${entry.player.name}", now))
    }
    fun move(league: NativeFantasyLeague, day: String, entryId: String, slot: String, locked: Set<String> = emptySet()): NativeFantasyLeague {
        val entry = league.userTeam.roster.firstOrNull { it.id == entryId } ?: throw IllegalArgumentException("Player not on your roster.")
        require(entry.player.id !in locked) { "This player's game has started. Their lineup position is locked." }
        require((league.slots[slot] ?: 0) > 0) { "Slot is not in this roster configuration." }
        require(slot == "BN" || (slot in setOf("IR", "IL") && entry.player.injury != null) || slot in entry.player.eligible) { "${entry.player.name} is not eligible for $slot." }
        val lineup = league.lineups[day].orEmpty() + (entryId to slot)
        require(lineup.values.count { it == slot } <= league.slots.getValue(slot)) { "Too many players in $slot. Move another player first." }
        return league.copy(lineups = (league.lineups + (day to lineup)).entries.toList().takeLast(400).associate { it.toPair() })
    }
    fun score(stats: Map<String, Double>?, rules: Map<String, Double>, type: String, categories: Set<String>): NativeFantasyScore {
        // No game feed is different from a played game with zero points.
        if (stats == null) return NativeFantasyScore(null, emptyMap())
        return if (type == "Points") NativeFantasyScore(rules.entries.sumOf { (stat, weight) -> (stats[stat] ?: 0.0) * weight }, emptyMap()) else NativeFantasyScore(null, categories.associateWith { stats[it] ?: 0.0 })
    }
    fun categoryWinner(home: Double, away: Double, stat: String): Int = if (home == away) 0 else if ((home > away) != (stat in setOf("goalsAgainstAverage", "goalsAgainst", "turnovers", "earnedRunAverage", "walksHitsPerInningPitched"))) 1 else -1
    private fun transaction(league: NativeFantasyLeague, team: String, text: String, now: Long) = (listOf(NativeFantasyTransaction(id(), team, text, now)) + league.transactions).take(1000)
    private fun id() = Uuid.random().toString()
}
