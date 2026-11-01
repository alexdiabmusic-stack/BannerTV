package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** NFLProvider port, sharing the existing anonymous Shield client/token refresh and native calendar. */
class NativeFootballProvider(private val client: NativeFootball, private val now: () -> Long = ::nowMillis) : SportsProvider {
    override val league = requireNotNull(League.find("football/nfl"))
    override val providerId = "NFL"
    override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams,
        SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players, SportsCapability.Injuries, SportsCapability.GameDetails)
    private val gate = Semaphore(2)
    private val metadataLock = Mutex(); private val rosterLock = Mutex()
    private data class Teams(val season: Int, val at: Long, val rows: Map<String, JsonElement>)
    private data class Rosters(val season: Int, val at: Long, val rows: JsonElement)
    private var teamCache: Teams? = null; private var rosterCache: Rosters? = null
    private suspend fun get(path: String, ttl: Long) = gate.withPermit { client.resource(path, ttl) }
    private fun resource(name: String, season: Int, week: NflWeek? = null, cursor: String? = null): String {
        require(name in setOf("teams/history", "rosters", "standings", "injuries"))
        return requestURL("/football/v2/$name", buildMap {
            put("season", "$season"); put("limit", "1000")
            week?.let { put("seasonType", it.type.name); put("week", "${it.number}") }
            cursor?.let { put("pageToken", it) }
        })
    }
    private suspend fun metadata(season: Int): Map<String, JsonElement> = metadataLock.withLock {
        teamCache?.takeIf { it.season == season && now() - it.at in 0 until 86_400_000 }?.let { return@withLock it.rows }
        val raw = get(resource("teams/history", season), 86_400_000)
        if (raw["teams"] !is JsonArray) throw IOException("NFL team history unavailable")
        val rows = raw["teams"].array.mapNotNull { team -> identity(team["id"])?.let { id ->
            id to JsonObject(team.obj.orEmpty().filterKeys { it in setOf("id", "fullName", "abbreviation", "currentLogo", "divisionFullName") })
        } }.toMap()
        teamCache = Teams(season, now(), rows); rows
    }
    private suspend fun rosters(season: Int): JsonElement = rosterLock.withLock {
        rosterCache?.takeIf { it.season == season && now() - it.at in 0 until 14_400_000 }?.let { return@withLock it.rows }
        val raw = get(resource("rosters", season), 14_400_000)
        if (raw["rosters"] !is JsonArray || raw["rosters"].array.sumOf { it["persons"].array.size } > 8192) throw IOException("NFL rosters unavailable or too large")
        // Retain only fields used by roster/profile UI, not every history/image/marketing field for the league.
        val compact = buildJsonObject { put("rosters", JsonArray(raw["rosters"].array.map { roster -> buildJsonObject {
            roster["team"]?.let { team -> put("team", buildJsonObject { team["id"]?.let { put("id", it) } }) }
            put("persons", JsonArray(roster["persons"].array.map { person -> JsonObject(person.obj.orEmpty().filterKeys {
                it in setOf("id", "displayName", "jerseyNumber", "position", "positionGroup", "height", "weight", "collegeNames", "nflExperience", "headshot")
            }) }))
        } })) }
        rosterCache = Rosters(season, now(), compact); compact
    }
    private suspend fun games(week: NflWeek): List<Match> = coroutineScope {
        val rows = async { get(client.weeklyPath(week, false), 30_000) }
        val teams = metadata(week.season)
        NflCalendar.matches(rows.await(), week, teams)
    }

    override suspend fun scores(date: Long?): List<Match> {
        val day = date ?: now(); val result = games(client.weekFor(day))
        return if (date == null) result else result.filter { it.startMillis in Dates.startOfDay(day) until Dates.addDays(Dates.startOfDay(day), 1) }
    }
    override suspend fun schedule(start: Long, days: Int): List<Match> = withTimeoutOrNull(40_000) {
        val lower = Dates.startOfDay(start); val upper = Dates.addDays(lower, days.coerceIn(1, 366))
        val weeks = linkedSetOf<NflWeek>(); var cursor = lower
        while (cursor < upper) {
            currentCoroutineContext().ensureActive()
            val raw = client.weekPayload(cursor); val week = NflCalendar.week(raw) ?: throw IOException("NFL date/week identity unavailable")
            weeks += week
            // dateEnd is an announced exclusive boundary, not a guessed seven-day season/week calculation.
            cursor = weekEnd(raw, cursor) ?: Dates.addDays(cursor, 1)
        }
        coroutineScope { weeks.map { week -> async { games(week) } }.awaitAll().flatten() }
            .filter { it.startMillis in lower until upper }.associateBy { it.id }.values.sortedBy { it.startMillis }
    } ?: throw IOException("NFL calendar timed out")

    override suspend fun teams(): List<Team> = metadata(client.weekFor(now()).season).values
        .filter { it["divisionFullName"].stringOrNull() != null }.mapNotNull { team(it) }.sortedBy { it.name }
    override suspend fun standings(): List<StandingGroup> {
        val week = client.weekFor(now())
        val raw = get(resource("standings", week.season, week), 900_000)
        return parseStandings(raw, metadata(week.season), week)
    }
    override suspend fun roster(teamId: String): List<RosterGroup> {
        val id = rawId(teamId) ?: throw IOException("An NFL team identity is required")
        val raw = rosters(client.weekFor(now()).season)
        val roster = raw["rosters"].array.singleOrNull { it["team"]["id"].string() == id } ?: throw IOException("NFL team roster unavailable")
        return parseRoster(roster)
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = rawId(athlete.id) ?: throw IOException("An NFL player identity is required")
        val week = client.weekFor(now())
        val raw = rosters(week.season)["rosters"].array.flatMap { it["persons"].array }.firstOrNull { it["id"].string() == id }
            ?: throw IOException("NFL player unavailable in this roster season")
        val player = person(raw) ?: throw IOException("NFL player identity unavailable")
        val stats = linkedMapOf<String, String>()
        for ((key, label) in listOf("position" to "Position", "jerseyNumber" to "Number", "nflExperience" to "Experience", "height" to "Height (in)", "weight" to "Weight (lb)")) raw[key].stringOrNull()?.let { stats[label] = it }
        return AthleteOverview(player, mapOf("Source" to "NFL", "Roster season" to "${week.season}"), stats, statlineLabel = "Player information")
    }
    override suspend fun injuries(): List<Injury> {
        val week = client.weekFor(now()); val teams = metadata(week.season)
        val rows = mutableListOf<JsonElement>(); val seen = mutableSetOf<String>(); var cursor: String? = null
        repeat(64) {
            val raw = get(resource("injuries", week.season, week, cursor), 900_000)
            if (raw["injuries"] !is JsonArray) throw IOException("NFL injuries unavailable")
            rows += raw["injuries"].array
            if (rows.size > 8192) throw IOException("NFL injury response exceeds the safe device limit")
            val next = raw["pagination"]["token"].stringOrNull()
            if (next == null) return parseInjuries(rows, teams)
            if (next.length > 4096 || !seen.add(next)) throw IOException("NFL injury pagination is inconsistent")
            cursor = next
        }
        throw IOException("NFL injury pagination exceeds the safe request limit")
    }
    override suspend fun gameCentre(match: Match) = client.load(match, null) ?: throw IOException("NFL game details unavailable")

    companion object {
        private val nfl = requireNotNull(League.find("football/nfl"))
        private fun identity(raw: JsonElement?) = raw.stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        fun rawId(id: String): String? = id.takeIf { it.startsWith("nfl:") }?.removePrefix("nfl:")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        fun team(raw: JsonElement?, metadata: JsonElement? = null): Team? {
            val id = identity(raw["id"]) ?: return null
            if (metadata["id"].stringOrNull()?.let { it != id } == true) return null
            val name = raw["fullName"].stringOrNull() ?: metadata["fullName"].stringOrNull() ?: return null
            val abbreviation = metadata["abbreviation"].stringOrNull() ?: raw["abbreviation"].stringOrNull() ?: return null
            return Team("nfl:$id", name, abbreviation, abbreviation, NflCalendar.image(raw["currentLogo"].stringOrNull() ?: metadata["currentLogo"].stringOrNull()), nfl)
        }
        fun person(raw: JsonElement?): Athlete? {
            val id = identity(raw["id"]) ?: return null; val name = raw["displayName"].stringOrNull() ?: return null
            val height = raw["height"].stringOrNull()?.toIntOrNull()?.takeIf { it > 0 }?.let { "${it / 12}′ ${it % 12}″" }
            return Athlete("nfl:$id", name, raw["jerseyNumber"].string(), raw["position"].string(), NflCalendar.image(raw["headshot"].stringOrNull()),
                positionName = raw["position"].stringOrNull(), height = height, weight = raw["weight"].stringOrNull()?.let { "$it lb" },
                college = raw["collegeNames"].array.mapNotNull { it.stringOrNull() }.joinToString(", ").takeIf(String::isNotBlank),
                experienceYears = raw["nflExperience"].stringOrNull()?.toIntOrNull()?.takeIf { it >= 0 })
        }
        fun parseRoster(raw: JsonElement): List<RosterGroup> {
            if (raw["persons"] !is JsonArray) throw IOException("NFL roster has no people array")
            return raw["persons"].array.groupBy { it["positionGroup"].string("Players") }.entries.sortedBy { it.key }.mapNotNull { (group, rows) ->
                rows.mapNotNull(::person).distinctBy { it.id }.takeIf(List<Athlete>::isNotEmpty)?.let { RosterGroup(group, it) }
            }
        }
        fun parseStandings(raw: JsonElement, teams: Map<String, JsonElement>, requested: NflWeek): List<StandingGroup> {
            if (raw["weeks"] !is JsonArray) throw IOException("NFL standings have no weeks array")
            return raw["weeks"].array.filter { week -> NflCalendar.week(week)?.let { it == requested } ?: listOf("season" to "${requested.season}", "seasonType" to requested.type.name, "week" to "${requested.number}").all { (key, value) -> week[key].stringOrNull()?.let { it == value } ?: true } }
                .flatMap { it["standings"].array }.mapNotNull { row ->
                    val id = identity(row["team"]["id"]) ?: return@mapNotNull null
                    val metadata = teams[id]; val team = team(row["team"], metadata) ?: return@mapNotNull null
                    val total = row["overall"]; val values = linkedMapOf<String, String>()
                    for ((key, label) in listOf("wins" to "wins", "losses" to "losses", "ties" to "ties", "winPct" to "winpercent", "games" to "gamesplayed")) total[key].stringOrNull()?.let { values[label] = it }
                    total["points"]["for"].stringOrNull()?.let { values["pointsfor"] = it }; total["points"]["against"].stringOrNull()?.let { values["pointsagainst"] = it }
                    val streak = total["streak"]; if (streak["type"].isPresent && streak["length"].isPresent) values["streak"] = streak["type"].string() + streak["length"].string()
                    row["division"]["rank"].stringOrNull()?.let { values["divisionrank"] = it }; row["conference"]["rank"].stringOrNull()?.let { values["leaguerank"] = it }
                    metadata["divisionFullName"].string("NFL") to StandingRow(team.id, team.name, team.abbreviation, team.logoUrl,
                        listOfNotNull(values["wins"], values["losses"], values["ties"]).joinToString("-").ifBlank { "–" }, values)
                }.groupBy({ it.first }, { it.second }).entries.sortedBy { it.key }.map { (name, rows) ->
                    val distinct = rows.distinctBy { it.teamId }
                    // Shield lists teams alphabetically. Its explicit division rank, not wins/strings/array index,
                    // carries the official tiebreaker order. Incomplete rank sets retain source order and unknown ranks.
                    val ordered = if (distinct.all { it.values["divisionrank"]?.toIntOrNull()?.let { rank -> rank > 0 } == true }) distinct.sortedBy { it.values["divisionrank"]!!.toInt() } else distinct
                    StandingGroup(name, ordered, "NFL")
                }
        }
        fun parseInjuries(rows: List<JsonElement>, teams: Map<String, JsonElement>): List<Injury> = rows.mapNotNull { row ->
            val player = person(row["person"]) ?: return@mapNotNull null
            val status = row["injuryStatus"].stringOrNull() ?: return@mapNotNull null
            Injury(player.copy(position = row["position"].string(player.position)), teams[row["team"]["id"].string()]["abbreviation"].string(),
                status.replaceFirstChar(Char::uppercase), row["injuries"].array.mapNotNull { it.stringOrNull() }.joinToString(", "), "", "NFL")
        }.distinctBy { it.athlete.id to it.team }

        fun weekEnd(raw: JsonElement, current: Long): Long? {
            fun date(key: String) = raw[key].stringOrNull()?.takeIf { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }?.let { text ->
                Dates.parseLocal(text, "yyyy-MM-dd", "America/New_York")
            }
            val begin = date("dateBegin") ?: return null; val end = date("dateEnd") ?: return null
            return end.takeIf { begin <= current && it > current && it - begin in 1..14L * 24 * 3_600_000 }
        }
    }
}
