package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** NBAProvider/WNBAProvider discovery and named Stats tables; no cross-league ID or host substitution. */
class NativeBasketballProvider(private val client: NativeBasketballClient,
    private val now: () -> Long = ::nowMillis,
    private val details: (suspend (Match) -> GameCentre?)? = null,
    private val cachedMatch: (suspend (String) -> Match?)? = null) : SportsProvider {
    private val config = client.config
    override val league = config.league
    override val providerId = config.name
    override val capabilities = buildSet { addAll(setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams, SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players)); if (details != null) add(SportsCapability.GameDetails) }
    private val teamLock = Mutex()
    private data class TeamDirectory(val season: String, val at: Long, val teams: List<Team>)
    private var cachedTeams: TeamDirectory? = null
    private fun games(raw: List<JsonElement>): List<Match> {
        val result = raw.mapNotNull { NativeBasketballMapping.game(it, config) }.associateBy { it.id }.values.sortedBy { it.startMillis }
        if (raw.isNotEmpty() && result.isEmpty()) throw IOException("${config.name} returned no identifiable games")
        return result
    }
    override suspend fun scores(date: Long?) = games(client.scoreboard(date ?: now()))
    override suspend fun schedule(start: Long, days: Int): List<Match> {
        val first = config.day(start); val last = config.day(Dates.addDays(start, days.coerceIn(1, 366) - 1))
        return games(client.schedule(config.season(start))).filter { config.day(it.startMillis) in first..last }
    }
    override suspend fun teams(): List<Team> = teamLock.withLock {
        val season = config.season(now())
        cachedTeams?.takeIf { it.season == season && now() - it.at in 0 until 86_400_000 }?.let { return@withLock it.teams }
        val rows = client.schedule().flatMap { listOfNotNull(NativeBasketballMapping.team(it["awayTeam"], config), NativeBasketballMapping.team(it["homeTeam"], config)) }
            .associateBy { it.id }.values.sortedBy { it.abbreviation }
        if (rows.isEmpty()) throw IOException("${config.name} has no announced team directory")
        cachedTeams = TeamDirectory(season, now(), rows); rows
    }
    override suspend fun standings(): List<StandingGroup> = standingsRows(NativeBasketballMapping.table(client.stats("leaguestandingsv3",
        mapOf("Season" to config.season(now()), "SeasonType" to "Regular Season", "SeasonYear" to ""), 900_000), "Standings"), config)
    override suspend fun roster(teamId: String): List<RosterGroup> {
        val id = config.rawId(teamId) ?: throw IOException("A ${config.name} team identity is required")
        val season = config.season(now())
        val rows = NativeBasketballMapping.table(client.stats("commonteamroster", mapOf("TeamID" to id, "Season" to season), 3_600_000), "CommonTeamRoster")
        if (rows.any { it["TeamID"].stringOrNull()?.let { value -> value != id } == true || it["SEASON"].stringOrNull()?.let { value -> value != season } == true }) throw IOException("${config.name} roster identity/season mismatch")
        val people = rows.mapNotNull { athlete(it, config) }.distinctBy { it.id }
        if (rows.isNotEmpty() && people.isEmpty()) throw IOException("${config.name} roster has no identifiable players")
        return people.groupBy { person -> when (person.position) { "G" -> "Guards"; "F" -> "Forwards"; "C" -> "Centers"; else -> "Other" } }
            .entries.sortedBy { it.key }.map { (group, people) -> RosterGroup(group, people) }
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = config.rawId(athlete.id) ?: throw IOException("A ${config.name} player identity is required")
        val rows = NativeBasketballMapping.table(client.stats("playercareerstats", mapOf("PlayerID" to id, "PerMode" to "PerGame"), 3_600_000), "SeasonTotalsRegularSeason")
        if (rows.any { it["PLAYER_ID"].stringOrNull()?.let { person -> person != id } == true }) throw IOException("${config.name} player statistics identity mismatch")
        val season = rows.mapNotNull { it["SEASON_ID"].stringOrNull() }.maxOrNull() ?: throw IOException("${config.name} has not reported a statistics season")
        val latest = rows.filter { it["SEASON_ID"].string() == season }
        val row = latest.firstOrNull { it["TEAM_ABBREVIATION"].string() == "TOT" } ?: latest.last()
        val stats = linkedMapOf<String, String>()
        row.obj.orEmpty().filterKeys { it !in setOf("PLAYER_ID", "TEAM_ID", "LEAGUE_ID", "SEASON_ID", "TEAM_ABBREVIATION") }.forEach { (key, value) ->
            value.stringOrNull()?.let { stats[STAT_LABELS[key] ?: key.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)] = it }
        }
        val headline = listOf("PTS", "REB", "AST", "FG_PCT").mapNotNull { key -> row[key].stringOrNull()?.let { STAT_LABELS.getValue(key) to it } }.toMap()
        return AthleteOverview(athlete.copy(headshotUrl = athlete.headshotUrl ?: headshot(id, config)), mapOf("Source" to config.name, "Statistics season" to season), stats,
            statlineLabel = "$season · Regular season · per game", headlineStats = headline)
    }
    override suspend fun gameCentre(match: Match): GameCentre = details?.invoke(match) ?: throw IOException("${config.name} game details are unavailable")
    suspend fun calendarGame(appId: String, date: Long? = null): Match? {
        val id = appId.takeIf { it.startsWith("${config.prefix}:") }?.removePrefix("${config.prefix}:")?.takeIf { it.matches(Regex("[0-9]{10}")) } ?: return null
        cachedMatch?.invoke(id)?.takeIf { it.id == appId && it.league == league }?.let { return it }
        try {
            val game = client.box(id)["game"]
            if (game["gameId"].string() == id) NativeBasketballMapping.game(game!!, config)?.let { return it }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { /* Existing known identity may still be in its native date slate. */ }
        if (date == null) return null
        return try { games(client.scoreboard(date)).singleOrNull { it.id == appId } }
        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
    }
    companion object {
        fun headshot(id: String, config: NativeBasketballLeague) = "https://cdn.nba.com/headshots/${config.prefix}/latest/260x190/$id.png"
        fun athlete(row: JsonElement, config: NativeBasketballLeague): Athlete? {
            val id = NativeBasketballMapping.id(row["PLAYER_ID"]) ?: return null
            val name = row["PLAYER"].stringOrNull() ?: return null
            return Athlete("${config.prefix}:$id", name, row["NUM"].string(), row["POSITION"].string(), headshot(id, config),
                positionName = row["POSITION"].stringOrNull(), age = row["AGE"].stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 0.0..120.0 }?.toInt(),
                height = row["HEIGHT"].stringOrNull(), weight = row["WEIGHT"].stringOrNull()?.let { "$it lb" }, college = row["SCHOOL"].stringOrNull(),
                experienceYears = row["EXP"].stringOrNull()?.toIntOrNull()?.takeIf { it >= 0 })
        }
        fun standingsRows(rows: List<JsonElement>, config: NativeBasketballLeague): List<StandingGroup> = rows.mapNotNull { row ->
            val id = NativeBasketballMapping.id(row["TeamID"]) ?: return@mapNotNull null
            val abbreviation = row["TeamAbbreviation"].string()
            val name = listOf(row["TeamCity"].string(), row["TeamName"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val stats = linkedMapOf<String, String>()
            for ((from, to) in listOf("WINS" to "wins", "LOSSES" to "losses", "WinPCT" to "winpercent", "ConferenceGamesBack" to "gamesbehind", "strCurrentStreak" to "streak", "PointsPG" to "pointsfor", "OppPointsPG" to "pointsagainst", "DiffPointsPG" to "pointdifferential", "DivisionRank" to "divisionrank", "PlayoffRank" to "leaguerank", "L10" to "lastten")) row[from].stringOrNull()?.let { stats[to] = it }
            val wins = stats["wins"]?.toIntOrNull(); val losses = stats["losses"]?.toIntOrNull()
            if (wins != null && losses != null) stats["gamesplayed"] = "${wins + losses}"
            row["Conference"].string(config.name) to StandingRow("${config.prefix}:$id", name, abbreviation, config.logo(abbreviation),
                if (wins != null && losses != null) "$wins-$losses" else "–", stats)
        }.groupBy({ it.first }, { it.second }).entries.sortedBy { it.key }.map { (name, values) ->
            val distinct = values.distinctBy { it.teamId }
            StandingGroup(name, if (distinct.all { (it.values["leaguerank"]?.toIntOrNull() ?: 0) > 0 }) distinct.sortedBy { it.values["leaguerank"]!!.toInt() } else distinct, config.name)
        }
        private val STAT_LABELS = mapOf("GP" to "Games played", "GS" to "Games started", "MIN" to "Minutes", "PTS" to "Points", "REB" to "Rebounds", "AST" to "Assists", "STL" to "Steals", "BLK" to "Blocks", "TOV" to "Turnovers", "PF" to "Personal fouls", "FG_PCT" to "Field goal fraction", "FG3_PCT" to "Three-point fraction", "FT_PCT" to "Free throw fraction", "FGM" to "Field goals made", "FGA" to "Field goals attempted", "FG3M" to "Three-pointers made", "FG3A" to "Three-pointers attempted", "FTM" to "Free throws made", "FTA" to "Free throws attempted", "OREB" to "Offensive rebounds", "DREB" to "Defensive rebounds", "PLAYER_AGE" to "Season age")
    }
}
