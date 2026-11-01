package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Public accepted payloads only. League-qualified paths, atomic writes and bounded cold recovery. */
class NativeBasketballSnapshotCache(private val directory: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Surface(val at: Long, val game: JsonElement, val source: String? = null)
    data class Snapshot(val surfaces: Map<String, Surface> = emptyMap()) {
        val characters get() = surfaces.values.sumOf { it.game.toString().length }
        fun latestGame(): JsonElement? = surfaces.filterKeys { it in setOf("score", "box") }.values.maxByOrNull { it.at }?.game
        fun match(config: NativeBasketballLeague): Match? = latestGame()?.let { NativeBasketballMapping.game(it, config) }
        fun details(match: Match, config: NativeBasketballLeague): GameCentre? {
            var result: GameCentre? = null
            surfaces.filterKeys { it in setOf("score", "box") }.entries.sortedBy { it.value.at }.forEach { (name, surface) ->
                result = NativeBasketball.update(result, surface.game, null, match, surface.at, config, boxFresh = name == "box") ?: result
            }
            val game = latestGame() ?: return null
            surfaces["plays"]?.let { surface ->
                result = NativeBasketball.update(result, game, surface.game, match, maxOf(surface.at, result?.providerStamp?.requestedAt ?: 0), config,
                    scoreFresh = false, boxFresh = false, playsSource = surface.source) ?: result
            }
            return result?.let { it.copy(basketball = it.basketball?.copy(surfaceUpdatedAt = surfaces.mapValues { it.value.at }, savedSnapshot = true)) }
        }
        fun accept(game: JsonElement?, plays: NativeBasketballClient.PlayFeed?, full: Boolean, requested: Long, accepted: GameCentre): Snapshot {
            val fresh = surfaces.toMutableMap()
            val times = accepted.basketball?.surfaceUpdatedAt.orEmpty()
            if (game != null && times["score"] == requested) fresh["score"] = Surface(requested, compact(game, false))
            if (full && game != null && times["box"] == requested) fresh["box"] = Surface(requested, compact(game, true))
            if (plays != null && times["plays"] == requested) {
                // The CDN can return a partial window. Persist the same stable-ID union as the reducer.
                fun identity(row: JsonElement) = (row["actionId"] ?: row["actionNumber"] ?: row["orderNumber"]).stringOrNull()
                val combined = (surfaces["plays"]?.game?.get("actions").array + plays.game["actions"].array)
                    .filter { identity(it) != null }.associateBy { identity(it) }.values
                    .sortedWith(compareBy({ it["period"].int() }, { (it["orderNumber"] ?: it["actionNumber"]).double() }))
                if (combined.size <= 8192) fresh["plays"] = Surface(requested, JsonObject(plays.game.obj.orEmpty() + ("actions" to JsonArray(combined))), plays.source)
            }
            return Snapshot(fresh)
        }
        private fun compact(game: JsonElement, box: Boolean): JsonElement = JsonObject(game.obj.orEmpty().filterKeys { it in GAME_FIELDS }.mapValues { (key, value) ->
            if (key in setOf("awayTeam", "homeTeam")) JsonObject(value.obj.orEmpty().filterKeys { it in TEAM_FIELDS || box && it in setOf("players", "statistics") }) else value
        })
    }
    private val lock = Mutex()
    suspend fun load(config: NativeBasketballLeague, id: String): Snapshot? = withContext(sportsIO) { lock.withLock {
        if (!validId(id)) return@withLock null
        read(config, id)
    } }
    suspend fun save(config: NativeBasketballLeague, id: String, value: Snapshot) = withContext(sportsIO) { lock.withLock {
        if (!validId(id) || !valid(config, id, value)) return@withLock
        runCatching {
            val old = read(config, id)
            if (old != null && old.surfaces.values.maxOf { it.at } > value.surfaces.values.maxOf { it.at }) return@runCatching
            if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
            val body = buildJsonObject {
                put("version", 1); put("league", config.prefix); put("gameId", id)
                put("surfaces", buildJsonObject { value.surfaces.forEach { (name, surface) -> put(name, buildJsonObject {
                    put("at", surface.at); put("game", surface.game); surface.source?.let { put("source", it) }
                }) } })
            }.toString().encodeToByteArray()
            if (body.size > MAX_FILE_BYTES) return@runCatching
            val target = SportsFile(directory, "${config.prefix}-$id.json"); val pending = SportsFile(directory, "${config.prefix}-$id.pending")
            try {
                pending.writeSynced(body)
                if (!pending.renameTo(target)) return@runCatching
                var bytes = 0L
                directory.listFiles { file -> file.name.matches(Regex("(?:nba|wnba)-[0-9]{10}\\.json")) }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                    bytes += file.length(); if (index >= 12 || bytes > MAX_TOTAL_BYTES) file.delete()
                }
            } finally { pending.delete() }
        }
        Unit
    } }
    private fun read(config: NativeBasketballLeague, id: String): Snapshot? = runCatching {
        val file = SportsFile(directory, "${config.prefix}-$id.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES.toLong()) return@runCatching null
        val root = parseJson(file.readText())
        if (root["version"].int() != 1 || root["league"].string() != config.prefix || root["gameId"].string() != id) return@runCatching null
        val surfaces = root["surfaces"].obj?.mapValues { (_, raw) -> Surface(raw["at"].string().toLongOrNull() ?: return@runCatching null,
            raw["game"] ?: return@runCatching null, raw["source"].stringOrNull()) } ?: return@runCatching null
        Snapshot(surfaces).takeIf { valid(config, id, it) }
    }.getOrNull()
    private fun valid(config: NativeBasketballLeague, id: String, value: Snapshot): Boolean =
        value.surfaces.isNotEmpty() && value.surfaces.keys.all { it in setOf("score", "box", "plays") } &&
        value.surfaces.all { (name, surface) -> surface.at > 0 && surface.at <= now() + 300_000 && now() - surface.at <= MAX_AGE &&
            surface.game["gameId"].string() == id && (name != "plays" || surface.game["actions"] is JsonArray) &&
            (surface.source == null || surface.source == "${config.name} CDN" || config == NativeBasketballLeague.NBA && surface.source == "NBA Stats") } &&
        value.match(config)?.let { match -> value.surfaces.filterKeys { it in setOf("score", "box") }.values.all {
            NativeBasketballMapping.game(it.game, config) != null && NativeBasketball.validTeams(it.game, match, config)
        } && value.details(match, config) != null } == true
    private fun validId(id: String) = id.matches(Regex("[0-9]{10}"))
    companion object {
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
        private const val MAX_AGE = 30L * 24 * 3_600_000
        private val GAME_FIELDS = setOf("gameId", "gameCode", "gameTimeUTC", "gameDateTimeUTC", "gameDateUTC", "gameStatus", "gameStatusText", "period", "regulationPeriods", "gameClock", "awayTeam", "homeTeam", "arena", "nationalBroadcasters", "nationalOttBroadcasters", "homeTvBroadcasters", "awayTvBroadcasters", "gameLeaders", "seriesText")
        private val TEAM_FIELDS = setOf("teamId", "teamCity", "teamName", "teamTricode", "score", "wins", "losses", "periods", "timeoutsRemaining", "inBonus")
    }
}
