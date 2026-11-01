package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.*

/** Official numeric game/club IDs and Opta p* people are separate from FotMob and ESPN identities. */
object NativeLaLigaMapping {
    val league = League.find("soccer/esp.1")!!
    private val madrid = "Europe/Madrid"
    fun rawId(value: String, person: Boolean = false): String? = value.removePrefix("laliga:").takeIf {
        (value == it || value == "laliga:$it") && it.matches(if (person) Regex("p[0-9]{1,12}") else Regex("[0-9]{1,12}")) && it.removePrefix("p").any { digit -> digit != '0' }
    }
    fun season(at: Long): Int = Dates.format("yyyy-MM", at, madrid).let { it.take(4).toInt() - if (it.takeLast(2).toInt() < 7) 1 else 0 }
    fun iso(value: String?): Long? {
        val raw = value ?: return null
        val parts = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})$").matchEntire(raw)?.groupValues ?: return null
        if (parts[4].toInt() !in 0..23 || parts[5].toInt() !in 0..59 || parts[6].toInt() !in 0..59) return null
        if (!raw.endsWith('Z')) {
            val offset = raw.takeLast(6); val hours = offset.substring(1, 3).toInt(); val minutes = offset.substring(4, 6).toInt()
            if (hours !in 0..14 || minutes !in 0..59 || hours == 14 && minutes != 0) return null
        }
        if (Dates.parseLocal(raw.take(10), "yyyy-MM-dd", "UTC") == null) return null
        return Dates.parseIso(raw)?.takeIf { it in 0..7_289_654_400_000L }
    }
    fun crest(raw: JsonElement?): String? = raw["shield"]["url"].stringOrNull()?.takeIf { safeWebURL(it) && it.substringAfter("://").substringBefore('/').lowercase() == "assets.laliga.com" }
    fun team(raw: JsonElement?): Team? {
        val id = rawId(raw["id"].string()) ?: return null
        val name = raw["name"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
        return Team("laliga:$id", name, raw["nickname"].string(name), raw["shortname"].string(), crest(raw), league,
            raw["color"].stringOrNull()?.removePrefix("#")?.takeIf { it.matches(Regex("[A-Fa-f0-9]{6}")) })
    }
    fun status(raw: String): NativeMlsMapping.Status = when (raw) {
        "PreMatch", "" -> NativeMlsMapping.Status(GameState.PRE, "Upcoming")
        "FullTime" -> NativeMlsMapping.Status(GameState.FINAL, "Full time")
        "FirstHalf" -> NativeMlsMapping.Status(GameState.LIVE, "First half")
        "SecondHalf" -> NativeMlsMapping.Status(GameState.LIVE, "Second half")
        "HalfTime" -> NativeMlsMapping.Status(GameState.LIVE, "Half-time")
        "Suspended" -> NativeMlsMapping.Status(GameState.LIVE, "Suspended")
        "Postponed" -> NativeMlsMapping.Status(GameState.PRE, "Postponed")
        "Cancelled" -> NativeMlsMapping.Status(GameState.PRE, "Cancelled")
        "Abandoned" -> NativeMlsMapping.Status(GameState.PRE, "Abandoned")
        "Delayed" -> NativeMlsMapping.Status(GameState.PRE, "Delayed")
        else -> NativeMlsMapping.Status(GameState.PRE, raw) // Do not interpret another provider's status tokens.
    }
    fun match(raw: JsonElement, year: Int, subscription: String): Match? {
        val id = rawId(raw["id"].string()) ?: return null
        if (raw["competition"]["id"].string() != "1" || raw["competition"]["slug"].string() != "primera-division" || raw["season"]["year"].string().toIntOrNull() != year) return null
        val start = iso(raw["date"].stringOrNull()) ?: return null
        val home = team(raw["home_team"]) ?: return null; val away = team(raw["away_team"]) ?: return null
        if (home.id == away.id) return null
        val status = status(raw["status"].string())
        fun score(key: String) = raw[key].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..100 }
        val h = score("home_score"); val a = score("away_score")
        fun side(team: Team, goals: Int?, opponent: Int?) = TeamSide(team.name, team.shortName, team.abbreviation, team.logoUrl,
            goals?.takeIf { status.state != GameState.PRE }?.toString(), isWinner = status.state == GameState.FINAL && goals != null && opponent != null && goals > opponent,
            teamId = team.id, colorHex = team.color)
        return Match("laliga:$id", league, start, "${home.name} vs ${away.name}", "${home.abbreviation} v ${away.abbreviation}", status.state, status.label,
            side(home, h, a), side(away, a, h), broadcasts = raw["channels"].array.mapNotNull { it["name"].stringOrNull()?.takeIf(String::isNotBlank) }.distinct().take(32),
            venue = raw["venue"]["name"].stringOrNull(), nativeReference = NativeGameReference("LaLiga", id, year, subscription, raw["gameweek"]["week"].string().toIntOrNull()))
    }
    /** The actual fixture catalogue publishes assignments; no undocumented officials route is invented. */
    fun officials(raw: JsonElement, expected: Match): List<SoccerOfficial>? {
        val reference = expected.nativeReference?.takeIf { it.provider == "LaLiga" } ?: throw IOException("La Liga officials scope missing")
        val fixture = match(raw, reference.season ?: throw IOException("La Liga officials season missing"), reference.seasonType ?: throw IOException("La Liga officials subscription missing"))
            ?: throw IOException("La Liga officials fixture invalid")
        if (fixture.key != expected.key || fixture.home.teamId != expected.home.teamId || fixture.away.teamId != expected.away.teamId) throw IOException("La Liga officials fixture mismatch")
        val value = raw["persons_role"] ?: return null
        if (value is JsonNull) return null
        val rows = (value as? JsonArray)?.takeIf { it.size <= 32 } ?: throw IOException("La Liga officials format/bounds unavailable")
        val seen = HashSet<String>()
        return rows.map { row ->
            val id = row["person"]["id"].string().takeIf { rawId(it) == it } ?: throw IOException("La Liga official identity missing")
            val name = row["person"]["name"].stringOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 } ?: throw IOException("La Liga official name missing")
            val role = row["role"]["slug"].stringOrNull()?.takeIf { it.matches(Regex("[a-z0-9-]{1,100}")) } ?: throw IOException("La Liga official role missing")
            if (!seen.add("$id|$role")) throw IOException("La Liga official assignment duplicated")
            val label = when (role) {
                "arbitro-principal" -> "Referee"; "cuarto-arbitro" -> "Fourth official"; "var" -> "VAR"; "avar" -> "Assistant VAR"
                "arbitro-asistente-1" -> "Assistant referee 1"; "arbitro-asistente-2" -> "Assistant referee 2"
                else -> row["role"]["name"].stringOrNull()?.takeIf { it.isNotBlank() && it.length <= 128 } ?: role
            }
            SoccerOfficial(name, label)
        }
    }
    fun standings(raw: JsonElement): StandingGroup {
        val rows = raw["standings"] as? JsonArray ?: throw IOException("La Liga standings missing")
        if (rows.size > 100 || raw["total"].stringOrNull()?.toIntOrNull()?.let { it != rows.size } == true) throw IOException("La Liga standings incomplete")
        val mapped = rows.map { row ->
            val team = team(row["team"]) ?: throw IOException("La Liga standings club identity missing")
            val values = listOf("position" to "leaguerank", "played" to "gamesplayed", "won" to "wins", "drawn" to "ties", "lost" to "losses",
                "goals_for" to "pointsfor", "goals_against" to "pointsagainst", "points" to "points").mapNotNull { (source, key) ->
                    row[source].stringOrNull()?.toIntOrNull()?.takeIf { it in (if (key == "points") -100 else 0)..1000 }?.let { key to "$it" }
                }.toMap().toMutableMap()
            val gf = values["pointsfor"]?.toIntOrNull(); val ga = values["pointsagainst"]?.toIntOrNull()
            if (gf != null && ga != null) values["pointdifferential"] = "${gf - ga}"
            StandingRow(team.id, team.name, team.abbreviation, team.logoUrl, listOfNotNull(values["wins"], values["ties"], values["losses"]).takeIf { it.size == 3 }?.joinToString("-") ?: "–", values)
        }
        if (mapped.map { it.teamId }.distinct().size != mapped.size) throw IOException("La Liga standings duplicate club")
        return StandingGroup("La Liga", mapped.sortedBy { it.values["leaguerank"]?.toIntOrNull() ?: Int.MAX_VALUE }, "LaLiga")
    }
    fun squad(rows: List<JsonElement>, teamId: String, now: Long): List<Athlete> {
        val id = rawId(teamId) ?: throw IOException("La Liga club identity required")
        if (rows.size > 256) throw IOException("La Liga squad exceeds bounds")
        return rows.filter { it["role"]["slug"].string() == "jugador" && NativeMlsMapping.boolean(it["current"]) != false }.map { row ->
            if (row["team"]["id"].stringOrNull()?.let { it != id } == true) throw IOException("La Liga squad club mismatch")
            val person = row["person"]; val opta = rawId(row["opta_id"].string(), true) ?: throw IOException("La Liga player Opta identity missing")
            val name = listOf(person["firstname"].string(), person["lastname"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank) ?: throw IOException("La Liga player name missing")
            val position = when (row["position"]["id"].string()) { "1" -> "Goalkeeper"; "2" -> "Defender"; "3" -> "Midfielder"; "4" -> "Forward"; else -> row["position"]["name"].string() }
            Athlete("laliga:$opta", name, row["shirt_number"].string(), position, positionName = position.takeIf(String::isNotBlank),
                details = person["country"]["id"].string(), age = person["date_of_birth"].stringOrNull()?.let { NativePremierLeagueMapping.age(it.take(10), now) },
                height = NativeMlsMapping.number(person["height"], 250.0)?.takeIf { it >= 50 }?.let { "${NativeMlsMapping.text(it)} cm" },
                weight = NativeMlsMapping.number(person["weight"], 300.0)?.takeIf { it >= 20 }?.let { "${NativeMlsMapping.text(it)} kg" },
                birthPlace = person["place_of_birth"].stringOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 })
        }.distinctBy { it.id }
    }
    fun playerStatistics(raw: JsonElement, optaId: String): Pair<Map<String, String>, Map<String, String>>? {
        require(rawId(optaId, true) == optaId)
        val rows = raw["player_stats"] as? JsonArray ?: throw IOException("La Liga player statistics missing")
        if (rows.size > 100) throw IOException("La Liga player statistics page exceeds bounds")
        val matches = rows.filter { it["opta_id"].string() == optaId }
        if (matches.size > 1) throw IOException("La Liga player statistics ambiguous")
        val row = matches.singleOrNull() ?: return null
        val stats = row["stats"] as? JsonArray ?: throw IOException("La Liga player metrics missing")
        if (stats.size > 512) throw IOException("La Liga player metrics exceeds bounds")
        val byKey = stats.mapNotNull { stat -> val key = stat["name"].stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,100}")) } ?: return@mapNotNull null
            NativeMlsMapping.reportedNumber(stat["stat"])?.let { key to NativeMlsMapping.text(it) } }.toMap()
        return byKey.mapKeys { NativeMlsDetails.humanize(it.key) } to byKey.filterKeys { it in setOf("goals", "goal_assists", "appearances", "clean_sheets") }.mapKeys { NativeMlsDetails.humanize(it.key) }
    }
    data class Round(val id: String, val week: Int, val name: String?, val at: Long?)
    fun rounds(raw: JsonElement): List<Round> {
        val rounds = raw["rounds"] as? JsonArray ?: throw IOException("La Liga rounds missing")
        if (rounds.size > 32) throw IOException("La Liga rounds exceeds bounds")
        val weeks = rounds.flatMap { round -> (round["gameweeks"] as? JsonArray) ?: throw IOException("La Liga gameweeks missing") }
        if (weeks.size > 100) throw IOException("La Liga gameweeks exceeds bounds")
        return weeks.map { week -> Round(rawId(week["id"].string()) ?: throw IOException("La Liga round identity missing"),
            week["week"].string().toIntOrNull()?.takeIf { it in 1..100 } ?: throw IOException("La Liga round number missing"), week["name"].stringOrNull(), week["date"].stringOrNull()?.let(::iso)) }.distinctBy { it.id }.sortedBy { it.week }
    }
}
