package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.net.SportsHttp
import com.alexdiab.stadiatv.net.HttpStatusException
import com.alexdiab.stadiatv.net.array
import com.alexdiab.stadiatv.net.get
import com.alexdiab.stadiatv.net.int
import com.alexdiab.stadiatv.net.parseJson
import com.alexdiab.stadiatv.net.path
import com.alexdiab.stadiatv.net.string
import com.alexdiab.stadiatv.net.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** The cookies ESPN needs to show a private league. They identify the user's ESPN session; never log them. */
class EspnCookies(val espnS2: String, val swid: String) {
    val header: String get() = "espn_s2=$espnS2; SWID=$swid"
}

/** Reads a league from ESPN's fantasy API, the same endpoint and views the iOS app uses. */
class EspnFantasyApi(private val http: SportsHttp, private val baseUrl: String = BASE) {

    /** Everything one screen needs in a single round trip: settings, teams, rosters, scores and status. */
    suspend fun league(connection: FantasyConnection, cookies: EspnCookies?): JsonElement {
        val views = VIEWS.joinToString("&") { "view=$it" }
        val url = "$baseUrl/apis/v3/games/${connection.sportCode}/seasons/${connection.season}/segments/0/leagues/${connection.leagueId}?$views"
        val headers = buildMap {
            put("Accept", "application/json")
            cookies?.let { put("Cookie", it.header) }
        }
        val body = try {
            http.getString(url, headers = headers, ttlMillis = CACHE_MILLIS, retries = 1)
        } catch (error: HttpStatusException) {
            throw when (error.code) {
                401, 403 -> FantasyException.AuthRequired()
                404 -> FantasyException.LeagueNotFound()
                else -> error
            }
        }
        // ESPN answers a missing or hidden league with an HTML page or an auth marker rather than a status code.
        if (body.trimStart().startsWith("<")) throw FantasyException.BadResponse()
        if (body.take(4096).contains("AUTH_LEAGUE_NOT_VISIBLE")) throw FantasyException.AuthRequired()
        return parseJson(body)
    }

    private companion object {
        const val BASE = "https://lm-api-reads.fantasy.espn.com"
        val VIEWS = listOf("mSettings", "mTeam", "mRoster", "mMatchupScore", "mScoreboard", "mStatus")
        const val CACHE_MILLIS = 45_000L
    }
}

/** Turns ESPN's league document into the small model the screens draw. */
object EspnFantasyParser {

    fun snapshot(root: JsonElement, connection: FantasyConnection, nowMillis: Long): FantasySnapshot {
        val sport = connection.sport ?: FantasySport.Football
        val teams = teams(root)
        val myTeamId = connection.teamId
        return FantasySnapshot(
            leagueName = root.path("settings", "name").stringOrNull() ?: "ESPN Fantasy ${sport.label}",
            season = connection.season,
            myTeam = teams.firstOrNull { it.id == myTeamId },
            standings = teams.sortedWith(compareBy({ it.rank ?: Int.MAX_VALUE }, { -it.wins })),
            roster = myTeamId?.let { roster(root, it, sport) }.orEmpty(),
            matchup = myTeamId?.let { matchup(root, it, teams) },
            refreshedAtMillis = nowMillis,
        )
    }

    fun teams(root: JsonElement): List<FantasyTeam> = root["teams"].array.mapNotNull { team ->
        val id = team["id"].intOrNull() ?: return@mapNotNull null
        val overall = team.path("record", "overall")
        FantasyTeam(
            id = id,
            name = displayName(team, id),
            abbreviation = team["abbrev"].string(),
            wins = overall["wins"].int(),
            losses = overall["losses"].int(),
            ties = overall["ties"].int(),
            pointsFor = overall["pointsFor"].doubleOrNull(),
            pointsAgainst = overall["pointsAgainst"].doubleOrNull(),
            rank = overall["rankCalculatedFinal"].intOrNull()?.takeIf { it > 0 },
            owners = team["owners"].array.mapNotNull { it.stringOrNull() },
        )
    }

    /** The team whose owner list contains this SWID, so a private-league user doesn't have to look up their team id. */
    fun ownerTeamId(root: JsonElement, swid: String): Int? {
        val wanted = normalizeSwid(swid)
        return root["teams"].array.firstOrNull { team -> team["owners"].array.any { normalizeSwid(it.string()) == wanted } }["id"].intOrNull()
    }

    fun roster(root: JsonElement, teamId: Int, sport: FantasySport): List<FantasyPlayer> {
        val team = root["teams"].array.firstOrNull { it["id"].intOrNull() == teamId } ?: return emptyList()
        return team.path("roster", "entries").array.mapNotNull { entry ->
            val pool = entry["playerPoolEntry"]
            val player = pool["player"] ?: return@mapNotNull null
            val id = player["id"].stringOrNull() ?: return@mapNotNull null
            val slotId = entry["lineupSlotId"].intOrNull()
            FantasyPlayer(
                id = id,
                name = player["fullName"].stringOrNull() ?: "Player $id",
                slot = sport.slotLabel(slotId),
                kind = sport.slotKind(slotId),
                points = pool["appliedStatTotal"].doubleOrNull() ?: pool["totalPoints"].doubleOrNull(),
                injuryStatus = player["injuryStatus"].stringOrNull()?.takeUnless { it.equals("ACTIVE", ignoreCase = true) },
                proTeamId = player["proTeamId"].intOrNull(),
            )
        }.sortedBy { it.kind.ordinal } // starters, then bench, then reserve; ESPN's order within each
    }

    fun matchup(root: JsonElement, teamId: Int, teams: List<FantasyTeam> = teams(root)): FantasyMatchup? {
        val schedule = root["schedule"].array
        val period = root.path("status", "currentMatchupPeriod").intOrNull()
        val candidates = schedule.filter { it.path("home", "teamId").intOrNull() == teamId || it.path("away", "teamId").intOrNull() == teamId }
        val entry = candidates.firstOrNull { period != null && it["matchupPeriodId"].intOrNull() == period } ?: candidates.firstOrNull() ?: return null
        val homeIsMe = entry.path("home", "teamId").intOrNull() == teamId
        val mine = entry[if (homeIsMe) "home" else "away"]
        val theirs = entry[if (homeIsMe) "away" else "home"]
        fun side(value: JsonElement?): FantasySide? {
            val id = value["teamId"].intOrNull() ?: return null
            return FantasySide(id, teams.firstOrNull { it.id == id }?.name ?: "Team $id", value["totalPoints"].doubleOrNull() ?: value.path("cumulativeScore", "score").doubleOrNull() ?: 0.0)
        }
        return FantasyMatchup(
            week = entry["matchupPeriodId"].intOrNull() ?: period ?: 0,
            me = side(mine) ?: return null,
            opponent = side(theirs),
        )
    }

    private fun displayName(team: JsonElement, id: Int): String =
        team["name"].stringOrNull()
            ?: listOf(team["location"].string(), team["nickname"].string()).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Team $id" }

    private fun normalizeSwid(value: String) = value.trim().removePrefix("{").removeSuffix("}").uppercase()

    private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.doubleOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
}
