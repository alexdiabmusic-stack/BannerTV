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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** NHLProvider/NHLGameMapper port. Numeric NHL identities never become ESPN team/player IDs. */
class NativeHockeyProvider(
    private val http: SportsHttp,
    private val details: NativeHockey = NativeHockey(http),
    private val baseUrl: String = "https://api-web.nhle.com/v1",
    private val now: () -> Long = ::nowMillis,
) : SportsProvider {
    override val league = requireNotNull(League.find("hockey/nhl"))
    override val providerId = "NHL"
    override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams,
        SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players, SportsCapability.GameDetails)
    private val gate = Semaphore(3)
    @Volatile private var retryAt = 0L
    private val knownTeams = SportsMemo(TEAM_ABBREVIATIONS)
    private data class ScheduleEntry(val at: Long, val games: List<Match>)
    private val scheduleLock = SportsLock()
    private val schedules = LinkedHashMap<String, ScheduleEntry>()

    private suspend fun resource(path: String, ttl: Long): JsonElement {
        if (retryAt > now()) throw IOException("NHL request cooldown")
        return try {
            withTimeout(8_000) { gate.withPermit {
                if (retryAt > now()) throw IOException("NHL request cooldown")
                http.getJson("$baseUrl/$path", ttlMillis = ttl, retries = 0)
            } }
        } catch (_: TimeoutCancellationException) { throw IOException("NHL request timed out") }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) {
            if (error.code in setOf(403, 429, 503)) retryAt = now() + NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds)
            throw error
        }
    }

    private fun rememberTeams(raw: JsonElement) {
        val games = raw["games"].array + raw["gameWeek"].array.flatMap { it["games"].array }
        for (game in games) for (side in listOf("awayTeam", "homeTeam")) {
            val id = game[side]["id"].int(); val abbreviation = game[side]["abbrev"].string()
            if (id in 1..999 && abbreviation.matches(Regex("[A-Z]{2,4}"))) knownTeams[id] = abbreviation
        }
    }

    override suspend fun scores(date: Long?): List<Match> {
        val raw = resource("score/${date?.let(::day) ?: "now"}", 10_000)
        if (raw["games"] !is JsonArray) throw IOException("NHL score response has no games array")
        rememberTeams(raw)
        return raw["games"].array.mapNotNull(::game)
    }

    override suspend fun schedule(start: Long, days: Int): List<Match> {
        val key = "${day(start)}:${days.coerceIn(1, 366)}"
        val ttl = if (days > 30) 300_000L else 120_000L
        val cached = scheduleLock.locked { schedules[key]?.takeIf { now() - it.at in 0 until ttl } }
        val loaded = cached?.games ?: try {
            withTimeout(40_000) { coroutineScope {
                scheduleDays(start, days).map { date -> async {
                    val raw = resource("schedule/$date", 120_000)
                    if (raw["gameWeek"] !is JsonArray) throw IOException("NHL schedule response has no gameWeek array")
                    rememberTeams(raw)
                    raw["gameWeek"].array.flatMap { it["games"].array }.mapNotNull(::game)
                } }.awaitAll().flatten()
            } }.let { games ->
                scheduleWindow(games, start, days).also { result -> scheduleLock.locked {
                    schedules.remove(key); schedules[key] = ScheduleEntry(now(), result)
                    while (schedules.size > 4) schedules.remove(schedules.keys.first())
                } }
            }
        } catch (_: TimeoutCancellationException) { throw IOException("NHL schedule timed out") }
        // A schedule cache may contain pregame/final states. Today's short-lived discovery owns live scores.
        val end = Dates.addDays(Dates.startOfDay(start), days.coerceIn(1, 366))
        return if (now() in Dates.startOfDay(start) until end) {
            val live = scores(null)
            (loaded.associateBy { it.key } + live.associateBy { it.key }).values.sortedBy { it.startMillis }
        } else loaded
    }

    private suspend fun standingsRaw(): JsonElement = resource("standings/now", 900_000).also {
        if (it["standings"] !is JsonArray) throw IOException("NHL standings response has no standings array")
    }

    override suspend fun teams(): List<Team> = parseTeams(standingsRaw()).also { teams ->
        teams.forEach { team -> rawId(team.id)?.toIntOrNull()?.let { knownTeams[it] = team.abbreviation } }
    }
    override suspend fun standings(): List<StandingGroup> = parseStandings(standingsRaw())
    override suspend fun roster(teamId: String): List<RosterGroup> {
        val number = rawId(teamId)?.toIntOrNull() ?: throw IOException("An NHL team identity is required")
        val abbreviation = knownTeams[number] ?: throw IOException("NHL team abbreviation is unavailable")
        val raw = resource("roster/$abbreviation/current", 3_600_000)
        if (listOf("forwards", "defensemen", "goalies").none { raw[it] is JsonArray }) throw IOException("NHL roster response has no player arrays")
        return parseRoster(raw)
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = rawId(athlete.id) ?: throw IOException("An NHL player identity is required")
        val raw = resource("player/$id/landing", 300_000)
        if (raw["playerId"].string() != id) throw IOException("NHL player response identity mismatch")
        return parsePlayer(raw)
    }
    suspend fun calendarGame(id: String): Match? {
        val rawId = rawId(id)?.takeIf { it.matches(Regex("[0-9]{10}")) } ?: return null
        return try {
            val raw = resource("gamecenter/$rawId/landing", 12_000)
            game(raw)?.takeIf { it.nativeReference?.gameId == rawId } ?: details.cachedMatch(rawId)
        } catch (cancel: CancellationException) {
            if (cancel !is kotlinx.coroutines.TimeoutCancellationException) throw cancel
            details.cachedMatch(rawId)
        } catch (_: Exception) { details.cachedMatch(rawId) }
    }
    override suspend fun gameCentre(match: Match): GameCentre = details.load(match, null) ?: throw IOException("NHL game details unavailable")

    companion object {
        val TEAM_ABBREVIATIONS = mapOf(1 to "NJD", 2 to "NYI", 3 to "NYR", 4 to "PHI", 5 to "PIT", 6 to "BOS", 7 to "BUF", 8 to "MTL", 9 to "OTT", 10 to "TOR", 12 to "CAR", 13 to "FLA", 14 to "TBL", 15 to "WSH", 16 to "CHI", 17 to "DET", 18 to "NSH", 19 to "STL", 20 to "CGY", 21 to "COL", 22 to "EDM", 23 to "VAN", 24 to "ANA", 25 to "DAL", 26 to "LAK", 28 to "SJS", 29 to "CBJ", 30 to "MIN", 52 to "WPG", 53 to "ARI", 54 to "VGK", 55 to "SEA", 59 to "UTA")
        private val nhl = requireNotNull(League.find("hockey/nhl"))
        fun rawId(id: String): String? = id.removePrefix("nhl:").takeIf { id.startsWith("nhl:") && it.matches(Regex("[1-9][0-9]{0,9}")) }
        fun day(time: Long): String = Dates.format("yyyy-MM-dd", time, "America/New_York")
        private fun local(raw: JsonElement?): String = raw["default"].stringOrNull() ?: raw.string()
        private fun web(raw: String?): String? = raw?.takeIf { value -> safeWebURL(value) }
        private fun logo(raw: JsonElement?, abbreviation: String): String? = web(raw["logo"].stringOrNull()) ?: web(raw["teamLogo"].stringOrNull())
            ?: abbreviation.takeIf { it.matches(Regex("[A-Z]{2,4}")) }?.let { "https://assets.nhle.com/logos/nhl/svg/${it}_light.svg" }

        fun game(raw: JsonElement): Match? {
            val id = raw["id"].stringOrNull()?.takeIf { it.matches(Regex("[0-9]{10}")) } ?: return null
            val start = Dates.parseIso(raw["startTimeUTC"].stringOrNull()) ?: return null
            val status = raw["gameScheduleState"].string()
            val state = when {
                status in setOf("PPD", "CNCL") -> GameState.PRE
                raw["gameState"].string() in setOf("LIVE", "CRIT") -> GameState.LIVE
                raw["gameState"].string() in setOf("OFF", "FINAL") -> GameState.FINAL
                else -> GameState.PRE
            }
            val aScore = raw["awayTeam"]["score"].stringOrNull()?.toIntOrNull()
            val hScore = raw["homeTeam"]["score"].stringOrNull()?.toIntOrNull()
            fun side(key: String, score: Int?, other: Int?): TeamSide? {
                val row = raw[key]; val teamId = row["id"].stringOrNull()?.takeIf { it.toIntOrNull()?.let { it > 0 } == true } ?: return null
                val abbreviation = row["abbrev"].string().takeIf { it.matches(Regex("[A-Z]{2,4}")) } ?: return null
                val name = local(row["name"]).ifBlank { listOf(local(row["placeName"]), local(row["commonName"])).filter(String::isNotBlank).joinToString(" ") }.ifBlank { abbreviation }
                return TeamSide(name, local(row["commonName"]).ifBlank { name }, abbreviation, logo(row, abbreviation),
                    score?.toString()?.takeIf { state != GameState.PRE }, isWinner = state == GameState.FINAL && score != null && other != null && score > other, teamId = "nhl:$teamId")
            }
            val away = side("awayTeam", aScore, hScore) ?: return null; val home = side("homeTeam", hScore, aScore) ?: return null
            val period = NativeHockeyEvents.periodLabel(raw["periodDescriptor"])
            val extra = period.takeIf { it == "Shootout" || it.endsWith("OT") }
            val clock = raw["clock"]["timeRemaining"].stringOrNull()
            val intermission = raw["clock"]["inIntermission"].bool()
            val label = when {
                status == "PPD" -> "Postponed"; status == "CNCL" -> "Cancelled"; raw["gameState"].string() == "SUSP" -> "Suspended"
                state == GameState.FINAL -> "Final${extra?.let { " · $it" } ?: ""}"
                intermission && state == GameState.LIVE -> "Intermission · $period"
                state == GameState.LIVE -> listOfNotNull(clock, period.takeIf(String::isNotBlank)).joinToString(" · ")
                raw["gameState"].string() !in setOf("FUT", "PRE") -> "Status unavailable"
                else -> Dates.kickoff(start)
            }
            val timing = GameTiming(
                clock.takeUnless { intermission },
                raw["clock"]["secondsRemaining"].stringOrNull()?.toIntOrNull()?.takeUnless { intermission },
                raw["clock"]["running"].bool() && !intermission,
                raw["periodDescriptor"]["number"].stringOrNull()?.toIntOrNull(),
                period,
            )
            return Match("nhl:$id", nhl, start, "${away.displayName} at ${home.displayName}", "${away.abbreviation} @ ${home.abbreviation}", state, label, home, away,
                broadcasts = raw["tvBroadcasts"].array.mapNotNull { it["network"].stringOrNull() }.distinct(), venue = local(raw["venue"]).takeIf(String::isNotBlank),
                live = if (state == GameState.LIVE) LiveContext(timing.clock, timing.period?.toString(), timing = timing) else LiveContext(timing = timing),
                nativeReference = NativeGameReference("NHL", id, raw["season"].stringOrNull()?.toIntOrNull()))
        }

        fun scheduleDays(start: Long, days: Int): List<String> = (0 until days.coerceIn(1, 366) step 7).map { day(Dates.addDays(start, it)) }.distinct()
        fun scheduleWindow(games: List<Match>, start: Long, days: Int): List<Match> {
            val first = Dates.startOfDay(start); val end = Dates.addDays(first, days.coerceIn(1, 366))
            return games.filter { it.startMillis in first until end }.associateBy { it.key }.values.sortedBy { it.startMillis }
        }

        fun parseTeams(raw: JsonElement): List<Team> = raw["standings"].array.mapNotNull { row ->
            val abbreviation = local(row["teamAbbrev"]).takeIf { it.matches(Regex("[A-Z]{2,4}")) } ?: return@mapNotNull null
            val id = TEAM_ABBREVIATIONS.entries.firstOrNull { it.value == abbreviation }?.key ?: return@mapNotNull null
            Team("nhl:$id", local(row["teamName"]).ifBlank { abbreviation }, local(row["teamCommonName"]).ifBlank { abbreviation }, abbreviation, logo(row, abbreviation), nhl)
        }.distinctBy { it.id }.sortedBy { it.name }

        fun parseStandings(raw: JsonElement): List<StandingGroup> = raw["standings"].array.groupBy { local(it["divisionName"]).ifBlank { "NHL" } }.entries.sortedBy { it.key }.map { (division, entries) ->
            StandingGroup(division, entries.sortedBy { it["divisionSequence"].int(Int.MAX_VALUE) }.mapNotNull { row ->
                val abbreviation = local(row["teamAbbrev"]).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val id = TEAM_ABBREVIATIONS.entries.firstOrNull { it.value == abbreviation }?.key ?: return@mapNotNull null
                val values = listOf("wins", "losses", "otLosses", "winPctg", "points", "gamesPlayed", "goalDifferential", "goalFor", "goalAgainst", "streakCode", "streakCount").mapNotNull { key -> row[key].stringOrNull()?.let { key.lowercase() to it } }.toMap()
                StandingRow("nhl:$id", local(row["teamName"]).ifBlank { abbreviation }, abbreviation, logo(row, abbreviation),
                    listOf("wins", "losses", "otLosses").mapNotNull { row[it].stringOrNull() }.joinToString("-").ifBlank { "–" }, values)
            }, "NHL")
        }

        fun parseRoster(raw: JsonElement): List<RosterGroup> = listOf("forwards" to "Forwards", "defensemen" to "Defensemen", "goalies" to "Goalies").mapNotNull { (key, label) ->
            val players = raw[key].array.mapNotNull { row ->
                val id = row["id"].stringOrNull() ?: row["playerId"].stringOrNull() ?: return@mapNotNull null
                val name = listOf(local(row["firstName"]), local(row["lastName"])).filter(String::isNotBlank).joinToString(" ")
                if (id.toLongOrNull()?.let { it > 0 } != true || name.isBlank()) return@mapNotNull null
                Athlete("nhl:$id", name, row["sweaterNumber"].string(), row["positionCode"].string(), web(row["headshot"].stringOrNull()))
            }.distinctBy { it.id }.sortedBy { it.name }
            players.takeIf { it.isNotEmpty() }?.let { RosterGroup(label, it) }
        }

        fun parsePlayer(raw: JsonElement): AthleteOverview {
            val id = raw["playerId"].string()
            val name = listOf(local(raw["firstName"]), local(raw["lastName"])).filter(String::isNotBlank).joinToString(" ")
            val stats = raw["featuredStats"]["regularSeason"]["subSeason"]
            val labels = linkedMapOf("gamesPlayed" to "Games played", "goals" to "Goals", "assists" to "Assists", "points" to "Points", "plusMinus" to "+/−", "pim" to "Penalty minutes", "shots" to "Shots", "shootingPctg" to "Shooting %", "wins" to "Wins", "losses" to "Losses", "savePctg" to "Save %", "goalsAgainstAvg" to "Goals against average", "shutouts" to "Shutouts")
            val values = labels.mapNotNull { (key, label) -> stats[key].stringOrNull()?.let { label to NativeHockeyPresentation.statValue(key, it) } }.toMap()
            val bio = listOfNotNull(raw["featuredStats"]["season"].stringOrNull()?.let { "Season" to it }, raw["birthDate"].stringOrNull()?.let { "Born" to it },
                listOf(local(raw["birthCity"]), local(raw["birthStateProvince"]), local(raw["birthCountry"])).filter(String::isNotBlank).joinToString(", ").takeIf(String::isNotBlank)?.let { "Birthplace" to it },
                raw["shootsCatches"].stringOrNull()?.let { "Shoots / catches" to it }, raw["heightInInches"].stringOrNull()?.let { "Height (in)" to it }, raw["weightInPounds"].stringOrNull()?.let { "Weight (lb)" to it }, "Source" to "NHL").toMap()
            val goalie = raw["position"].string() == "G"
            val headline = (if (goalie) listOf("Wins", "Save %", "Goals against average", "Shutouts") else listOf("Goals", "Assists", "Points", "+/−")).mapNotNull { label -> values[label]?.let { label to it } }.toMap()
            return AthleteOverview(Athlete("nhl:$id", name, raw["sweaterNumber"].string(), raw["position"].string(), web(raw["headshot"].stringOrNull())), bio, values,
                statlineLabel = raw["featuredStats"]["season"].stringOrNull(), headlineStats = headline)
        }
    }
}
