package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.*

/** Shared NBAGameMapper/WNBAGameMapper and column-name tables; provider numbers never become ESPN IDs. */
object NativeBasketballMapping {
    fun id(raw: JsonElement?) = raw.stringOrNull()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
    fun start(raw: JsonElement) = listOf("gameDateTimeUTC", "gameTimeUTC", "gameDateUTC").firstNotNullOfOrNull { key ->
        val value = raw[key].string().trim()
        // The real WNBA static schedule supplies 1900-01-01 in gameTimeUTC even for completed 2026
        // games. Date+time is the authoritative schedule field; sentinel dates are never kickoffs.
        value.takeUnless { it.startsWith("1900-01-01") || it.startsWith("0001-01-01") }?.let(Dates::parseIso)
    }
    fun state(raw: JsonElement): Pair<GameState, String> {
        val text = raw["gameStatusText"].string(); val lower = text.lowercase()
        return when {
            "ppd" in lower || "postpon" in lower -> GameState.PRE to "Postponed"
            "cancel" in lower -> GameState.PRE to "Cancelled"
            "suspend" in lower -> GameState.LIVE to "Suspended"
            "delay" in lower -> GameState.LIVE to "Delayed"
            "halftime" in lower || "half time" in lower -> GameState.LIVE to "Halftime"
            "final" in lower || raw["gameStatus"].int() == 3 -> {
                val overtime = raw["period"].int() - raw["regulationPeriods"].int(4)
                GameState.FINAL to if (overtime > 0) "Final · ${if (overtime == 1) "OT" else "${overtime}OT"}" else "Final"
            }
            raw["gameStatus"].int() == 2 -> GameState.LIVE to text.ifBlank { "Live" }
            else -> GameState.PRE to text.ifBlank { if (raw["gameStatus"].int() == 1) "Scheduled" else "Status unavailable" }
        }
    }
    fun statusLabel(raw: JsonElement, state: GameState, label: String): String {
        if (state != GameState.LIVE || label in setOf("Halftime", "Suspended", "Delayed")) return label
        val period = raw["period"].int().takeIf { it > 0 }?.let { NativeBasketballDuration.period(it, raw["regulationPeriods"].int(4)) }
        val clock = NativeBasketballDuration.clock(raw["gameClock"].string())
        return listOfNotNull(period, clock).joinToString(" · ").ifBlank { label }
    }
    fun team(raw: JsonElement?, config: NativeBasketballLeague): Team? {
        val id = id(raw["teamId"]) ?: return null
        val abbreviation = raw["teamTricode"].stringOrNull()?.takeIf { it.matches(Regex("[A-Z]{2,4}")) } ?: return null
        val name = listOf(raw["teamCity"].string(), raw["teamName"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank) ?: return null
        return Team("${config.prefix}:$id", name, abbreviation, abbreviation, config.logo(abbreviation), config.league)
    }
    fun game(raw: JsonElement, config: NativeBasketballLeague): Match? {
        val id = raw["gameId"].stringOrNull()?.takeIf { it.matches(Regex("[0-9]{10}")) } ?: return null
        val time = start(raw) ?: return null
        val away = team(raw["awayTeam"], config) ?: return null; val home = team(raw["homeTeam"], config) ?: return null
        if (away.id == home.id) return null
        val (state, label) = state(raw)
        fun score(key: String) = raw[key]["score"].stringOrNull()?.toIntOrNull()?.takeIf { state != GameState.PRE && it >= 0 }?.toString()
        val a = score("awayTeam"); val h = score("homeTeam")
        fun side(team: Team, key: String, score: String?, other: String?): TeamSide {
            val wins = raw[key]["wins"].stringOrNull(); val losses = raw[key]["losses"].stringOrNull()
            return TeamSide(team.name, team.shortName, team.abbreviation, team.logoUrl, score,
                if (wins != null && losses != null) "$wins-$losses" else null,
                state == GameState.FINAL && score?.toIntOrNull()?.let { n -> other?.toIntOrNull()?.let { n > it } } == true, teamId = team.id)
        }
        val broadcasts = listOf("nationalBroadcasters", "nationalOttBroadcasters", "homeTvBroadcasters", "awayTvBroadcasters")
            .flatMap { raw[it].array }.mapNotNull { it["broadcasterDisplay"].stringOrNull() }.distinct()
        val period = raw["period"].int(); val regulation = raw["regulationPeriods"].int(4)
        val clock = raw["gameClock"].stringOrNull()?.let { NativeBasketballDuration.clock(it).orEmpty() }
        return Match("${config.prefix}:$id", config.league, time, "${away.name} at ${home.name}", "${away.abbreviation} @ ${home.abbreviation}", state, statusLabel(raw, state, label),
            side(home, "homeTeam", h, a), side(away, "awayTeam", a, h), broadcasts, raw["arena"]["arenaName"].stringOrNull(),
            live = if (state != GameState.LIVE) LiveContext.EMPTY else LiveContext(clock, if (period > regulation) "${period - regulation}OT" else "$period"),
            nativeReference = NativeGameReference(config.name, id))
    }
    fun table(raw: JsonElement, name: String): List<JsonElement> {
        val sets = raw["resultSets"]
        if (sets !is JsonArray) throw IOException("Basketball statistics have no named result sets")
        val set = sets.singleOrNull { it["name"].string() == name } ?: throw IOException("Basketball statistics table $name is unavailable")
        val headers = set["headers"].array.map { it.stringOrNull() ?: throw IOException("Invalid statistics header") }
        val rows = set["rowSet"]
        if (headers.isEmpty() || headers.size > 256 || headers.distinct().size != headers.size || rows !is JsonArray || rows.size > 8192 || rows.any { it !is JsonArray || it.size > headers.size }) throw IOException("Basketball statistics table changed shape")
        return rows.map { cells -> JsonObject(headers.zip(cells.array).toMap()) }
    }
}
