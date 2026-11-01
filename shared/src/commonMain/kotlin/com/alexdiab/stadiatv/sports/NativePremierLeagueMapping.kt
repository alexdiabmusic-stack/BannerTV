package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*

/** SDP strings retain an explicit epl: namespace; legacy ESPN/Opta/FPL numbers are never interchangeable. */
object NativePremierLeagueMapping {
    val league = League.find("soccer/eng.1")!!
    private val london = "Europe/London"
    fun id(raw: kotlinx.serialization.json.JsonElement?) = raw.stringOrNull()?.takeIf { it.matches(Regex("[0-9]{1,20}")) && it.any { c -> c != '0' } }
    fun rawId(value: String) = value.takeIf { it.startsWith("epl:") }?.removePrefix("epl:")?.takeIf { id(kotlinx.serialization.json.JsonPrimitive(it)) != null }
    fun day(at: Long) = SportsTime.format("yyyy-MM-dd", at, london)
    fun addDays(at: Long, days: Int) = SportsTime.addLocalDays(at, days, london)
    fun season(at: Long) = day(at).let { it.take(4).toInt() - if (it.substring(5, 7).toInt() < 7) 1 else 0 }
    fun reportedSeasons(raw: kotlinx.serialization.json.JsonElement) = listOfNotNull(raw["season"].stringOrNull(), raw["seasonId"].stringOrNull(), raw["seasonInfo"]["id"].stringOrNull())
    fun statText(raw: kotlinx.serialization.json.JsonElement?): String? {
        val value = raw.stringOrNull()?.takeIf { it.length <= 128 } ?: return null
        val number = value.toDoubleOrNull() ?: return value
        if (!number.isFinite()) return null
        return plainNumber(value)
    }
    fun age(birth: String?, at: Long): Int? {
        if (birth == null || !birth.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return null
        if (SportsTime.parseLocal(birth, "yyyy-MM-dd", london) == null) return null
        val today = day(at)
        return (today.take(4).toInt() - birth.take(4).toInt() - if (today.drop(5) < birth.drop(5)) 1 else 0).takeIf { it in 0..120 }
    }
    fun kickoff(raw: String): Long? {
        return SportsTime.parseLocal(raw, "yyyy-MM-dd HH:mm:ss", london)?.takeIf { it in 0..7_289_654_400_000L }
    }
    fun logo(id: String) = "https://resources.premierleague.com/premierleague25/badges/$id.png"
    fun team(raw: kotlinx.serialization.json.JsonElement?): Team? {
        val id = id(raw["id"]) ?: return null
        val name = raw["name"].stringOrNull()?.takeIf(String::isNotBlank) ?: return null
        return Team("epl:$id", name, raw["shortName"].string(name), raw["abbr"].string(), logo(id), league)
    }
    data class Status(val state: GameState, val label: String, val minute: String? = null)
    fun status(period: String, clock: String): Status {
        val normalized = period.lowercase().replace("_", "").replace(" ", "")
        val minute = clock.toIntOrNull()?.takeIf { it in 0..300 }
        fun running(base: Int) = minute?.let { if (it > base) "$base+${it - base}′" else "$it′" }
        return when {
            "postpon" in normalized -> Status(GameState.PRE, "Postponed")
            "cancel" in normalized -> Status(GameState.PRE, "Cancelled")
            "abandon" in normalized -> Status(GameState.PRE, "Abandoned")
            "suspend" in normalized -> Status(GameState.LIVE, "Suspended")
            "delay" in normalized -> Status(GameState.PRE, "Delayed")
            normalized in setOf("fulltime", "postmatch", "ft", "f") -> Status(GameState.FINAL, "Full time")
            normalized == "firsthalf" -> Status(GameState.LIVE, running(45) ?: "First half", running(45))
            normalized == "secondhalf" -> Status(GameState.LIVE, running(90) ?: "Second half", running(90))
            normalized == "halftime" -> Status(GameState.LIVE, "Half-time")
            normalized in setOf("prematch", "p", "ns", "") -> Status(GameState.PRE, "Upcoming")
            // EPL league play has no observed ET/shootout match-state contract. Preserve unknown text.
            else -> Status(GameState.PRE, period)
        }
    }
    fun game(raw: kotlinx.serialization.json.JsonElement): Match? {
        val id = id(raw["matchId"]) ?: return null
        if (raw["competitionId"].stringOrNull()?.let { it != "8" } == true) return null
        val start = kickoff(raw["kickoff"].string()) ?: return null
        val home = team(raw["homeTeam"]) ?: return null; val away = team(raw["awayTeam"]) ?: return null
        if (home.id == away.id) return null
        val status = status(raw["period"].string(), raw["clock"].string())
        fun score(side: String) = raw[side]["score"].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..100 }
        val h = score("homeTeam"); val a = score("awayTeam")
        fun side(team: Team, score: Int?, other: Int?) = TeamSide(team.name, team.shortName, team.abbreviation, team.logoUrl,
            score?.takeIf { status.state != GameState.PRE }?.toString(), isWinner = status.state == GameState.FINAL && score != null && other != null && score > other, teamId = team.id)
        val cards = listOf("homeTeam" to home, "awayTeam" to away).mapNotNull { (key, team) -> raw[key]["redCards"].stringOrNull()?.toIntOrNull()?.takeIf { it in 1..11 }?.let { "${team.abbreviation.ifBlank { team.shortName }}: $it red ${if (it == 1) "card" else "cards"}" } }
        return Match("epl:$id", league, start, "${home.name} vs ${away.name}", "${home.abbreviation} v ${away.abbreviation}", status.state, status.label,
            side(home, h, a), side(away, a, h), venue = raw["ground"].stringOrNull(),
            live = if (status.state == GameState.LIVE) LiveContext(status.minute, raw["period"].stringOrNull(), cards) else LiveContext.EMPTY,
            nativeReference = NativeGameReference("EPL", id, reportedSeasons(raw).firstOrNull()?.toIntOrNull() ?: season(start)))
    }
}
