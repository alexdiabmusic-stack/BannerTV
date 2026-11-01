package com.alexdiab.stadiatv.sports

import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import com.alexdiab.stadiatv.foundation.safeWebURL

enum class NflSeasonType(val label: String) { PRE("Preseason"), REG("Regular season"), POST("Postseason") }

@Immutable
data class NflWeek(val season: Int, val type: NflSeasonType, val number: Int) {
    val valid: Boolean get() = season in 2000..2100 && number in 1..30
    val key: String get() = "$season:${type.name}:$number"
}

@Immutable
data class NflWeekChoice(val week: NflWeek, val label: String, val byeTeams: List<String> = emptyList())

/** Native calendar mapping. Game and team identities are provider-qualified, never ESPN numbers. */
object NflCalendar {
    fun week(raw: JsonElement?): NflWeek? {
        val season = raw["season"].string().toIntOrNull() ?: return null
        val type = NflSeasonType.entries.firstOrNull { it.name == raw["seasonType"].string() } ?: return null
        return NflWeek(season, type, raw["week"].string().toIntOrNull() ?: return null).takeIf { it.valid }
    }

    fun choices(raw: JsonElement, season: Int, type: NflSeasonType): List<NflWeekChoice> {
        if (raw["weeks"] !is JsonArray) throw IOException("NFL weeks response is unavailable")
        return raw["weeks"].array.mapNotNull { row ->
            val week = week(row) ?: row["week"].string().toIntOrNull()?.let { NflWeek(season, type, it) }?.takeIf { it.valid } ?: return@mapNotNull null
            if (week.season != season || week.type != type || row["season"].stringOrNull()?.let { it != "$season" } == true || row["seasonType"].stringOrNull()?.let { it != type.name } == true) return@mapNotNull null
            val label = mapOf("WC" to "Wild Card", "DIV" to "Divisional", "CONF" to "Conference Championship", "SB" to "Super Bowl")[row["weekType"].string()] ?: "Week ${week.number}"
            NflWeekChoice(week, label, row["byeTeams"].array.mapNotNull { it["fullName"].stringOrNull() }.distinct())
        }.associateBy { it.week.number }.values.sortedBy { it.week.number }
    }

    fun rows(raw: JsonElement): List<JsonElement> = when {
        raw is JsonArray -> raw
        raw["games"] is JsonArray -> raw["games"].array
        raw["data"] is JsonArray -> raw["data"].array
        else -> throw IOException("NFL games response is unavailable")
    }

    fun image(raw: String?): String? {
        if (raw == null) return null
        val path = raw.replace("{formatInstructions}", if ("/image/upload/" in raw) "f_png,w_192" else "image/upload/f_png,w_192")
        return path.takeIf { safeWebURL(it) }
    }

    fun phase(raw: String, quarter: String? = null): Pair<GameState, String> {
        val key = raw.uppercase().replace('-', '_').replace(' ', '_')
        val final = key in setOf("FINAL", "FINAL_OVERTIME", "FINAL_OT", "END_OF_GAME")
        if (!final && quarter?.uppercase() in setOf("HALFTIME", "HALF_TIME", "END_OF_HALF")) return GameState.LIVE to "Halftime"
        return when (key) {
            "FINAL_OVERTIME", "FINAL_OT" -> GameState.FINAL to "Final · OT"
            "FINAL", "END_OF_GAME" -> GameState.FINAL to "Final"
            "HALFTIME", "HALF_TIME" -> GameState.LIVE to "Halftime"
            "IN_PROGRESS", "INGAME", "IN_GAME", "LIVE", "GAME" -> GameState.LIVE to "Live"
            "DELAYED", "SUSPENDED_TEMPORARY" -> GameState.LIVE to "Delayed"
            "SUSPENDED" -> GameState.LIVE to "Suspended"
            "POSTPONED" -> GameState.PRE to "Postponed"
            "CANCELLED", "CANCELED" -> GameState.PRE to "Cancelled"
            "SCHEDULED" -> GameState.PRE to "Scheduled"
            "PREGAME", "PRE_GAME" -> GameState.PRE to "Pregame"
            else -> GameState.PRE to "Status unavailable"
        }
    }

    fun matches(raw: JsonElement, requested: NflWeek, metadata: Map<String, JsonElement> = emptyMap()): List<Match> = rows(raw).mapNotNull { row ->
        if (week(row) != requested) return@mapNotNull null
        val id = row["id"].stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: return@mapNotNull null
        val start = Dates.parseIso(row["time"].string()) ?: return@mapNotNull null
        val summary = row["summary"]
        if (summary["gameId"].stringOrNull()?.let { it != id } == true) return@mapNotNull null
        val (state, status) = phase(summary["phase"].stringOrNull() ?: row["status"].string(), summary["quarter"].stringOrNull())
        fun side(key: String): TeamSide? {
            val team = row[key]; val nativeId = team["id"].stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: return null
            if (summary.path(key, "teamId").stringOrNull()?.let { it != nativeId } == true) return null
            val info = metadata[nativeId]
            val logo = team["currentLogo"].stringOrNull() ?: info["currentLogo"].stringOrNull()
            val abbreviation = info["abbreviation"].stringOrNull() ?: team["abbreviation"].stringOrNull()
                ?: logo?.substringAfterLast('/')?.takeIf { it.matches(Regex("[A-Z]{2,3}")) } ?: "NFL"
            val name = team["fullName"].stringOrNull() ?: info["fullName"].stringOrNull() ?: abbreviation
            val score = summary.path(key, "score", "total").string().toIntOrNull()?.takeIf { it >= 0 && state != GameState.PRE }?.toString()
            return TeamSide(name, abbreviation, abbreviation, image(logo), score, teamId = "nfl:$nativeId")
        }
        val away = side("awayTeam") ?: return@mapNotNull null; val home = side("homeTeam") ?: return@mapNotNull null
        fun winner(one: TeamSide, other: TeamSide) = one.copy(isWinner = state == GameState.FINAL && one.score?.toIntOrNull()?.let { n -> other.score?.toIntOrNull()?.let { n > it } } == true)
        Match("nfl:$id", League.find("football/nfl")!!, start, "${away.displayName} at ${home.displayName}", "${away.abbreviation} @ ${home.abbreviation}", state, status, winner(home, away), winner(away, home),
            row.path("broadcastInfo", "homeNetworkChannels").array.mapNotNull { it.stringOrNull() }.distinct(), row.path("venue", "name").stringOrNull(),
            nativeReference = NativeGameReference("NFL", id, requested.season, requested.type.name, requested.number),
            live = if (state == GameState.LIVE) LiveContext(summary["clock"].stringOrNull(), summary["quarter"].stringOrNull(), buildList {
                summary["yardLine"].stringOrNull()?.let(::add)
                val down = summary["down"].stringOrNull()?.toIntOrNull()?.takeIf { it in 1..4 }
                val distance = if (summary["isGoalToGo"].bool()) "GOAL" else summary["distance"].stringOrNull()?.toIntOrNull()?.takeIf { it >= 0 }?.toString()
                if (down != null && distance != null) add("${listOf("1st", "2nd", "3rd", "4th")[down - 1]} & $distance")
            }) else LiveContext.EMPTY)
    }.associateBy { it.id }.values.sortedBy { it.startMillis }
}
