package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.*

/** Validated provider shapes; no event-derived scores, fabricated geometry, or guessed shootout outcomes. */
object NativeMlsDetails {
    private fun requireIdentity(info: JsonElement?, match: Match, seasonId: String) {
        if (!NativeMlsMapping.resourceIdentity(info, match, seasonId)) throw IOException("MLS resource game/season/competition mismatch")
    }
    fun events(raw: JsonElement, match: Match, seasonId: String, overview: JsonElement): List<Play> {
        requireIdentity(raw["match_info"], match, seasonId)
        val rows = raw["events"] as? JsonArray ?: throw IOException("MLS event format unavailable")
        if (rows.size > 2000 || NativeMlsClient.cursor(raw) != null) throw IOException("MLS event sequence exceeds bounds or is truncated")
        val teams = setOfNotNull(match.home.teamId, match.away.teamId)
        val memberships = listOf("home", "away").flatMap { side -> overview[side]["players"].array.mapNotNull { p ->
            NativeMlsMapping.qualified(p["person_id"].string(), "OBJ")?.let { it to NativeMlsMapping.qualified(overview[side]["team_id"].string(), "CLU") }
        } }.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct().singleOrNull() }
        val names = listOf("home", "away").flatMap { overview[it]["players"].array }.mapNotNull { NativeMlsMapping.person(it) }.associateBy { it.id }
        fun player(event: JsonElement?, prefix: String): SportPlayer? {
            val id = NativeMlsMapping.qualified(event["${prefix}_id"].string(), "OBJ") ?: return null
            return NativeMlsMapping.person(event, "${prefix}_id", "${prefix}_first_name", "${prefix}_last_name") ?: names[id] ?: SportPlayer(id, "Unknown player")
        }
        return rows.mapIndexed { index, wrapper ->
            val type = wrapper["type"].stringOrNull() ?: throw IOException("MLS event type missing")
            val event = wrapper["event"] as? JsonObject ?: throw IOException("MLS event payload missing")
            val id = event["event_id"].stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: throw IOException("MLS event identity missing")
            val primary = when (type) { "substitutions" -> player(event, "player_in"); "fouls" -> player(event, "fouler"); else -> player(event, "player") }
            val secondary = when (type) { "substitutions" -> player(event, "player_out"); "fouls" -> player(event, "fouled"); else -> player(event, "assist_player") }
            val secondAssist = player(event, "second_assist_player")
            val reportedTeam = event[if (type == "fouls") "team_fouler_id" else "team_id"].stringOrNull()?.takeIf(String::isNotBlank)
            val team = if (reportedTeam != null) NativeMlsMapping.qualified(reportedTeam, "CLU")?.takeIf { it in teams }
                ?: throw IOException("MLS event club mismatch") else primary?.id?.let { memberships[it] }
            val kind = when (type) {
                "shot_at_goals" -> when (event["shot_result"].string()) {
                    "SuccessfulShot" -> if (event["origin"].string().contains("penalt", true)) SoccerEventKind.PenaltyGoal else SoccerEventKind.Goal
                    "SavedShot" -> SoccerEventKind.ShotSaved; "BlockedShot" -> SoccerEventKind.ShotBlocked; "ShotWide" -> SoccerEventKind.ShotOffTarget
                    else -> SoccerEventKind.Unknown
                }
                "cards" -> when (event["card_color"].string().lowercase()) {
                    "yellow" -> SoccerEventKind.YellowCard; "red" -> SoccerEventKind.RedCard
                    "secondyellow", "yellowred", "secondyellowcard" -> SoccerEventKind.SecondYellow
                    else -> SoccerEventKind.Unknown
                }
                "substitutions" -> SoccerEventKind.Substitution; "corner_kicks" -> SoccerEventKind.Corner
                "offsides" -> SoccerEventKind.Offside; "fouls" -> SoccerEventKind.Foul
                "kick_off" -> SoccerEventKind.PeriodStart; "final_whistle" -> SoccerEventKind.PeriodEnd
                else -> SoccerEventKind.Unknown
            }
            val label = when (kind) {
                SoccerEventKind.Goal -> "Goal"; SoccerEventKind.PenaltyGoal -> "Penalty goal"; SoccerEventKind.YellowCard -> "Yellow card"
                SoccerEventKind.RedCard -> "Red card"; SoccerEventKind.SecondYellow -> "Second yellow card"; SoccerEventKind.Substitution -> "Substitution"
                SoccerEventKind.ShotSaved -> "Shot saved"; SoccerEventKind.ShotBlocked -> "Shot blocked"; SoccerEventKind.ShotOffTarget -> "Shot off target"
                SoccerEventKind.Corner -> "Corner"; SoccerEventKind.Offside -> "Offside"; SoccerEventKind.Foul -> "Foul"
                SoccerEventKind.PeriodStart -> "Kick-off"; SoccerEventKind.PeriodEnd -> "Period ended"
                else -> listOf(type, event["shot_result"].string(), event["card_color"].string()).filter(String::isNotBlank).joinToString(": ")
            }
            val period = event["game_section"].string()
            val periodNumber = when (period) { "firstHalf" -> 1; "secondHalf" -> 2; "firstHalfExtra" -> 3; "secondHalfExtra" -> 4; "penalty" -> 5; else -> 0 }
            val minute = event["minute_of_play"].string(); val timestamp = Dates.parseIso(event["event_time"].stringOrNull())
            val description = when (kind) {
                SoccerEventKind.Substitution -> listOfNotNull(primary?.let { "On: ${it.name}" }, secondary?.let { "Off: ${it.name}" }).joinToString(" · ")
                SoccerEventKind.Foul -> listOfNotNull(primary?.name, secondary?.let { "Fouled: ${it.name}" }).joinToString(" · ")
                else -> listOfNotNull(primary?.name, secondary?.let { "Assist: ${it.name}" }, secondAssist?.let { "Second assist: ${it.name}" }).joinToString(" · ")
            }
            Play("mls:${match.nativeReference!!.gameId}|$id", period, NativeMlsMapping.clock(minute).orEmpty(), description.ifBlank { label }, team, null, null,
                kind in setOf(SoccerEventKind.Goal, SoccerEventKind.PenaltyGoal), label, periodNumber,
                sortOrder = timestamp?.toDouble() ?: (rows.size - index).toDouble(),
                soccerEvent = SoccerEvent(kind, primary, secondary, minute.substringBefore('+').toIntOrNull(), timestamp, period, secondAssist))
        }.associateBy { it.id }.values.sortedByDescending { it.sortOrder }
    }
    private val metrics = listOf("xG" to "Expected goals", "possession_ratio" to "Possession", "shots_at_goal_sum" to "Shots", "shots_on_target" to "Shots on target",
        "corner_kicks_sum" to "Corners", "shots_at_goal_wide" to "Shots off target", "shots_at_goal_blocked" to "Blocked shots",
        "passes_sum" to "Passes", "passes_successful_sum" to "Completed passes", "crosses_sum" to "Crosses", "interceptions_sum" to "Interceptions",
        "defensive_clearances" to "Clearances", "tackling_games_air_won" to "Aerial duels won", "total_attacking_zone_entries" to "Final-third entries",
        "fouls_sum" to "Fouls", "offsides" to "Offsides", "goalkeeper_saves" to "Saves", "cards_yellow" to "Yellow cards", "cards_red" to "Red cards",
        "distance_covered" to "Distance covered (m)")
    fun stats(raw: JsonElement, match: Match, seasonId: String): List<StatComparison> {
        val rows = raw["match_statistics_list"] as? JsonArray ?: throw IOException("MLS statistics format unavailable")
        if (rows.size > 16) throw IOException("MLS statistics exceeds limit")
        val scopes = rows.map { it["match_statistics"] }.filter { it["scope"].string() == "match" }
        if (scopes.size != 1) throw IOException("MLS match statistic scope is missing or ambiguous")
        val scope = scopes.single(); requireIdentity(scope, match, seasonId)
        val teams = scope["team_statistics"] as? JsonArray ?: throw IOException("MLS team statistics missing")
        if (teams.size != 2 || teams.map { NativeMlsMapping.qualified(it["team_id"].string(), "CLU") }.toSet() != setOf(match.home.teamId, match.away.teamId)) throw IOException("MLS team statistics club mismatch")
        fun team(home: Boolean) = teams.first { NativeMlsMapping.qualified(it["team_id"].string(), "CLU") == if (home) match.home.teamId else match.away.teamId }
        val home = team(true); val away = team(false)
        fun value(row: JsonElement, key: String): Double? = NativeMlsMapping.number(row[key], if (key == "possession_ratio") 100.0 else 1_000_000.0)
        fun display(value: Double, label: String) = if (label == "Expected goals") decimal(value, 2) else NativeMlsMapping.text(value)
        fun comparison(label: String, a: Double?, h: Double?, percent: Boolean = false): StatComparison? = if (a == null && h == null) null else
            StatComparison(label, a?.let { display(it, label) + if (percent) "%" else "" } ?: "–", h?.let { display(it, label) + if (percent) "%" else "" } ?: "–")
        return buildList {
            metrics.forEach { (key, label) -> comparison(label, value(away, key), value(home, key), key == "possession_ratio")?.let(::add) }
            fun accuracy(row: JsonElement): Double? { val passes = value(row, "passes_sum") ?: return null; val completed = value(row, "passes_successful_sum") ?: return null
                return if (passes > 0 && completed <= passes) kotlin.math.round(completed / passes * 1000) / 10 else null }
            comparison("Pass accuracy", accuracy(away), accuracy(home), true)?.let(::add)
            comparison("Ball recovery time (s)", NativeMlsMapping.number(away["advanced_stats"]["ball_recovery_time"]), NativeMlsMapping.number(home["advanced_stats"]["ball_recovery_time"]))?.let(::add)
            for (zone in 1..4) {
                fun zoneValue(row: JsonElement, key: String) = row["attacking_zones"].array.singleOrNull { it["zone_id"].string() == "$zone" }?.let { NativeMlsMapping.number(it[key], if (key == "ratio") 100.0 else 1_000_000.0) }
                comparison("Attacking zone $zone entries", zoneValue(away, "entries"), zoneValue(home, "entries"))?.let(::add)
                comparison("Attacking zone $zone share", zoneValue(away, "ratio"), zoneValue(home, "ratio"), true)?.let(::add)
            }
        }
    }
    fun playerStats(raw: JsonElement, match: Match, seasonId: String): List<SoccerPlayerMatchStats> {
        val scope = raw["match_statistics"]; requireIdentity(scope, match, seasonId)
        if (scope["scope"].string() != "match" || NativeMlsClient.cursor(raw) != null) throw IOException("MLS player statistics scope/truncation mismatch")
        val rows = scope["player_statistics"] as? JsonArray ?: throw IOException("MLS player statistics missing")
        if (rows.size > 128) throw IOException("MLS player statistics exceeds limit")
        return rows.map { row ->
            if (row["match_id"].string() != match.nativeReference?.gameId) throw IOException("MLS player row game mismatch")
            val team = NativeMlsMapping.qualified(row["team_id"].string(), "CLU")?.takeIf { it in setOf(match.home.teamId, match.away.teamId) } ?: throw IOException("MLS player row club mismatch")
            val person = NativeMlsMapping.person(row, "player_id", "player_first_name", "player_last_name") ?: throw IOException("MLS player identity missing")
            if (row.obj.orEmpty().size > 512) throw IOException("MLS player statistic columns exceeds limit")
            val values = row.obj.orEmpty().entries.sortedBy { it.key }.mapNotNull { (key, rawValue) -> NativeMlsMapping.reportedNumber(rawValue)?.let { humanize(key) to NativeMlsMapping.text(it) } }.toMap()
            SoccerPlayerMatchStats(person, team == match.home.teamId, values)
        }.distinctBy { it.player.id }
    }
    fun comments(raw: JsonElement, match: Match, seasonId: String): List<SoccerComment> {
        requireIdentity(raw["match_info"], match, seasonId)
        NativeMlsClient.cursor(raw)
        val rows = raw["commentary"] as? JsonArray ?: throw IOException("MLS commentary missing")
        if (rows.size > 500) throw IOException("MLS commentary exceeds limit")
        return rows.map { entry ->
            val id = entry["event_id"].stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: throw IOException("MLS commentary identity missing")
            val text = entry["commentary"].stringOrNull()?.takeIf { it.length <= 16_384 } ?: throw IOException("MLS commentary text missing")
            SoccerComment("mls:${match.nativeReference!!.gameId}|$id", entry["minute_of_play"].string(), text, entry["type"].string(),
                Dates.parseIso(entry["event_time"].stringOrNull()), entry["version"].string().toIntOrNull()?.takeIf { it >= 0 } ?: 0)
        }
    }
    fun humanize(key: String) = key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
