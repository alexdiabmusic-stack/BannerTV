package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.*

enum class NativeBasketballLeague(val prefix: String, val leagueId: String) {
    NBA("nba", "00"), WNBA("wnba", "10");
    val league get() = requireNotNull(League.find("basketball/$prefix"))
    val cdn get() = "cdn.$prefix.com"
    val stats get() = "stats.$prefix.com"
    fun season(at: Long): String {
        val month = SportsTime.format("MM", at, "America/New_York").toInt()
        val year = SportsTime.format("yyyy", at, "America/New_York").toInt()
        if (this == WNBA) return "$year"
        val first = if (month >= 8) year else year - 1
        return "$first-${((first + 1) % 100).toString().padStart(2, '0')}"
    }
    fun day(at: Long) = SportsTime.format("yyyy-MM-dd", at, "America/New_York")
    fun rawId(id: String) = id.takeIf { it.startsWith("$prefix:") }?.removePrefix("$prefix:")?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
    fun logo(abbreviation: String): String? {
        val input = abbreviation.trim().uppercase()
        val asset = if (this == WNBA) when (input) { "NYL" -> "NY"; "LVA" -> "LV"; "CON" -> "CONN"; "LA" -> "LAS"; else -> input }
            else when (input) { "GS" -> "GSW"; "NY" -> "NYK"; "SA" -> "SAS"; "NO" -> "NOP"; "WSH" -> "WAS"; "PHO" -> "PHX"; "UTAH" -> "UTA"; else -> input }
        // An asset inventory, NOT a whitelist of teams allowed into schedules or discovery.
        val bundled = if (this == NBA) NBA_ASSETS else WNBA_ASSETS
        return asset.takeIf { it in bundled }?.let { basketballAsset(prefix, it) }
    }
    private companion object {
        val NBA_ASSETS = "ATL BKN BOS CHA CHI CLE DAL DEN DET GSW HOU IND LAC LAL MEM MIA MIL MIN NOP NYK OKC ORL PHI PHX POR SAC SAS TOR UTA WAS".split(' ').toSet()
        val WNBA_ASSETS = "ATL CHI CONN DAL GSV IND LAS LV MIN NY PHX POR SEA TOR WAS".split(' ').toSet()
    }
}

/** NBAAPIClient/WNBALiveCDNClient routing. Stats never participates in WNBA live feeds or schedules. */
class NativeBasketballClient(val config: NativeBasketballLeague, private val http: SportsHttp,
    private val now: () -> Long = ::nowMillis,
    private val cdnBase: String = "https://${config.cdn}", private val statsBase: String = "https://${config.stats}") {
    private val gate = Semaphore(2)
    private val scheduleLock = Mutex()
    private data class Season(val label: String, val at: Long, val games: List<JsonElement>)
    private var retained: Season? = null
    private data class Block(val until: Long, val code: Int?, val retry: Long?)
    private val blocks = SportsMemo<Boolean, Block>()
    private suspend fun get(path: String, query: Map<String, String>, stats: Boolean, ttl: Long): JsonElement = gate.withPermit {
        blocks[stats]?.takeIf { it.until > now() }?.let { block ->
            throw block.code?.let { HttpStatusException(it, block.retry) } ?: IOException("${config.name} stats host is cooling down")
        }
        val url = requestURL(((if (stats) statsBase else cdnBase).trimEnd('/') + path), query)
        val headers = if (!stats) mapOf("Accept" to "application/json", "User-Agent" to "BannerTV/Android") else mapOf(
            "Accept" to "application/json, text/plain, */*", "Accept-Language" to "en-US,en;q=0.9", "Referer" to "https://www.${config.prefix}.com/",
            "Origin" to "https://www.${config.prefix}.com", "x-nba-stats-origin" to "stats", "x-nba-stats-token" to "true",
            "User-Agent" to "Mozilla/5.0 BannerTV/Android", "Cache-Control" to "no-store")
        try {
            withTimeoutOrNull(if (stats) 8000 else 10_000) {
                http.getJson(url, headers, ttl, retries = 0, maxResponseBytes = (if (path.contains("schedule", true)) 12 else 8) * 1024L * 1024)
            } ?: run { if (stats) blocks[true] = Block(now() + 120_000, null, null); throw IOException("${config.name} request timed out") }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) {
            if (error.code in setOf(403, 429)) blocks[stats] = Block(now() + maxOf(if (error.code == 403 && stats) 120_000 else 300_000,
                (error.retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000), error.code, error.retryAfterSeconds)
            throw error
        } catch (error: IOException) { if (stats && error !is ResponseTooLargeException) blocks[true] = Block(now() + 120_000, null, null); throw error }
    }
    suspend fun stats(name: String, query: Map<String, String>, ttl: Long): JsonElement {
        require(name in setOf("scheduleleaguev2", "leaguestandingsv3", "scoreboardv3", "playbyplayv3", "commonteamroster", "commonplayerinfo", "playercareerstats"))
        require(config == NativeBasketballLeague.NBA || name !in setOf("scheduleleaguev2", "scoreboardv3", "playbyplayv3"))
        return get("/stats/$name", query + ("LeagueID" to config.leagueId), true, ttl)
    }
    suspend fun schedule(season: String = config.season(now())): List<JsonElement> = scheduleLock.withLock {
        require(if (config == NativeBasketballLeague.NBA) season.matches(Regex("20[0-9]{2}-[0-9]{2}")) else season.matches(Regex("20[0-9]{2}")))
        retained?.takeIf { it.label == season && now() - it.at in 0 until 120_000 }?.let { return@withLock it.games }
        if (config == NativeBasketballLeague.WNBA && season != config.season(now())) throw IOException("WNBA's CDN does not serve a historical season")
        val raw = if (config == NativeBasketballLeague.NBA && season != config.season(now())) stats("scheduleleaguev2", mapOf("Season" to season), 120_000)
        else try { get("/static/json/staticData/${if (config == NativeBasketballLeague.NBA) "scheduleLeagueV2_1.json" else "scheduleLeagueV2.json"}", emptyMap(), false, 120_000) }
        catch (error: HttpStatusException) {
            if (config == NativeBasketballLeague.WNBA) throw error
            stats("scheduleleaguev2", mapOf("Season" to season), 120_000)
        }
        val body = raw["leagueSchedule"]
        if (body["gameDates"] !is JsonArray || body["seasonYear"].stringOrNull()?.let { it != season } == true) throw IOException("${config.name} schedule shape/season changed")
        val dates = body["gameDates"].array
        if (dates.size > 500 || dates.any { it["games"] !is JsonArray }) throw IOException("${config.name} schedule dates are inconsistent")
        val games = dates.flatMap { it["games"].array }
        if (games.size > 4096 || games.any { it !is JsonObject }) throw IOException("${config.name} season exceeds the safe game limit")
        // Retain one season's small schedule currency, not all marketing/venue/player trees.
        val compact = games.map { game -> JsonObject(game.jsonObject.filterKeys { it in GAME_FIELDS }.mapValues { (key, value) ->
            if (key in setOf("awayTeam", "homeTeam")) JsonObject(value.obj.orEmpty().filterKeys { it in TEAM_FIELDS }) else value
        }) }
        retained = Season(season, now(), compact); compact
    }
    suspend fun scoreboard(date: Long): List<JsonElement> {
        if (config.day(date) == config.day(now())) {
            val raw = try { get("/static/json/liveData/scoreboard/todaysScoreboard_${config.leagueId}.json", emptyMap(), false, 10_000) }
            catch (error: HttpStatusException) {
                if (config == NativeBasketballLeague.WNBA) throw error
                stats("scoreboardv3", mapOf("GameDate" to config.day(date), "DayOffset" to "0"), 10_000)
            }
            // "Today's" CDN file can remain on the last game day during gaps/offseason.
            // A successful old file is not proof of today's slate (or today's empty slate).
            val reportedDay = raw["scoreboard"]["gameDate"].stringOrNull()
            if (reportedDay != null && reportedDay != config.day(date))
                return gamesOnDay(schedule(config.season(date)), date)
            return scoreboardRows(raw)
        }
        if (config == NativeBasketballLeague.NBA && config.season(date) != config.season(now()))
            return scoreboardRows(stats("scoreboardv3", mapOf("GameDate" to config.day(date), "DayOffset" to "0"), 10_000))
        return gamesOnDay(schedule(config.season(date)), date)
    }
    private fun gamesOnDay(games: List<JsonElement>, date: Long): List<JsonElement> {
        if (games.any { NativeBasketballMapping.start(it) == null }) throw IOException("${config.name} schedule contains unresolved dates")
        return games.filter { config.day(NativeBasketballMapping.start(it)!!) == config.day(date) }
    }
    suspend fun box(id: String): JsonElement { require(id.matches(Regex("[0-9]{10}"))); return get("/static/json/liveData/boxscore/boxscore_$id.json", emptyMap(), false, 12_000) }
    data class PlayFeed(val game: JsonElement, val source: String)
    suspend fun plays(id: String): PlayFeed {
        require(id.matches(Regex("[0-9]{10}")))
        val raw = try { get("/static/json/liveData/playbyplay/playbyplay_$id.json", emptyMap(), false, 12_000) }
        catch (error: HttpStatusException) {
            if (config == NativeBasketballLeague.WNBA) throw error
            val rows = NativeBasketballMapping.table(stats("playbyplayv3", mapOf("GameID" to id, "StartPeriod" to "0", "EndPeriod" to "14"), 15_000), "PlayByPlay")
            if (rows.any { it["gameId"].stringOrNull()?.let { returned -> returned != id } == true }) throw IOException("${config.name} returned another game's plays")
            return PlayFeed(buildJsonObject { put("gameId", id); put("actions", JsonArray(rows)) }, "${config.name} Stats")
        }
        val game = raw["game"]
        if (game["gameId"].string() != id || game["actions"] !is JsonArray || game["actions"].array.size > 8192 ||
            game["actions"].array.any { it["gameId"].stringOrNull()?.let { returned -> returned != id } == true }) throw IOException("${config.name} play data identity/shape changed")
        return PlayFeed(game!!, "${config.name} CDN")
    }
    private fun scoreboardRows(raw: JsonElement): List<JsonElement> {
        val rows = raw["scoreboard"]["games"]
        if (rows !is JsonArray || rows.size > 128 || rows.any { it !is JsonObject }) throw IOException("${config.name} scoreboard shape changed")
        return rows
    }
    companion object {
        private val TEAM_FIELDS = setOf("teamId", "teamCity", "teamName", "teamTricode", "teamSlug", "score", "wins", "losses", "periods", "timeoutsRemaining", "inBonus")
        private val GAME_FIELDS = setOf("gameId", "gameCode", "gameTimeUTC", "gameDateTimeUTC", "gameDateUTC", "gameStatus", "gameStatusText", "period", "regulationPeriods", "gameClock", "awayTeam", "homeTeam", "arena", "nationalBroadcasters", "nationalOttBroadcasters", "homeTvBroadcasters", "awayTvBroadcasters", "gameLabel", "gameSubLabel", "gameSubtype", "seriesText", "seriesGameNumber")
    }
}
