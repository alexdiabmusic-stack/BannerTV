package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.GameCentre
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Only public, accepted NHL payloads. Bounded disk storage, atomic replacement, original fetch times.
 * Saved payloads go through the same identity/anti-regression reducer as network data on every read. */
class NativeHockeySnapshotCache(private val directory: SportsFile, private val now: () -> Long = ::nowMillis) {
    private val mutex = Mutex()
    private data class Surface(val at: Long, val payload: JsonElement)
    private val names = setOf("landing", "boxscore", "play-by-play", "right-rail")

    suspend fun load(id: String, match: Match): GameCentre? = withContext(sportsIO) { mutex.withLock {
        if (!validId(id)) return@withLock null
        val records = read(id)
        var result: GameCentre? = null
        records.entries.groupBy { it.value.at }.entries.sortedBy { it.key }.forEach { (at, entries) ->
            val data = entries.associate { it.key to it.value.payload }
            result = NativeHockey.update(result, data["landing"], data["boxscore"], data["play-by-play"], data["right-rail"], match, at)
        }
        result?.let { it.copy(hockey = it.hockey?.copy(savedSnapshot = true)) }
    } }

    /** Cold links may identify a saved game while offline, without fabricating a team/time resolver. */
    suspend fun match(id: String): Match? = withContext(sportsIO) { mutex.withLock {
        if (!validId(id)) return@withLock null
        val records = read(id)
        val seed = records.values.sortedBy { it.at }.firstNotNullOfOrNull { NativeHockeyProvider.game(it.payload) } ?: return@withLock null
        var result: GameCentre? = null
        records.entries.groupBy { it.value.at }.entries.sortedBy { it.key }.forEach { (at, entries) ->
            val data = entries.associate { it.key to it.value.payload }
            result = NativeHockey.update(result, data["landing"], data["boxscore"], data["play-by-play"], data["right-rail"], seed, at)
        }
        result?.liveMatch?.takeIf { it.nativeReference?.gameId == id }
    } }

    suspend fun save(id: String, requested: Long, payloads: Map<String, JsonElement?>, result: GameCentre) = withContext(sportsIO) { mutex.withLock {
        if (!validId(id) || result.providerStamp?.gameId != id || requested <= 0 || requested > now() + 300_000) return@withLock
        val fresh = payloads.filter { (key, value) -> key in names && value != null && result.hockey?.surfaceUpdatedAt?.get(key) == requested && result.hockey.errors[key] == null }
        if (fresh.isEmpty()) return@withLock
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
            val records = read(id).toMutableMap()
            fresh.forEach { (key, value) -> if ((records[key]?.at ?: 0) <= requested) records[key] = Surface(requested, value!!) }
            val body = buildJsonObject {
                put("version", 1); put("gameId", id)
                put("surfaces", buildJsonObject { records.forEach { (key, value) -> put(key, buildJsonObject { put("at", value.at); put("payload", value.payload) }) } })
            }.toString().encodeToByteArray()
            if (body.size > MAX_FILE_BYTES) return@runCatching
            val target = SportsFile(directory, "$id.json")
            val temporary = SportsFile(directory, "$id.pending")
            try {
                temporary.writeSynced(body)
                if (!temporary.renameTo(target)) return@runCatching
                val files = directory.listFiles { file -> file.name.matches(Regex("[0-9]{10}\\.json")) }?.sortedByDescending { it.lastModified() }.orEmpty()
                var bytes = 0L
                files.forEachIndexed { index, file -> bytes += file.length(); if (index >= MAX_FILES || bytes > MAX_TOTAL_BYTES) file.delete() }
            } finally { temporary.delete() }
        }
        Unit
    } }

    private fun read(id: String): Map<String, Surface> = runCatching {
        val file = SportsFile(directory, "$id.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES.toLong()) return@runCatching emptyMap()
        val root = parseJson(file.readText())
        if (root["version"].int() != 1 || root["gameId"].string() != id) return@runCatching emptyMap()
        root["surfaces"].obj.orEmpty().mapNotNull { (key, raw) ->
            val at = raw["at"].string().toLongOrNull() ?: return@mapNotNull null
            val value = raw["payload"] ?: return@mapNotNull null
            if (key !in names || at <= 0 || at > now() + 300_000 || now() - at > MAX_AGE ||
                (key != "right-rail" && value["id"].string() != id) || (key == "right-rail" && value["id"] != null && value["id"].string() != id)) return@mapNotNull null
            key to Surface(at, value)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun validId(id: String) = id.matches(Regex("[0-9]{10}"))
    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 12L * 1024 * 1024
        private const val MAX_FILES = 12
        private const val MAX_AGE = 30L * 24 * 3_600_000
    }
}
