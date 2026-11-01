package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlin.math.abs

/** NFL Shield, including one bounded token refresh, week resolution and GSIS participant-stat mapping. */
class NativeFootball(private val http: SportsHttp, private val baseUrl: String = "https://api.nfl.com", private val now: () -> Long = ::nowMillis,
    snapshots: NativeFootballSnapshotCache? = null) {
    private val cooldown = SportsMemo<String, Long>()
    private val tokenLock = Mutex()
    private var accessToken: String? = null
    private var expiresAt = 0L
    private data class PlayerNames(val season: Int, val at: Long, val values: Map<String, String>)
    private var playerNames: PlayerNames? = null
    private val playersLock = Mutex()
    private val loader = NativeFootballLoader(now, snapshots,
        rich = { week -> get(weeklyPath(week, true), 0) },
        summaries = { week -> get("/football/v2/stats/live/game-summaries?season=${week.season}&seasonType=${week.type.name}&week=${week.number}&limit=1000", 0) },
        names = ::playerNames)
    private val deviceId = kotlin.uuid.Uuid.random().toString()
    private val headers = mapOf("Accept" to "application/json", "X-Domain-Id" to "100", "Origin" to "https://www.nfl.com", "Referer" to "https://www.nfl.com/", "User-Agent" to NFLWebConfiguration.userAgent,
        "Cache-Control" to "no-store") // Match the iOS ephemeral client: bearer request metadata must not enter HTTP disk cache.

    private suspend fun token(rejected: String? = null): String = tokenLock.withLock {
        if (accessToken != null && expiresAt > now() && (rejected == null || rejected != accessToken)) return@withLock accessToken!!
        val root = parseJson(http.postForm("$baseUrl/identity/v3/token", NFLWebConfiguration.fields(deviceId), headers))
        val value = root["accessToken"].stringOrNull() ?: throw IOException("NFL authentication is unavailable")
        accessToken = value; expiresAt = now() + 240_000
        value
    }

    private suspend fun get(path: String, ttl: Long): JsonElement = withTimeoutOrNull(10_000) {
        if ((cooldown[path] ?: 0) > now()) throw IOException("NFL resource is cooling down; retry later")
        try {
            val first = token()
            val limit = (if (path.contains("weekly-game-details")) 16 else if (path.contains("/rosters")) 8 else 4) * 1024L * 1024
            try { http.getJson("$baseUrl$path", headers + ("Authorization" to "Bearer $first"), ttlMillis = ttl, retries = 0, maxResponseBytes = limit) }
            catch (error: HttpStatusException) {
                if (error.code != 401 && error.code != 403) throw error
                val refreshed = token(rejected = first)
                http.getJson("$baseUrl$path", headers + ("Authorization" to "Bearer $refreshed"), ttlMillis = ttl, retries = 0, maxResponseBytes = limit)
            }
        } catch (error: HttpStatusException) { cooldown[path] = now() + NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds); throw error }
    } ?: throw IOException("NFL request timed out")

    internal suspend fun weekPayload(dateMillis: Long): JsonElement {
        val date = Dates.format("yyyy-MM-dd", dateMillis, "America/New_York")
        return get("/football/v2/weeks/date/$date", 3_600_000)
    }
    suspend fun weekFor(dateMillis: Long): NflWeek = NflCalendar.week(weekPayload(dateMillis)) ?: throw IOException("NFL week is unavailable")
    internal suspend fun resource(path: String, ttl: Long): JsonElement = get(path, ttl)
    private suspend fun playerNames(season: Int): Map<String, String> = playersLock.withLock {
        val old = playerNames?.takeIf { it.season == season }
        old?.takeIf { now() - it.at in 0 until 14_400_000 }?.let { return@withLock it.values }
        try {
            val raw = withTimeout(4_000) { get("/football/v2/rosters?season=$season&limit=1000", 14_400_000) }
            if (raw["rosters"] !is JsonArray) return@withLock old?.values.orEmpty()
            val people = raw["rosters"].array.flatMap { it["persons"].array }
            if (people.size > 8192) return@withLock old?.values.orEmpty()
            val names = people.mapNotNull { person ->
                val id = person["id"].stringOrNull(); val name = person["displayName"].stringOrNull()
                if (id == null || name == null) null else id to name
            }.toMap()
            playerNames = PlayerNames(season, now(), names); names
        } catch (_: TimeoutCancellationException) { old?.values.orEmpty() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { old?.values.orEmpty() }
    }
    suspend fun weeks(season: Int, type: NflSeasonType, force: Boolean = false): List<NflWeekChoice> {
        require(season in 2000..2100)
        val path = "/football/v2/weeks/season/$season/seasonType/${type.name}"
        if (force) http.invalidate("$baseUrl$path")
        return NflCalendar.choices(get(path, 3_600_000), season, type)
    }
    internal fun weeklyPath(week: NflWeek, drives: Boolean): String = "/football/v2/experience/weekly-game-details?season=${week.season}&type=${week.type.name}&week=${week.number}&includeDriveChart=$drives&includeReplays=false&includeStandings=false&includeTaggedVideos=false"
    suspend fun schedule(week: NflWeek, force: Boolean = false): List<Match> {
        require(week.valid)
        val path = weeklyPath(week, false)
        if (force) http.invalidate("$baseUrl$path")
        return NflCalendar.matches(get(path, 60_000), week)
    }
    suspend fun calendarGame(appId: String, dateMillis: Long?): Match? {
        if (!appId.matches(Regex("nfl:[A-Za-z0-9_-]{1,128}"))) return null
        loader.cachedMatch(appId.removePrefix("nfl:"))?.let { return it }
        return try {
            if (dateMillis == null || dateMillis !in 0..4_102_444_800_000) loader.cachedMatch(appId.removePrefix("nfl:"))
            else schedule(weekFor(dateMillis)).singleOrNull { it.id == appId } ?: loader.cachedMatch(appId.removePrefix("nfl:"))
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { loader.cachedMatch(appId.removePrefix("nfl:")) }
    }

    suspend fun cachedGame(match: Match): GameCentre? = loader.cachedDetails(match)

    suspend fun load(match: Match, previous: GameCentre?, force: Boolean = false): GameCentre? {
        if (match.nativeReference != null && match.nativeReference.provider != "NFL") return null
        val reference = match.nativeReference?.takeIf { it.provider == "NFL" }
        val week = reference?.let { ref -> NflSeasonType.entries.firstOrNull { it.name == ref.seasonType }?.let { NflWeek(ref.season ?: 0, it, ref.week ?: 0) }?.takeIf { it.valid } } ?: weekFor(match.startMillis)
        return loader.load(match, week, reference?.gameId, previous, force)
    }

    companion object {
        private fun normalized(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
        fun teamMatches(raw: JsonElement?, side: TeamSide): Boolean {
            if (side.teamId?.startsWith("nfl:") == true && side.teamId.removePrefix("nfl:") != raw["id"].string()) return false
            return raw["fullName"].stringOrNull()?.let { normalized(it) == normalized(side.displayName) } == true ||
                raw["abbreviation"].stringOrNull()?.let { TeamIdentity.sameAbbreviation("football/nfl", it, side.abbreviation) } == true
        }

        fun field(text: String?, possession: String?, ownAbbreviation: String?, away: String, home: String, down: Int?, distance: Int?, goal: Boolean): FootballField? {
            if (text.isNullOrBlank()) return null
            val parts = text.trim().split(Regex("\\s+")); val yard = parts.lastOrNull()?.toIntOrNull()?.takeIf { it in 0..50 }
            val side = parts.firstOrNull().takeIf { parts.size > 1 }
            val fromOwn = if (yard == null || ownAbbreviation == null) null else if (side == ownAbbreviation) yard.toFloat() else if (side == away || side == home) (100 - yard).toFloat() else if (side == null && yard == 50) 50f else null
            return FootballField(possession, text, fromOwn, distance?.takeIf { it >= 0 }, down?.takeIf { it in 1..4 }, goal, ownAbbreviation)
        }

        fun parse(raw: JsonElement, match: Match, requested: Long, players: Map<String, String> = emptyMap()): GameCentre? {
            val id = raw["id"].stringOrNull() ?: return null
            if (match.nativeReference?.takeIf { it.provider == "NFL" }?.gameId?.let { it != id } == true) return null
            val summary = raw["summary"]
            if (summary["gameId"].stringOrNull()?.let { it != id } == true || raw.path("driveChart", "gameId").stringOrNull()?.let { it != id } == true) return null
            if (!teamMatches(raw["awayTeam"], match.away) || !teamMatches(raw["homeTeam"], match.home)) return null
            val awayId = raw.path("awayTeam", "id").string(); val homeId = raw.path("homeTeam", "id").string()
            if (summary.path("awayTeam", "teamId").stringOrNull()?.let { it != awayId } == true || summary.path("homeTeam", "teamId").stringOrNull()?.let { it != homeId } == true) return null
            val homePossession = summary.path("homeTeam", "hasPossession").bool(); val awayPossession = summary.path("awayTeam", "hasPossession").bool()
            val possessionId = if (homePossession) match.home.teamId else if (awayPossession) match.away.teamId else null
            val nativeAway = raw.path("awayTeam", "abbreviation").string(match.away.abbreviation); val nativeHome = raw.path("homeTeam", "abbreviation").string(match.home.abbreviation)
            val field = field(summary["yardLine"].stringOrNull(), possessionId, if (homePossession) nativeHome else if (awayPossession) nativeAway else null, nativeAway, nativeHome, summary["down"].stringOrNull()?.toIntOrNull(), summary["distance"].stringOrNull()?.toIntOrNull(), summary["isGoalToGo"].bool())
            val phase = summary["phase"].stringOrNull() ?: raw["status"].string()
            val (state, status) = NflCalendar.phase(phase, summary["quarter"].stringOrNull())
            val rawDrives = raw.path("driveChart", "drives").array
            val rawPlays = raw.path("driveChart", "plays").array
            val chronological = NativeFootballPlays.accepted(rawPlays)
            val plays = NativeFootballPlays.map(chronological, rawDrives, id, match, awayId, homeId, players)
            val drives = rawDrives.mapNotNull { drive -> val sequence = drive["sequence"].string().toIntOrNull() ?: return@mapNotNull null; GameDrive("nfl:$id:drive:$sequence", when (drive["teamId"].string()) { awayId -> match.away.abbreviation; homeId -> match.home.abbreviation; else -> "" }, drive["endedDescription"].string(), listOfNotNull(drive["startedYardLine"].stringOrNull(), drive["endedYardLine"].stringOrNull()).joinToString(" → "), drive["plays"].int(), drive["yardsGainedNet"].string(), drive["timeOfPossession"].string(), FootballDrive(sequence, drive["startedQuarter"].string().toIntOrNull(), drive["endedQuarter"].string().toIntOrNull(), drive["startedClock"].stringOrNull(), drive["endedClock"].stringOrNull(), drive["endedWithScore"].bool(), drive["plays"].string().toIntOrNull())) }.associateBy { it.id }.values.sortedBy { it.footballDrive?.sequence }
            fun reportedScore(key: String, fallback: String?): String? = if (state == GameState.PRE) null else
                (summary.path(key, "score", "total").stringOrNull() ?: fallback)?.toIntOrNull()?.takeIf { it >= 0 }?.toString()
            val awayScore = reportedScore("awayTeam", match.away.score)
            val homeScore = reportedScore("homeTeam", match.home.score)
            fun winner(one: String?, other: String?) = state == GameState.FINAL && one?.toIntOrNull()?.let { n -> other?.toIntOrNull()?.let { n > it } } == true
            val nativeMatch = match.copy(state = state, statusDetail = status, away = match.away.copy(score = awayScore, isWinner = winner(awayScore, homeScore)), home = match.home.copy(score = homeScore, isWinner = winner(homeScore, awayScore)), live = if (state != GameState.LIVE) LiveContext.EMPTY else LiveContext(summary["clock"].stringOrNull(), summary["quarter"].stringOrNull(), listOfNotNull(field?.yardLine, field?.down?.let { down -> val distance = if (field.goalToGo) "GOAL" else field.yardsToFirst?.toString(); distance?.let { "$down & $it" } })))
            val statRecords = chronological.flatMap { play -> play["stats"].array.distinctBy { it["playStatId"].stringOrNull() ?: it.toString() } }
            val box = listOf(awayId to match.away, homeId to match.home).mapNotNull { (teamId, side) -> playerBox(statRecords.filter { it["teamId"].string() == teamId }, players, side) }
            val comparisons = listOf(Triple("First downs", setOf(3, 4, 5), false), Triple("Rushing yards", setOf(10, 11, 12, 13), true), Triple("Net passing yards", setOf(15, 16, 20), true), Triple("Total yards", setOf(10, 11, 12, 13, 15, 16, 20), true), Triple("Penalties", setOf(93), false), Triple("Penalty yards", setOf(93), true)).map { (label, codes, yards) -> fun total(team: String): String { val records = statRecords.filter { it["teamId"].string() == team && it["statType"].int() in codes }; return if (!yards) "${records.size}" else if (records.any { !it["yards"].isPresent }) "–" else "${records.sumOf { it["yards"].int() }}" }; StatComparison(label, total(awayId), total(homeId)) }.takeIf { statRecords.isNotEmpty() }.orEmpty()
            val loaded = raw.path("driveChart", "plays") is JsonArray
            val football = FootballDetail(if (state == GameState.LIVE) field else null, raw["season"].stringOrNull()?.toIntOrNull(), raw["seasonType"].stringOrNull(), raw["week"].stringOrNull()?.toIntOrNull(), summary.path("awayTeam", "timeouts", "remaining").string().toIntOrNull(), summary.path("homeTeam", "timeouts", "remaining").string().toIntOrNull(), loaded, NativeFootballPlays.currentDrive(drives, state == GameState.LIVE, status))
            return GameCentre(lineScore = if (state == GameState.PRE) null else NativeFootballPlays.lineScore(raw, match), plays = plays, playsSource = if (loaded) "NFL Shield play-by-play" else null, drives = drives, boxScore = box, teamStats = comparisons, football = football, liveMatch = nativeMatch, providerStamp = ProviderStamp("NFL", id, requested, sequence = summary["offset"].int(-1), final = state == GameState.FINAL), gameInfo = listOfNotNull(raw.path("venue", "name").stringOrNull()?.let { "Venue" to it }, summary["weather"].stringOrNull()?.let { "Weather" to it }))
        }

        private fun playerBox(records: List<JsonElement>, names: Map<String, String>, team: TeamSide): TeamBox? {
            val people = records.groupBy { it["personId"].string() }.filterKeys(String::isNotBlank)
            val columns = listOf(
                Triple("Passing", setOf(14, 15, 16, 19), listOf("C/ATT", "YDS", "TD", "INT")), Triple("Rushing", setOf(10, 11, 12, 13), listOf("CAR", "YDS", "TD")), Triple("Receiving", setOf(21, 22, 23, 24), listOf("REC", "YDS", "TD")), Triple("Defense", setOf(25, 26, 79, 80, 82, 83, 84), listOf("TKL", "AST", "SACK", "INT")), Triple("Kicking", setOf(69, 70, 71, 72, 73, 74), listOf("FG", "XP")), Triple("Punting", setOf(29, 31, 32), listOf("PUNTS", "YDS")))
            val groups = columns.mapNotNull { (group, relevant, labels) ->
                val rows = people.mapNotNull { (id, all) ->
                    val stats = all.filter { it["statType"].int() in relevant }; if (stats.isEmpty()) return@mapNotNull null
                    fun count(vararg codes: Int) = stats.count { it["statType"].int() in codes }.toString()
                    fun yards(vararg codes: Int): String { val subset = stats.filter { it["statType"].int() in codes }; return if (subset.any { !it["yards"].isPresent }) "–" else "${subset.sumOf { it["yards"].int() }}" }
                    val values = when (group) {
                        "Passing" -> listOf("${count(15, 16)}/${count(14, 15, 16, 19)}", yards(15, 16), count(16), count(19))
                        "Rushing" -> listOf(count(10, 11), yards(10, 11, 12, 13), count(11, 13))
                        "Receiving" -> listOf(count(21, 22), yards(21, 22, 23, 24), count(22, 24))
                        "Defense" -> listOf(count(79, 80), count(82), "${stats.count { it["statType"].int() == 83 } + .5 * stats.count { it["statType"].int() == 84 }}", count(25, 26))
                        "Kicking" -> listOf("${count(70)}/${count(69, 70, 71)}", "${count(72)}/${count(72, 73, 74)}")
                        else -> listOf(count(29, 31, 32), yards(29, 31, 32))
                    }
                    BoxRow(names[id] ?: all.firstOrNull()?.get("gsisPlayerName").string("Unknown player"), "", values, athleteId = "nfl:$id")
                }
                if (rows.isEmpty()) null else BoxGroup(group, labels, rows, emptyList())
            }
            return if (groups.isEmpty()) null else TeamBox(team.displayName, team.abbreviation, groups)
        }

        fun reduce(previous: GameCentre?, incoming: GameCentre): GameCentre {
            if (previous?.providerStamp?.provider != "NFL") return incoming
            val a = previous.providerStamp; val b = incoming.providerStamp ?: return previous
            if (a.gameId != b.gameId || b.requestedAt < a.requestedAt || b.sequence < a.sequence || a.final && !b.final) return previous
            return if (incoming.football?.playsLoaded != true) incoming.copy(plays = previous.plays, playsSource = previous.playsSource, drives = previous.drives, boxScore = previous.boxScore, teamStats = previous.teamStats,
                football = incoming.football?.copy(playsLoaded = previous.football?.playsLoaded == true, currentDriveId = NativeFootballPlays.currentDrive(previous.drives, incoming.liveMatch?.state == GameState.LIVE, incoming.liveMatch?.statusDetail))) else incoming
        }
    }
}
