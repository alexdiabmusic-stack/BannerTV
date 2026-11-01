package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** SDP resources have different shapes. Events are team buckets, stats an array keyed by native teamId. */
object NativeSoccerDetails {
    private data class Metric(val label: String, val keys: List<String>, val percent: Boolean = false, val decimal: Boolean = false)
    private val metrics = listOf(
        Metric("Possession", listOf("possessionPercentage"), percent = true),
        Metric("Expected goals", listOf("expectedGoals"), decimal = true), Metric("Expected goals on target", listOf("expectedGoalsOnTarget"), decimal = true),
        Metric("Shots", listOf("totalScoringAtt")), Metric("Shots on target", listOf("ontargetScoringAtt")),
        Metric("Shots off target", listOf("shotOffTarget", "shotsOffTarget")), Metric("Blocked shots", listOf("blockedScoringAtt", "blockedShots")),
        Metric("Big chances created", listOf("bigChanceCreated")), Metric("Big chances missed", listOf("bigChanceMissed")),
        Metric("Corners", listOf("cornerTaken")), Metric("Passes", listOf("totalPass")), Metric("Completed passes", listOf("accuratePass")),
        Metric("Crosses", listOf("totalCross")), Metric("Tackles", listOf("totalTackle")), Metric("Tackles won", listOf("wonTackle")),
        Metric("Interceptions", listOf("interception")), Metric("Clearances", listOf("totalClearance")), Metric("Duels won", listOf("duelWon")),
        Metric("Aerial duels won", listOf("aerialWon")), Metric("Touches in opposition box", listOf("touchesInOppBox")),
        Metric("Final third entries", listOf("finalThirdEntries")), Metric("Fouls", listOf("fkFoulLost")),
        Metric("Offsides", listOf("totalOffside")), Metric("Saves", listOf("saves")), Metric("Yellow cards", listOf("yellowCard")), Metric("Red cards", listOf("redCard")),
    )
    fun stats(raw: JsonElement?, homeId: String, awayId: String): List<StatComparison>? {
        if (raw !is JsonArray || homeId == awayId) return null
        if (raw.size > 2 || raw.any { it["teamId"].string() !in setOf(homeId, awayId) } || raw.map { it["teamId"].string() }.distinct().size != raw.size) return null
        val home = raw.singleOrNull { it["teamId"].string() == homeId }?.get("stats")
        val away = raw.singleOrNull { it["teamId"].string() == awayId }?.get("stats")
        if (home?.obj == null && away?.obj == null) return null
        return metrics.mapNotNull { metric ->
            fun value(side: JsonElement?): Double? = metric.keys.firstNotNullOfOrNull { side[it].stringOrNull()?.toDoubleOrNull()?.takeIf { number -> number.isFinite() && number in 0.0..1_000_000_000.0 && (!metric.percent || number <= 100) } }
            fun display(number: Double?): String = when {
                number == null -> "–"
                metric.decimal -> decimal(number, 2)
                number % 1.0 == 0.0 -> number.toLong().toString()
                else -> decimal(number, 1)
            } + if (number != null && metric.percent) "%" else ""
            val h = value(home); val a = value(away)
            if (h == null && a == null) null else StatComparison(metric.label, display(a), display(h))
        }
    }

    private fun boundedEvents(raw: JsonElement?) = listOf("homeTeam", "awayTeam").all { side -> raw[side].obj != null &&
        listOf("goals", "cards", "subs").all { kind -> raw[side][kind] == null || raw[side][kind] is JsonArray && raw[side][kind].array.size <= 512 } }
    fun referencedPlayers(raw: JsonElement?): Set<String> = if (!boundedEvents(raw)) emptySet() else listOf("homeTeam", "awayTeam").flatMap { side -> listOf("goals", "cards", "subs").flatMap { kind -> raw[side][kind].array.flatMap { entry -> listOf("playerId", "assistPlayerId", "playerOnId", "playerOffId").mapNotNull { entry[it].stringOrNull()?.takeIf(::validId) } } } }.toSet()
    fun validLineups(raw: JsonElement?, matchId: String): Boolean = raw != null && raw["matchId"].stringOrNull()?.let { it == matchId } != false &&
        listOf("home_team" to "homeTeam", "away_team" to "awayTeam").all { (snake, camel) ->
            val team = raw[snake] ?: raw[camel]
            team.obj != null && (team["players"] == null || team["players"] is JsonArray && team["players"].array.size <= 64) &&
                team.path("formation", "lineup").array.size <= 12 && team.path("formation", "lineup").array.all { it is JsonArray && it.size <= 16 }
        }
    fun playerNames(raw: JsonElement?): Map<String, String> = raw.array.mapNotNull { entry ->
        val id = entry["id"].stringOrNull()?.takeIf(::validId) ?: return@mapNotNull null
        val name = listOf(entry["firstName"].string(), entry["lastName"].string()).filter(String::isNotBlank).joinToString(" ")
        name.takeIf(String::isNotBlank)?.let { id to it }
    }.toMap()
    private fun validId(id: String) = id.length <= 128 && id.matches(Regex("[A-Za-z0-9_-]+"))
    private data class Incident(val play: Play, val timestamp: Long?, val minute: Int?, val ordinal: Int)
    fun events(raw: JsonElement?, match: Match, nativeId: String, names: Map<String, String>, homeId: String? = null, awayId: String? = null): List<Play>? {
        if (!boundedEvents(raw)) return null
        if (raw["matchId"].stringOrNull()?.let { it != nativeId } == true || homeId != null && raw.path("homeTeam", "id").stringOrNull()?.let { it != homeId } == true || awayId != null && raw.path("awayTeam", "id").stringOrNull()?.let { it != awayId } == true) return null
        val incidents = mutableListOf<Incident>()
        for ((sideKey, side) in listOf("homeTeam" to match.home, "awayTeam" to match.away)) {
            for (kind in listOf("goals", "cards", "subs")) raw[sideKey][kind].array.forEachIndexed { ordinal, entry ->
                val playerId = entry[if (kind == "subs") "playerOnId" else "playerId"].stringOrNull()?.takeIf(::validId)
                val secondary = entry[if (kind == "subs") "playerOffId" else "assistPlayerId"].stringOrNull()?.takeIf(::validId)
                val timestampRaw = entry["timestamp"].stringOrNull()
                val timestamp = timestampRaw?.let { SportsTime.parseLocal(it, "yyyyMMdd'T'HHmmssZ", "UTC") }
                val time = entry["time"].string().trim().takeIf { it.matches(Regex("[0-9]{1,3}(?:\\+[0-9]{1,2})?")) }
                if (playerId == null && timestamp == null && time == null) return@forEachIndexed
                val rawType = entry[if (kind == "goals") "goalType" else "type"].string().ifBlank { if (kind == "goals") "Goal" else if (kind == "subs") "Substitution" else "Unknown card" }
                val lower = rawType.lowercase()
                val type = when (kind) {
                    "subs" -> "Substitution"
                    "cards" -> when { "second" in lower -> "Second yellow card"; "red" in lower -> "Red card"; "yellow" in lower -> "Yellow card"; else -> rawType }
                    else -> when { "miss" in lower -> rawType; "own" in lower -> "Own goal"; "penalt" in lower -> "Penalty goal"; lower == "goal" -> "Goal"; else -> rawType }
                }
                fun name(id: String?) = id?.let { names[it] ?: "Player $it" }
                val text = if (kind == "subs") listOfNotNull(name(playerId)?.let { "On: $it" }, name(secondary)?.let { "Off: $it" }).joinToString(" · ")
                    else listOfNotNull(name(playerId), if (kind == "goals") name(secondary)?.let { "Assist: $it" } else null).joinToString(" · ")
                val period = entry["period"].string()
                val periodNumber = when (period.lowercase().replace("_", "")) { "firsthalf", "1h", "1" -> 1; "secondhalf", "2h", "2" -> 2; "extrafirsthalf", "firsthalfextra" -> 3; "extrasecondhalf", "secondhalfextra" -> 4; else -> 0 }
                val label = when (periodNumber) { 1 -> "First half"; 2 -> "Second half"; 3 -> "Extra time · first half"; 4 -> "Extra time · second half"; else -> period }
                val minute = time?.split('+')?.mapNotNull(String::toIntOrNull)?.sum()
                val identity = "$nativeId|$sideKey|$kind|${playerId ?: "-"}|${timestampRaw ?: "-"}|$ordinal"
                val scoring = kind == "goals" && type in setOf("Goal", "Own goal", "Penalty goal")
                val eventKind = when (type) {
                    "Goal" -> SoccerEventKind.Goal; "Own goal" -> SoccerEventKind.OwnGoal; "Penalty goal" -> SoccerEventKind.PenaltyGoal
                    "Yellow card" -> SoccerEventKind.YellowCard; "Second yellow card" -> SoccerEventKind.SecondYellow; "Red card" -> SoccerEventKind.RedCard
                    "Substitution" -> SoccerEventKind.Substitution
                    else -> SoccerEventKind.Unknown
                }
                fun reference(id: String?) = id?.let { SportPlayer(it, name(it).orEmpty()) }
                val event = SoccerEvent(eventKind, reference(playerId), reference(secondary), minute, timestamp, period)
                incidents += Incident(Play(identity, label, NativeSoccerPresentation.eventClock(time, periodNumber), text.ifBlank { type }, side.teamId, null, null, scoring, type, periodNumber,
                    sortOrder = timestamp?.toDouble() ?: ((minute ?: 0) * 60_000.0 + ordinal), soccerEvent = event), timestamp, minute, ordinal)
            }
        }
        // Keep provider timestamp order authoritative. Insert untimed incidents by minute rather than using a
        // non-transitive comparator that mixes timestamp and minute comparisons on malformed/corrected feeds.
        val ordered = incidents.filter { it.timestamp != null }.sortedWith(compareBy<Incident> { it.timestamp }.thenBy { it.ordinal }.thenBy { it.play.id }).toMutableList()
        for (entry in incidents.filter { it.timestamp == null }.sortedWith(compareBy<Incident> { it.minute ?: -1 }.thenBy { it.ordinal }.thenBy { it.play.id })) {
            val index = ordered.indexOfFirst { (it.minute ?: -1) > (entry.minute ?: -1) }.takeIf { it >= 0 } ?: ordered.size
            ordered.add(index, entry)
        }
        return ordered.map { it.play }.distinctBy { it.id }.asReversed()
    }

    /** A malformed resource is unavailable, not an authoritative empty list of officials. */
    fun officials(raw: JsonElement?, matchId: String? = null): List<SoccerOfficial>? {
        if (matchId != null && raw["matchId"].stringOrNull()?.let { it != matchId } == true) return null
        val entries = raw["matchOfficials"] as? JsonArray ?: return null
        if (entries.size > 32) return null
        return entries.mapNotNull { entry ->
            val name = entry.path("official", "name").stringOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SoccerOfficial(name, entry["type"].stringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: "Official")
        }.distinctBy { it.name to it.role }
    }

    fun table(raw: JsonElement?, season: Int, live: Boolean, now: Long): SoccerTable? {
        val tables = raw["tables"] as? JsonArray ?: return null
        if (listOfNotNull(raw["season"].stringOrNull(), raw["season"]["id"].stringOrNull()).any { it != season.toString() } ||
            listOfNotNull(raw["competition"].stringOrNull(), raw["competition"]["id"].stringOrNull()).any { it != "8" } ||
            raw["live"].stringOrNull()?.let { it != live.toString() } == true || tables.size > 32) return null
        val entries = tables.flatMap { (it["entries"] as? JsonArray) ?: return null }
        if (entries.size > 100) return null
        fun integer(value: JsonElement?): Int? = value.stringOrNull()?.toIntOrNull()
        val deductions = raw["deductions"].array.mapNotNull { entry -> (entry.path("team", "id").stringOrNull() ?: entry["teamId"].stringOrNull())?.let { id -> integer(entry["points"])?.let { id to it } } }.toMap()
        val rows = entries.map { entry ->
            val team = entry["team"]; val id = team["id"].stringOrNull() ?: return null
            val name = team["name"].stringOrNull() ?: team["shortName"].stringOrNull() ?: return null
            val overall = entry["overall"]
            SoccerTableRow(id, name, integer(overall["position"]), integer(overall["played"]), integer(overall["won"]), integer(overall["drawn"]), integer(overall["lost"]), integer(overall["goalsFor"]), integer(overall["goalsAgainst"]), integer(overall["points"]), integer(overall["startingPosition"]), deductions[id])
        }.distinctBy { it.id }.sortedBy { it.position ?: Int.MAX_VALUE }
        return SoccerTable(season, live, now, rows, integer(raw["matchweek"]))
    }
}
