package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class SleeperAccount(val id: String, val username: String, val display: String)
@Serializable data class SleeperLeague(val id: String, val name: String, val season: String, val status: String, val slots: List<String>, val scoring: Map<String, Double>)
@Serializable data class SleeperTeam(val id: Int, val owner: String?, val name: String, val wins: Int?, val losses: Int?, val ties: Int?, val pointsFor: Double?, val pointsAgainst: Double?, val players: List<String>, val starters: List<String>, val reserve: Set<String>)
@Serializable data class SleeperMatchSide(val roster: Int, val points: Double?, val starters: List<String>, val players: List<String>, val playerPoints: Map<String, Double>)
@Serializable data class SleeperSnapshot(val league: SleeperLeague, val week: Int, val teams: List<SleeperTeam>, val me: Int?, val home: SleeperMatchSide?, val opponent: SleeperMatchSide?, val updated: Long)
@Serializable data class SleeperPlayer(val id: String, val name: String, val team: String?, val position: String?, val injury: String?, val espn: String?)

/** Same public read-only NFL integration as iOS: no password, writes, draft claims or API secret. */
class SleeperFantasyApi(private val http: SportsHttp, private val base: String = "https://api.sleeper.app/v1") {
    private suspend fun get(path: String, ttl: Long = 60000) = http.getJson("$base/$path", ttlMillis = ttl, retries = 0)
    suspend fun account(identifier: String): SleeperAccount {
        val clean = identifier.trim(); require(clean.isNotEmpty() && clean.length <= 100) { "Enter your Sleeper username or user ID." }
        val raw = get("user/" + encodeComponent(clean), 300000)
        val id = raw["user_id"].stringOrNull() ?: throw IllegalArgumentException("Sleeper user not found.")
        return SleeperAccount(id, raw["username"].string(clean), raw["display_name"].stringOrNull() ?: raw["username"].string(clean))
    }
    suspend fun season(): Pair<String, Int> { val raw = get("state/nfl"); return (raw["league_season"].stringOrNull() ?: raw["season"].string()) to (raw["display_week"] ?: raw["week"]).int().coerceAtLeast(1) }
    suspend fun leagues(account: SleeperAccount, year: String): List<SleeperLeague> {
        require(account.id.matches(Regex("[A-Za-z0-9_-]+")) && year.matches(Regex("\\d{4}")))
        return get("user/${account.id}/leagues/nfl/$year").array.mapNotNull(::league)
    }
    suspend fun load(account: SleeperAccount, selected: SleeperLeague, week: Int): SleeperSnapshot = coroutineScope {
        require(selected.id.matches(Regex("[A-Za-z0-9_-]+")) && week in 1..30)
        val meta = async { get("league/${selected.id}") }; val users = async { get("league/${selected.id}/users") }; val rosters = async { get("league/${selected.id}/rosters") }; val matches = async { get("league/${selected.id}/matchups/$week") }
        val current = league(meta.await())?.takeIf { it.id == selected.id } ?: throw IllegalArgumentException("Sleeper returned a different league.")
        snapshot(account, current, week, users.await(), rosters.await(), matches.await())
    }
    companion object {
        fun league(raw: JsonElement): SleeperLeague? { val id = raw["league_id"].stringOrNull() ?: return null; return SleeperLeague(id, raw["name"].string("Sleeper league"), raw["season"].string(), raw["status"].string(), raw["roster_positions"].array.map { it.string() }, raw["scoring_settings"].obj.orEmpty().mapNotNull { (key, value) -> value.stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { key to it } }.toMap()) }
        fun snapshot(account: SleeperAccount, league: SleeperLeague, week: Int, users: JsonElement, rosters: JsonElement, matches: JsonElement, now: Long = nowMillis()): SleeperSnapshot {
            val names = users.array.associate { raw -> raw["user_id"].string() to (raw["metadata"]["team_name"].stringOrNull() ?: raw["metadata"]["teamName"].stringOrNull() ?: raw["display_name"].stringOrNull() ?: raw["username"].string("Sleeper team")) }
            fun number(raw: JsonElement?) = raw.stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() }
            fun ids(raw: JsonElement?) = raw.array.map { it.string() }.filter { it.isNotBlank() && it != "0" }
            val teams = rosters.array.mapNotNull { raw -> val id = raw["roster_id"].string().toIntOrNull() ?: return@mapNotNull null; val owner = raw["owner_id"].stringOrNull(); val s = raw["settings"]
                SleeperTeam(id, owner, names[owner] ?: "Team $id", number(s["wins"])?.toInt(), number(s["losses"])?.toInt(), number(s["ties"])?.toInt(), number(s["fpts"])?.plus((number(s["fpts_decimal"]) ?: 0.0) / 100), number(s["fpts_against"])?.plus((number(s["fpts_against_decimal"]) ?: 0.0) / 100), ids(raw["players"]), raw["starters"].array.map { it.string() }, ids(raw["reserve"]).toSet())
            }.sortedWith(compareByDescending<SleeperTeam> { it.wins ?: -1 }.thenByDescending { it.pointsFor ?: -1.0 })
            val me = teams.firstOrNull { it.owner == account.id }?.id
            val rawMe = matches.array.firstOrNull { it["roster_id"].int(-1) == me }
            val matchupId = rawMe["matchup_id"].stringOrNull()
            val opponent = matches.array.firstOrNull { it["roster_id"].int(-1) != me && matchupId != null && it["matchup_id"].stringOrNull() == matchupId }
            fun side(raw: JsonElement?): SleeperMatchSide? { val id = raw["roster_id"].string().toIntOrNull() ?: return null; return SleeperMatchSide(id, number(raw["custom_points"]) ?: number(raw["points"]), ids(raw["starters"]), ids(raw["players"]), raw["players_points"].obj.orEmpty().mapNotNull { (key, value) -> number(value)?.let { key to it } }.toMap()) }
            return SleeperSnapshot(league, week, teams, me, side(rawMe), side(opponent), now)
        }
    }
}
