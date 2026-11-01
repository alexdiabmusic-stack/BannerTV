package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** MLBProvider / MLBEndpoint port. Public native identities never masquerade as ESPN IDs.
 * Long schedules use bounded month requests and skip live linescore hydration; detail remains on demand. */
class NativeBaseballProvider(private val http: SportsHttp, private val details: NativeBaseball = NativeBaseball(http),
    private val baseUrl: String = "https://statsapi.mlb.com", private val now: () -> Long = ::nowMillis) : SportsProvider {
    override val league = requireNotNull(League.find("baseball/mlb"))
    override val providerId = "MLB"
    override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams,
        SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players, SportsCapability.GameDetails)
    private val gate = Semaphore(2)
    @Volatile private var retryAt = 0L
    private data class SavedSchedule(val at: Long, val games: List<Match>)
    private val scheduleLock = SportsLock()
    private val schedules = LinkedHashMap<String, SavedSchedule>()

    private suspend fun resource(path: String, query: Map<String, String> = emptyMap(), ttl: Long): JsonElement {
        if (retryAt > now()) throw IOException("MLB request cooldown")
        val url = requestURL(baseUrl.trimEnd('/') + path, query)
        return try { withTimeout(10_000) { gate.withPermit {
            if (retryAt > now()) throw IOException("MLB request cooldown")
            http.getJson(url.toString(), ttlMillis = ttl, retries = 0)
        } } } catch (_: TimeoutCancellationException) { throw IOException("MLB request timed out") }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) { if (error.code in setOf(403, 429, 503)) retryAt = now() + NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds); throw error }
    }

    override suspend fun scores(date: Long?): List<Match> {
        val root = resource("/api/v1/schedule", mapOf("sportId" to "1", "date" to day(date ?: now()), "hydrate" to "team,linescore,probablePitcher,broadcasts"), 10_000)
        if (root["dates"] !is JsonArray) throw IOException("MLB schedule response has no dates array")
        return matches(root)
    }

    override suspend fun schedule(start: Long, days: Int): List<Match> {
        val count = days.coerceIn(1, 366); val first = Dates.startOfDay(start); val end = Dates.addDays(first, count)
        val key = "$first:$count"
        val cached = scheduleLock.locked { schedules[key]?.takeIf { now() - it.at in 0 until 120_000 } }
        if (cached != null) return cached.games
        val games = try { withTimeout(40_000) { coroutineScope {
            (0 until count step 31).map { offset -> async {
                val lower = Dates.addDays(first, offset); val upper = Dates.addDays(first, minOf(offset + 30, count - 1))
                val root = resource("/api/v1/schedule", mapOf("sportId" to "1", "startDate" to day(lower), "endDate" to day(upper),
                    "hydrate" to if (count <= 7) "team,linescore,probablePitcher,broadcasts" else "team,broadcasts"), 120_000)
                if (root["dates"] !is JsonArray) throw IOException("MLB schedule response has no dates array")
                matches(root)
            } }.awaitAll().flatten()
        } } } catch (_: TimeoutCancellationException) { throw IOException("MLB schedule timed out") }
        val result = games.filter { it.startMillis in first until end }.associateBy { it.key }.values.sortedBy { it.startMillis }
        scheduleLock.locked { schedules.remove(key); schedules[key] = SavedSchedule(now(), result); while (schedules.size > 4) schedules.remove(schedules.keys.first()) }
        return result
    }

    override suspend fun teams(): List<Team> = resource("/api/v1/teams", mapOf("sportId" to "1"), 86_400_000).let { root ->
        if (root["teams"] !is JsonArray) throw IOException("MLB team response has no teams array")
        root["teams"].array.mapNotNull(::team).distinctBy { it.id }.sortedBy { it.name }
    }
    override suspend fun standings(): List<StandingGroup> {
        val leagues = resource("/api/v1/leagues", mapOf("sportId" to "1"), 86_400_000)["leagues"].array
            .filter { it["active"].bool() && it["sport"]["id"].int() == 1 }.mapNotNull { positiveId(it["id"]) }.distinct()
        if (leagues.isEmpty()) throw IOException("MLB active leagues unavailable")
        val year = Dates.format("yyyy", now()).toInt()
        return parseStandings(resource("/api/v1/standings", mapOf("leagueId" to leagues.joinToString(","), "season" to "$year", "standingsTypes" to "regularSeason", "hydrate" to "team,division"), 900_000))
    }
    override suspend fun roster(teamId: String): List<RosterGroup> {
        val id = rawId(teamId) ?: throw IOException("An MLB team identity is required")
        val root = resource("/api/v1/teams/$id/roster", mapOf("hydrate" to "person"), 3_600_000)
        if (root["roster"] !is JsonArray) throw IOException("MLB roster response has no roster array")
        return parseRoster(root)
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = rawId(athlete.id) ?: throw IOException("An MLB player identity is required")
        val person = resource("/api/v1/people/$id", mapOf("hydrate" to "stats(group=[hitting,pitching],type=[season])"), 3_600_000)["people"].array.singleOrNull { it["id"].string() == id }
            ?: throw IOException("MLB player response identity mismatch")
        return parsePlayer(person)
    }
    suspend fun calendarGame(id: String): Match? {
        val raw = rawId(id) ?: return null
        return try {
            val feed = resource("/api/v1.1/game/$raw/feed/live", ttl = 10_000)
            gameFromFeed(feed)?.takeIf { it.id == id } ?: details.cachedMatch(raw)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { details.cachedMatch(raw) }
    }
    override suspend fun gameCentre(match: Match) = details.load(match, null) ?: throw IOException("MLB game details unavailable")

    companion object {
        private val mlb = requireNotNull(League.find("baseball/mlb"))
        fun rawId(id: String): String? = id.removePrefix("mlb:").takeIf { id.startsWith("mlb:") && it.matches(Regex("[1-9][0-9]{0,9}")) }
        private fun positiveId(raw: JsonElement?): String? = raw.stringOrNull()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
        private fun day(time: Long) = Dates.format("yyyy-MM-dd", time, "America/New_York")
        private fun team(raw: JsonElement?): Team? {
            val id = positiveId(raw["id"]) ?: return null; val name = raw["name"].stringOrNull() ?: return null
            val abbreviation = raw["abbreviation"].stringOrNull() ?: raw["teamName"].string(name)
            return Team("mlb:$id", name, raw["teamName"].string(name), abbreviation, "https://www.mlbstatic.com/team-logos/share/$id.jpg", mlb)
        }
        fun matches(root: JsonElement) = root["dates"].array.flatMap { it["games"].array }.mapNotNull(::game).associateBy { it.id }.values.sortedBy { it.startMillis }
        fun gameFromFeed(root: JsonElement): Match? {
            val data = root["gameData"]; val line = root["liveData"]["linescore"]
            return game(buildJsonObject {
                root["gamePk"]?.let { put("gamePk", it) }; data["datetime"]["dateTime"]?.let { put("gameDate", it) }
                data["game"]["season"]?.let { put("season", it) }; data["status"]?.let { put("status", it) }
                data["venue"]?.let { put("venue", it) }; line?.let { put("linescore", it) }
                put("teams", buildJsonObject { for (side in listOf("away", "home")) put(side, buildJsonObject {
                    data["teams"][side]?.let { put("team", it) }; line["teams"][side]["runs"]?.let { put("score", it) }
                    data["teams"][side]["record"]["leagueRecord"]?.let { put("leagueRecord", it) }
                }) })
            })
        }
        fun game(raw: JsonElement): Match? {
            val id = positiveId(raw["gamePk"]) ?: return null; val start = Dates.parseIso(raw["gameDate"].string()) ?: return null
            val away = team(raw["teams"]["away"]["team"]) ?: return null; val home = team(raw["teams"]["home"]["team"]) ?: return null
            val detail = raw["status"]["detailedState"].string(); val status = detail.lowercase()
            val state = gameState(raw["status"])
            fun score(side: String) = raw["teams"][side]["score"].stringOrNull()?.takeIf { it.toIntOrNull()?.let { n -> n >= 0 } == true && state != GameState.PRE }
            val a = score("away"); val h = score("home")
            fun side(team: Team, key: String, score: String?, other: String?): TeamSide {
                val record = raw["teams"][key]["leagueRecord"]
                val wins = record["wins"].stringOrNull(); val losses = record["losses"].stringOrNull()
                return TeamSide(team.name, team.shortName, team.abbreviation, team.logoUrl, score,
                    record = if (wins != null && losses != null) "$wins-$losses" else null,
                    isWinner = state == GameState.FINAL && score?.toIntOrNull()?.let { n -> other?.toIntOrNull()?.let { n > it } } == true, teamId = team.id)
            }
            val line = raw["linescore"]; val inning = line["currentInning"].stringOrNull()
            val half = line["inningState"].stringOrNull()
            val label = if (state == GameState.LIVE && status == "in progress" && inning != null) listOfNotNull(half, inning).joinToString(" ") else detail.ifBlank { if (state == GameState.PRE) Dates.kickoff(start) else state.label }
            val situation = if (state != GameState.LIVE) emptyList() else buildList {
                line["outs"].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..3 }?.let { add("$it outs") }
                if (line["outs"].int(-1) != 3 && half?.lowercase() !in setOf("middle", "end")) {
                    val balls = line["balls"].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..3 }; val strikes = line["strikes"].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..2 }
                    if (balls != null && strikes != null) add("$balls–$strikes count")
                    val bases = listOf("first", "second", "third").filter { line["offense"][it]["id"].isPresent }
                    if (bases.isNotEmpty()) add(if (bases.size == 3) "Bases loaded" else "Runners on ${bases.joinToString(" and ")}")
                }
            }
            return Match("mlb:$id", mlb, start, "${away.name} at ${home.name}", "${away.abbreviation} @ ${home.abbreviation}", state, label,
                home = side(home, "home", h, a), away = side(away, "away", a, h), broadcasts = raw["broadcasts"].array.mapNotNull { it["name"].stringOrNull() }.distinct(),
                venue = raw["venue"]["name"].stringOrNull(), live = if (state == GameState.LIVE) LiveContext(period = listOfNotNull(half, inning).joinToString(" "), situation = situation) else LiveContext.EMPTY,
                nativeReference = NativeGameReference("MLB", id, raw["season"].stringOrNull()?.toIntOrNull()))
        }

        fun gameState(raw: JsonElement?): GameState {
            val status = raw["detailedState"].string().lowercase()
            return when {
                status.contains("postpon") || status.contains("cancel") || status in setOf("scheduled", "pregame", "pre-game", "warmup") -> GameState.PRE
                status.contains("suspend") || status.contains("delay") -> GameState.LIVE
                status in setOf("final", "game over", "completed early") || raw["abstractGameState"].string() == "Final" -> GameState.FINAL
                status in setOf("in progress", "manager challenge", "umpire review") || raw["abstractGameState"].string() == "Live" -> GameState.LIVE
                else -> GameState.PRE
            }
        }

        fun parseStandings(root: JsonElement): List<StandingGroup> {
            if (root["records"] !is JsonArray) throw IOException("MLB standings response has no records array")
            return root["records"].array.mapNotNull { group ->
                val rows = group["teamRecords"].array.mapNotNull { row ->
                    val team = team(row["team"]) ?: return@mapNotNull null
                    val values = linkedMapOf<String, String>()
                    for ((rawKey, key) in mapOf("wins" to "wins", "losses" to "losses", "winningPercentage" to "winpercent", "divisionGamesBack" to "gamesbehind", "gamesPlayed" to "gamesplayed", "runsScored" to "pointsfor", "runsAllowed" to "pointsagainst", "runDifferential" to "differential", "divisionRank" to "divisionrank", "leagueRank" to "leaguerank", "wildCardRank" to "wildcardrank", "wildCardGamesBack" to "wildcardgamesback")) row[rawKey].stringOrNull()?.let { values[key] = it }
                    row["streak"]["streakCode"].stringOrNull()?.let { values["streak"] = it }
                    row["records"]["splitRecords"].array.firstOrNull { it["type"].string() == "lastTen" }?.let { split ->
                        val w = split["wins"].stringOrNull(); val l = split["losses"].stringOrNull(); if (w != null && l != null) values["lastten"] = "$w-$l"
                    }
                    StandingRow(team.id, team.name, team.abbreviation, team.logoUrl, listOfNotNull(values["wins"], values["losses"]).joinToString("-").ifBlank { "–" }, values)
                }
                rows.takeIf(List<StandingRow>::isNotEmpty)?.let { StandingGroup(group["division"]["name"].stringOrNull() ?: group["league"]["name"].string("MLB"), it, "MLB") }
            }
        }
        fun parseRoster(root: JsonElement): List<RosterGroup> = root["roster"].array.mapNotNull { row ->
            person(row["person"])?.copy(jersey = row["jerseyNumber"].string(), position = row["position"]["abbreviation"].string(), positionName = row["position"]["name"].stringOrNull())
        }.distinctBy { it.id }.groupBy { if (it.position == "P") "Pitchers" else "Position players" }.entries.sortedBy { it.key }.map { (name, players) -> RosterGroup(name, players.sortedBy { it.name }) }
        private fun person(raw: JsonElement?): Athlete? {
            val id = positiveId(raw["id"]) ?: return null; val name = raw["fullName"].stringOrNull() ?: return null
            return Athlete("mlb:$id", name, raw["primaryNumber"].string(), raw["primaryPosition"]["abbreviation"].string(),
                "https://img.mlbstatic.com/mlb-photos/image/upload/w_96,q_auto:good/v1/people/$id/headshot/67/current",
                positionName = raw["primaryPosition"]["name"].stringOrNull(), age = raw["currentAge"].stringOrNull()?.toIntOrNull()?.takeIf { it > 0 },
                height = raw["height"].stringOrNull(), weight = raw["weight"].stringOrNull()?.let { "$it lb" },
                birthPlace = listOf(raw["birthCity"].string(), raw["birthStateProvince"].string(), raw["birthCountry"].string()).filter(String::isNotBlank).joinToString(", ").takeIf(String::isNotBlank))
        }
        fun parsePlayer(raw: JsonElement): AthleteOverview {
            val player = person(raw) ?: throw IOException("MLB player missing identity")
            val values = linkedMapOf<String, String>(); val headline = linkedMapOf<String, String>()
            val seasons = mutableSetOf<String>()
            val labels = mapOf("gamesPlayed" to "Games played", "avg" to "AVG", "homeRuns" to "Home runs", "rbi" to "RBI", "atBats" to "At bats", "era" to "ERA", "inningsPitched" to "Innings pitched", "strikeOuts" to "Strikeouts")
            raw["stats"].array.forEach { group ->
                val name = group["group"]["displayName"].string("Statistics").replaceFirstChar(Char::uppercase)
                group["splits"].array.forEach { split ->
                    split["season"].stringOrNull()?.let(seasons::add)
                    split["stat"].obj.orEmpty().entries.sortedBy { it.key }.forEach { (key, value) -> value.stringOrNull()?.let { text ->
                        val title = labels[key] ?: key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase)
                        values["$name · $title"] = text
                        if (key in setOf("avg", "homeRuns", "rbi", "era", "inningsPitched", "strikeOuts", "wins") && headline.size < 4) headline[title] = text
                    } }
                }
            }
            val bio = listOfNotNull(raw["birthDate"].stringOrNull()?.let { "Born" to it }, raw["batSide"]["description"].stringOrNull()?.let { "Bats" to it }, raw["pitchHand"]["description"].stringOrNull()?.let { "Throws" to it }, raw["mlbDebutDate"].stringOrNull()?.let { "MLB debut" to it }, "Source" to "MLB").toMap()
            return AthleteOverview(player, bio, values, statlineLabel = seasons.singleOrNull()?.let { "$it Season" } ?: "Reported season statistics", headlineStats = headline)
        }
    }
}
