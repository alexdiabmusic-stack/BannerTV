package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.*

/** Opaque Sportec identities remain distinct from ESPN, Opta and EPL numbers. */
object NativeMlsMapping {
    val league = League.find("soccer/usa.1")!!
    fun rawId(value: String, family: String): String? = value.removePrefix("mls:").takeIf {
        it.matches(Regex("MLS-$family-[A-Z0-9]{6,16}")) && (value == it || value == "mls:$it")
    }
    fun qualified(value: String, family: String) = rawId(value, family)?.let { "mls:$it" }
    fun number(raw: JsonElement?, max: Double = 1_000_000.0): Double? = (raw as? JsonPrimitive)?.takeUnless { it.isString && it.content.length > 64 }
        ?.content?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..max }
    fun reportedNumber(raw: JsonElement?): Double? = (raw as? JsonPrimitive)?.content?.takeIf { it.length <= 64 }?.toDoubleOrNull()?.takeIf { it.isFinite() && it in -1_000_000.0..1_000_000.0 }
    fun text(value: Double) = plainNumber(value.toString())
    private fun score(raw: JsonElement?) = raw.stringOrNull()?.toIntOrNull()?.takeIf { it in 0..100 }
    fun boolean(raw: JsonElement?): Boolean? = when (raw.stringOrNull()?.lowercase()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    fun clock(raw: String): String? = raw.takeIf { it.matches(Regex("[0-9]{1,3}(\\+[0-9]{1,2})?")) }?.let { "$it′" }
    data class Status(val state: GameState, val label: String, val runningClock: String? = null)
    fun status(raw: String, minute: String): Status {
        val lower = raw.lowercase()
        return when {
            lower in setOf("finalwhistle", "fulltime", "postmatch") -> Status(GameState.FINAL, "Full time")
            lower in setOf("scheduled", "prematch", "") -> Status(GameState.PRE, "Upcoming")
            lower in setOf("halftime", "halftimebreak") -> Status(GameState.LIVE, "Half-time")
            lower in setOf("extrahalftime", "halftimeextra") -> Status(GameState.LIVE, "Half-time (ET)")
            lower in setOf("penalties", "penaltyshootout", "penalty") -> Status(GameState.LIVE, "Penalties")
            lower in setOf("firsthalf", "secondhalf", "extrafirsthalf", "firsthalfextra", "extrasecondhalf", "secondhalfextra") ->
                Status(GameState.LIVE, clock(minute) ?: raw, clock(minute))
            "postpon" in lower -> Status(GameState.PRE, "Postponed")
            "cancel" in lower -> Status(GameState.PRE, "Cancelled")
            "abandon" in lower -> Status(GameState.PRE, "Abandoned")
            "suspend" in lower -> Status(GameState.LIVE, "Suspended")
            "delay" in lower -> Status(GameState.PRE, "Delayed")
            else -> Status(GameState.PRE, raw) // data_status is not a game-status substitute.
        }
    }
    fun club(raw: JsonElement?): Team? {
        val id = qualified(raw["club_id"].string(), "CLU") ?: return null
        val name = raw["club_name"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
        return Team(id, name, raw["club_short_name"].string(raw["short_name"].string(name)),
            raw["club_three_letter_code"].string(raw["three_letter_code"].string()), null, league)
    }
    fun game(raw: JsonElement, overview: Boolean = false): Match? {
        val info = if (overview) raw["match_information"] else raw
        val id = rawId(info["match_id"].string(), "MAT") ?: return null
        val competition = info["competition_id"].string().takeIf { it in NativeMlsClient.COMPETITIONS } ?: return null
        rawId(info["season_id"].string(), "SEA") ?: return null
        val season = info["season"].string().toIntOrNull()?.takeIf { it in 1900..2200 } ?: return null
        val start = (if (overview) Dates.parseIso(info["kickoff_time"].stringOrNull()) else null)
            ?: Dates.parseIso(info["planned_kickoff_time"].stringOrNull()) ?: return null
        fun team(side: String): Team? {
            val block = if (overview) raw[side] else null
            val teamId = qualified(if (overview) block["team_id"].string() else raw["${side}_team_id"].string(), "CLU") ?: return null
            val name = (if (overview) block["team_name"].stringOrNull() else raw["${side}_team_name"].stringOrNull())?.takeIf(String::isNotBlank) ?: return null
            val short = if (overview) block["team_short_name"].string(name) else raw["${side}_team_short_name"].string(name)
            val abbr = if (overview) block["team_three_letter_code"].string() else raw["${side}_team_three_letter_code"].string()
            return Team(teamId, name, short, abbr, null, league)
        }
        val home = team("home") ?: return null; val away = team("away") ?: return null
        if (home.id == away.id) return null
        val state = status(info["match_status"].string(), info["minute_of_play"].string())
        val h = score(info["home_team_goals"]); val a = score(info["away_team_goals"])
        fun side(team: Team, goals: Int?, other: Int?) = TeamSide(team.name, team.shortName, team.abbreviation,
            score = goals?.takeIf { state.state != GameState.PRE }?.toString(), teamId = team.id,
            isWinner = state.state == GameState.FINAL && goals != null && other != null && goals > other)
        return Match("mls:$id", league, start, "${home.name} vs ${away.name}", "${home.abbreviation} v ${away.abbreviation}",
            state.state, state.label, side(home, h, a), side(away, a, h), venue = if (overview) raw["environment"]["stadium_name"].stringOrNull() else raw["stadium_name"].stringOrNull(),
            live = if (state.state == GameState.LIVE) LiveContext(state.runningClock, info["match_status"].stringOrNull()) else LiveContext.EMPTY,
            nativeReference = NativeGameReference("MLS", id, season, competition))
    }
    fun resourceIdentity(info: JsonElement?, match: Match, seasonId: String): Boolean {
        val ref = match.nativeReference ?: return false
        return info["match_id"].string() == ref.gameId && info["competition_id"].string() == ref.seasonType &&
            info["season_id"].string() == seasonId && info["season"].string().toIntOrNull() == ref.season
    }
    fun person(raw: JsonElement?, idKey: String = "person_id", first: String = "first_name", last: String = "last_name"): SportPlayer? {
        val id = rawId(raw[idKey].string(), "OBJ") ?: return null
        val name = listOf(raw[first].string(), raw[last].string()).filter(String::isNotBlank).joinToString(" ")
        if (name.isBlank()) return null
        return SportPlayer("mls:$id", name, raw["shirt_number"].string(), raw["playing_position"].string())
    }
    fun lineups(raw: JsonElement): List<SoccerLineup> {
        return listOf("home", "away").mapNotNull { side ->
            val block = raw[side]; val value = block["players"]
            val rows = if (value == null || value is JsonNull) JsonArray(emptyList()) else value as? JsonArray ?: throw IOException("MLS lineup format unavailable")
            if (rows.size > 64) throw IOException("MLS lineup exceeds limit")
            val slots = rows.map { player ->
                val person = person(player) ?: throw IOException("MLS lineup player identity missing")
                val starting = boolean(player["starting"])
                SoccerLineupPlayer(person, starting == true, boolean(player["team_leader"]) == true, membershipKnown = starting != null)
            }.distinctBy { it.player.id }
            if (slots.isEmpty()) return@mapNotNull null
            val staff = block["trainer_staff"] as? JsonArray
            if (staff != null && staff.size > 32) throw IOException("MLS staff exceeds limit")
            val manager = staff?.firstOrNull { it["role"].string() == "headcoach" }
            SoccerLineup(side == "home", block["latest_line_up"].stringOrNull()?.takeIf(String::isNotBlank)
                ?: block["initial_line_up"].stringOrNull()?.takeIf(String::isNotBlank), slots,
                listOf(manager["first_name"].string(), manager["last_name"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank))
        }
    }
    fun officials(raw: JsonElement): List<SoccerOfficial> {
        val value = raw["referees"]
        val rows = if (value == null || value is JsonNull) JsonArray(emptyList()) else value as? JsonArray ?: throw IOException("MLS officials format unavailable")
        if (rows.size > 32) throw IOException("MLS officials exceeds limit")
        return rows.map { row ->
            val name = listOf(row["first_name"].string(), row["last_name"].string()).filter(String::isNotBlank).joinToString(" ")
            if (name.isBlank()) throw IOException("MLS official name missing")
            SoccerOfficial(name, row["role"].string("Official"))
        }
    }
}
