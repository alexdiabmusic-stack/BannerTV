package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement

/** GSIS codes influence presentation only through the same isolated rules as NFLPlayMapper. */
object NativeFootballPlays {
    fun lineScore(raw: JsonElement, match: Match): LineScore? {
        val away = raw.path("summary", "awayTeam", "score"); val home = raw.path("summary", "homeTeam", "score")
        val period = raw.path("summary", "quarter").string().uppercase()
        val overtime = period.startsWith("OT") || period.startsWith("OVERTIME") || (period.toIntOrNull() ?: 0) > 4 || raw.path("summary", "phase").string().uppercase() in setOf("FINAL_OVERTIME", "FINAL_OT")
        val quarters = (away.obj?.keys.orEmpty() + home.obj?.keys.orEmpty()).filter { key ->
            key.matches(Regex("q[1-4]|ot[0-9]{0,2}")) && (!key.startsWith("ot") || overtime || away[key].int() > 0 || home[key].int() > 0)
        }.distinct().sortedBy { if (it.startsWith("q")) it.drop(1).toInt() else 4 + (it.drop(2).toIntOrNull() ?: 1) }
        if (quarters.isEmpty()) return null
        fun score(side: JsonElement?, key: String) = side[key].string().toIntOrNull()?.takeIf { it >= 0 }?.toString() ?: "–"
        return LineScore(quarters.map { it.uppercase() }, quarters.map { score(away, it) }, quarters.map { score(home, it) }, score(away, "total"), score(home, "total"), match.away.abbreviation, match.home.abbreviation)
    }
    fun quarter(number: Int): String = when { number in 1..4 -> "Q$number"; number > 4 -> "OT ${number - 4}"; else -> "" }
    fun kind(raw: String, scoring: String?, text: String, codes: Set<Int>): String {
        if (scoring == "TOUCHDOWN") return "Touchdown"
        if (scoring == "SAFETY") return "Safety"
        if (raw == "FIELD_GOAL" && codes.any { it in setOf(69, 71) }) return "Missed field goal"
        if (raw == "XP_KICK" && codes.any { it in setOf(73, 74) }) return "Missed extra point"
        if (19 in codes) return "Interception"
        if (codes.any { it in setOf(52, 53) }) return "Fumble"
        if (raw == "PASS" && 14 in codes) return "Incomplete pass"
        val description = text.lowercase()
        return when (raw) {
            "RUSH" -> if ("kneels" in description) "Kneel" else "Run"
            "PASS" -> when { "intercepted" in description -> "Interception"; "incomplete" in description -> "Incomplete pass"; "spiked" in description -> "Spike"; else -> "Pass" }
            "SACK" -> "Sack"; "PUNT" -> "Punt"; "KICK_OFF" -> "Kickoff"; "FIELD_GOAL" -> "Field goal"
            "XP_KICK" -> "Extra point"; "PAT2" -> "Two-point conversion"; "PENALTY" -> "Penalty"; "TIMEOUT" -> "Timeout"
            "END_QUARTER" -> "End of quarter"; "END_GAME" -> "Final"
            "COMMENT" -> if ("review" in description || "challenge" in description) "Review" else "Play"
            else -> "Play"
        }
    }

    /** Source snapshots replace earlier entries, including deletions; no duplicate season stats are accumulated. */
    fun accepted(raw: List<JsonElement>): List<JsonElement> {
        val entries = LinkedHashMap<String, JsonElement>()
        raw.forEach { play ->
            val id = play["playId"].stringOrNull() ?: return@forEach
            if (play["playDeleted"].bool()) entries.remove(id) else entries[id] = play
        }
        return entries.values.sortedWith(compareBy(::sequence, { it["playId"].string() }))
    }
    private fun sequence(raw: JsonElement) = raw["playSequenceNumber"].double(Double.NaN).takeIf(Double::isFinite)
        ?: raw["playId"].double(Double.NaN).takeIf(Double::isFinite) ?: 0.0

    fun map(raw: List<JsonElement>, drives: List<JsonElement>, gameId: String, match: Match, nativeAway: String, nativeHome: String, names: Map<String, String>): List<Play> = raw.map { value ->
        val participants = value["stats"].array.distinctBy { it["playStatId"].stringOrNull() ?: it.toString() }.map { stat ->
            val id = stat["personId"].stringOrNull()
            FootballParticipant(id, names[id]?.takeIf(String::isNotBlank) ?: stat["gsisPlayerName"].stringOrNull() ?: "Unknown player",
                stat["teamId"].stringOrNull(), stat["statType"].string().toIntOrNull(), stat["yards"].string().toIntOrNull())
        }
        val codes = participants.mapNotNull { it.statType }.toSet()
        val text = value["playDescription"].stringOrNull()?.trim()?.takeIf(String::isNotBlank) ?: "Play details unavailable"
        val rawType = value["playType"].string("UNKNOWN")
        val driveSequence = value["driveSequence"].string().toIntOrNull()
        val drive = drives.firstOrNull { it["sequence"].string().toIntOrNull() == driveSequence }
        val team = value["scoringTeamId"].stringOrNull() ?: drive["teamId"].stringOrNull()
        val side = when { team == null -> null; nativeAway.isNotBlank() && team == nativeAway -> match.away.teamId; nativeHome.isNotBlank() && team == nativeHome -> match.home.teamId; else -> null }
        val event = FootballEvent(driveSequence, value["down"].string().toIntOrNull()?.takeIf { it in 1..4 }, value["yardsRemaining"].string().toIntOrNull()?.takeIf { it >= 0 },
            value["playIsGoalToGo"].bool(), value["yardLine"].stringOrNull(), value["yardsGained"].string().toIntOrNull(), codes.any { it in setOf(9, 19, 59, 60) },
            rawType == "PENALTY" || text.contains("PENALTY", true), codes.any { it in setOf(3, 4, 5) }, participants, rawType)
        val number = value["quarter"].string().toIntOrNull() ?: 0
        Play("nfl:$gameId:${value["playId"].string()}", quarter(number), value["clockTime"].string(), text, side, null, null, value["playScored"].bool(),
            kind(rawType, value["scoringPlayType"].stringOrNull(), text, codes), number,
            sortOrder = sequence(value), footballEvent = event)
    }.asReversed()

    fun currentDrive(drives: List<GameDrive>, live: Boolean, status: String? = null): String? {
        if (!live || status != null && status !in setOf("Live", "Delayed")) return null
        val last = drives.maxByOrNull { it.footballDrive?.sequence ?: -1 } ?: return null
        val complete = setOf("touchdown", "field goal", "punt", "interception", "fumble", "downs", "missed field goal", "end of half", "end of game", "safety")
        return last.id.takeUnless { last.footballDrive?.scoring == true || last.result.lowercase() in complete }
    }
}
