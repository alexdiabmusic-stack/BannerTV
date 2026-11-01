package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Spatial enrichment only. Provider IDs are resolved by date AND both teams, never by ESPN's numeric ID. */
class NativeGameInsights(private val http: SportsHttp) {
    private val blockedUntil = SportsMemo<String, Long>()

    suspend fun shots(match: Match): Pair<String, List<GameShot>>? {
        if (match.league.path !in setOf("basketball/nba", "hockey/nhl") || match.state == GameState.PRE) return null
        if ((blockedUntil[match.league.path] ?: 0) > nowMillis()) return null
        return try {
            withTimeout(10_000) { if (match.league.group == SportGroup.Basketball) nba(match) else nhl(match) }
        } catch (_: TimeoutCancellationException) { blockedUntil[match.league.path] = nowMillis() + 120_000; null }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) { blockedUntil[match.league.path] = nowMillis() + cooldownMillis(error.code, error.retryAfterSeconds); null }
        catch (_: Exception) { blockedUntil[match.league.path] = nowMillis() + 120_000; null }
    }

    private suspend fun nba(match: Match): Pair<String, List<GameShot>>? {
        val schedule = http.getJson("https://cdn.nba.com/static/json/staticData/scheduleLeagueV2_1.json", ttlMillis = 6 * 3_600_000L, retries = 0)
        val games = schedule.path("leagueSchedule", "gameDates").array.flatMap { it["games"].array }
        val game = games.filter { raw ->
            sameTeams(raw.path("awayTeam", "teamTricode").string(), raw.path("homeTeam", "teamTricode").string(), match) &&
                sameTime(raw["gameDateTimeUTC"].string(), match.startMillis)
        }.singleOrNull() ?: return null
        val id = game["gameId"].string()
        if (!Regex("[0-9]{10}").matches(id)) return null
        val root = http.getJson("https://cdn.nba.com/static/json/liveData/playbyplay/playbyplay_$id.json", ttlMillis = 20_000, retries = 0)
        if (root.path("game", "gameId").string() != id) return null
        return "NBA live data" to nbaShots(root.path("game", "actions").array, match.home.abbreviation)
    }

    private suspend fun nhl(match: Match): Pair<String, List<GameShot>>? {
        if (match.nativeReference != null && !match.nativeReference.provider.equals("NHL", true)) return null
        val date = SportsTime.format("yyyy-MM-dd", match.startMillis, "America/New_York")
        val id = match.nativeReference?.gameId ?: run {
            val schedule = http.getJson("https://api-web.nhle.com/v1/schedule/$date", ttlMillis = 300_000, retries = 0)
            val game = schedule["gameWeek"].array.flatMap { it["games"].array }.filter { raw ->
                sameTeams(raw.path("awayTeam", "abbrev").string(), raw.path("homeTeam", "abbrev").string(), match) && sameTime(raw["startTimeUTC"].string(), match.startMillis)
            }.singleOrNull() ?: return null
            game["id"].string()
        }
        if (!Regex("[0-9]{10}").matches(id)) return null
        val root = http.getJson("https://api-web.nhle.com/v1/gamecenter/$id/play-by-play", ttlMillis = 20_000, retries = 0)
        if (root["id"].string() != id || !sameTeams(root.path("awayTeam", "abbrev").string(), root.path("homeTeam", "abbrev").string(), match)) return null
        val homeId = root.path("homeTeam", "id").string()
        val awayId = root.path("awayTeam", "id").string()
        val roster = root["rosterSpots"].array.associateBy { it["playerId"].string() }
        val points = root["plays"].array.mapNotNull { play ->
            val kind = NativeHockeyEvents.kind(play)
            if (kind !in setOf("goal", "shot-on-goal", "missed-shot", "blocked-shot")) return@mapNotNull null
            val detail = play["details"]
            val location = NativeHockeyEvents.location(play) ?: return@mapNotNull null
            val owner = detail["eventOwnerTeamId"].stringOrNull() ?: return@mapNotNull null
            if (owner !in setOf(awayId, homeId)) return@mapNotNull null
            val player = roster[(detail["scoringPlayerId"] ?: detail["shootingPlayerId"]).string()]
            val name = listOf(player.path("firstName", "default").string(), player.path("lastName", "default").string()).filter(String::isNotBlank).joinToString(" ")
            val eventId = play["eventId"].stringOrNull() ?: return@mapNotNull null
            GameShot("nhl:$id:$eventId", location.x, location.y, kind == "goal", owner == homeId, play.path("periodDescriptor", "number").int(), name, kind.replace('-', ' '))
        }.associateBy { it.id }.values.toList()
        return "NHL play-by-play" to points
    }

    companion object {
        fun cooldownMillis(code: Int, retryAfterSeconds: Long?): Long = maxOf(if (code == 403) 300_000L else 120_000L, (retryAfterSeconds ?: 0).coerceIn(0, Long.MAX_VALUE / 2_000) * 1_000)
        private fun sameTime(raw: String, expected: Long): Boolean = com.alexdiab.stadiatv.foundation.SportsTime.parseIso(raw)?.let { abs(it - expected) < 3 * 3_600_000L } ?: false
        private fun alias(value: String) = when (value.uppercase()) { "GS" -> "GSW"; "NY" -> "NYK"; "SA" -> "SAS"; "NO" -> "NOP"; "WSH" -> "WAS"; "PHO" -> "PHX"; "UTAH" -> "UTA"; "MON" -> "MTL"; "NJ" -> "NJD"; "TB" -> "TBL"; "LA" -> "LAK"; "SJ" -> "SJS"; else -> value.uppercase() }
        fun sameAbbreviation(first: String, second: String) = first.isNotBlank() && second.isNotBlank() && alias(first) == alias(second)
        fun sameTeams(away: String, home: String, match: Match) = away.isNotBlank() && home.isNotBlank() && alias(away) == alias(match.away.abbreviation) && alias(home) == alias(match.home.abbreviation)

        /** Exact iOS legacy conversion and sentinel/distance checks. Missing geometry remains missing. */
        fun nbaShots(actions: List<JsonElement>, homeAbbreviation: String): List<GameShot> = actions.mapNotNull { raw ->
            val type = raw["actionType"].string()
            if (type !in setOf("2pt", "3pt")) return@mapNotNull null
            val x = (raw["xLegacy"] ?: raw["x"]).double(Double.NaN) / 10
            val y = (raw["yLegacy"] ?: raw["y"]).double(Double.NaN) / 10
            val distance = raw["shotDistance"].double(Double.NaN)
            if (distance.isFinite() && distance < 0) return@mapNotNull null
            if (!x.isFinite() || !y.isFinite() || abs(x) > 30 || y !in -6.0..95.0) return@mapNotNull null
            if (x == 0.0 && y == 0.0 && (!distance.isFinite() || distance > 1)) return@mapNotNull null
            val computed = hypot(x, y)
            if (distance.isFinite() && abs(distance - computed) > maxOf(3.0, 0.15 * computed)) return@mapNotNull null
            val id = raw["actionNumber"].stringOrNull() ?: return@mapNotNull null
            val tricode = raw["teamTricode"].stringOrNull() ?: return@mapNotNull null
            GameShot("nba:$id", x.toFloat(), y.toFloat(), raw["shotResult"].string().equals("Made", true), alias(tricode) == alias(homeAbbreviation), raw["period"].int(), raw["playerName"].string(), raw["description"].string(),
                value = if (type == "3pt") 3 else 2, distanceFeet = (if (distance.isFinite()) distance else round(computed * 10) / 10).toFloat())
        }.associateBy { it.id }.values.toList()
    }
}
