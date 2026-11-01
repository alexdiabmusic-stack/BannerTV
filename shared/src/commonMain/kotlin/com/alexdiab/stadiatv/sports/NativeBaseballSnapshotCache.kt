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

/** Public, accepted MLB data only. No playlist URLs, account data or entitlement tokens. */
class NativeBaseballSnapshotCache(private val directory: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Snapshot(val root: JsonElement, val at: Long, val surfaces: Map<String, Long>) {
        fun game(): Match? = NativeBaseballProvider.gameFromFeed(root)
        fun details(match: Match): GameCentre? = NativeBaseball.parse(root, match, at)?.let {
            it.copy(baseball = it.baseball?.copy(surfaceUpdatedAt = surfaces, savedSnapshot = true))
        }
    }
    private val mutex = Mutex()

    suspend fun load(id: String): Snapshot? = withContext(sportsIO) { mutex.withLock {
        if (!validId(id)) return@withLock null
        runCatching {
            val file = SportsFile(directory, "$id.json")
            if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES.toLong()) return@runCatching null
            val raw = parseJson(file.readText())
            val at = raw["at"].string().toLongOrNull() ?: return@runCatching null
            if (raw["version"].int() != 1 || raw["gameId"].string() != id || !validTime(at)) return@runCatching null
            val root = raw["feed"] ?: return@runCatching null
            if (root["gamePk"].string() != id) return@runCatching null
            val reportedTimes = raw["surfaces"].obj.orEmpty()
            val times = reportedTimes.mapNotNull { (key, value) ->
                val time = value.string().toLongOrNull() ?: return@mapNotNull null
                if (key in SURFACES && validTime(time) && time <= at) key to time else null
            }.toMap()
            // The normalized feed contains all accepted sections: never drop an old timestamp but keep its payload.
            if (times.isEmpty() || times.size != reportedTimes.size) return@runCatching null
            Snapshot(root, at, times).takeIf { saved -> saved.game()?.let { saved.details(it) != null } == true }
        }.getOrNull()
    } }

    suspend fun save(id: String, value: Snapshot) = withContext(sportsIO) { mutex.withLock {
        if (!validId(id) || !validTime(value.at) || value.root["gamePk"].string() != id || value.surfaces.isEmpty() ||
            value.surfaces.any { (key, at) -> key !in SURFACES || !validTime(at) || at > value.at } ||
            value.game()?.let { value.details(it) != null } != true) return@withLock
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
            val target = SportsFile(directory, "$id.json")
            // A cancelled/late caller must not replace a newer snapshot on disk.
            if (target.isFile && target.length() in 1..MAX_FILE_BYTES.toLong()) {
                val previous = runCatching { parseJson(target.readText())["at"].string().toLongOrNull() }.getOrNull()
                if (previous != null && previous > value.at) return@runCatching
            }
            val body = buildJsonObject {
                put("version", 1); put("gameId", id); put("at", value.at); put("feed", value.root)
                put("surfaces", buildJsonObject { value.surfaces.forEach { (key, at) -> put(key, at) } })
            }.toString().encodeToByteArray()
            if (body.size > MAX_FILE_BYTES) return@runCatching
            val temporary = SportsFile(directory, "$id.pending")
            try {
                temporary.writeSynced(body)
                if (!temporary.renameTo(target)) return@runCatching
                val files = directory.listFiles { file -> file.name.matches(Regex("[1-9][0-9]{0,9}\\.json")) }?.sortedByDescending { it.lastModified() }.orEmpty()
                var bytes = 0L
                files.forEachIndexed { index, file -> bytes += file.length(); if (index >= MAX_FILES || bytes > MAX_TOTAL_BYTES) file.delete() }
            } finally { temporary.delete() }
        }
        Unit
    } }

    private fun validTime(at: Long) = at > 0 && at <= now() + 300_000 && now() - at <= MAX_AGE
    private fun validId(id: String) = id.matches(Regex("[1-9][0-9]{0,9}"))
    companion object {
        val SURFACES = setOf("overview", "linescore", "plays", "boxscore")
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 24L * 1024 * 1024
        private const val MAX_FILES = 12
        private const val MAX_AGE = 30L * 24 * 3_600_000
    }
}
