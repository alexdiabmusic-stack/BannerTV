package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Independent public MLB content/probability cache; no downloaded video or user/account information. */
class NativeBaseballExtrasCache(private val directory: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Saved(val payload: JsonElement, val at: Long)
    private val mutex = Mutex()
    suspend fun load(id: String, kind: String): Saved? = withContext(sportsIO) { mutex.withLock {
        if (!valid(id, kind)) return@withLock null
        read(id, kind)
    } }
    suspend fun save(id: String, kind: String, value: Saved) = withContext(sportsIO) { mutex.withLock {
        if (!valid(id, kind) || !validTime(value.at) || !validPayload(kind, value.payload)) return@withLock
        runCatching {
            if ((read(id, kind)?.at ?: 0) > value.at || !directory.isDirectory && !directory.mkdirs()) return@runCatching
            val body = buildJsonObject { put("version", 1); put("gameId", id); put("kind", kind); put("at", value.at); put("payload", value.payload) }.toString().encodeToByteArray()
            if (body.size > MAX_FILE) return@runCatching
            val target = SportsFile(directory, "$id-$kind.json"); val temporary = SportsFile(directory, "$id-$kind.pending")
            try {
                temporary.writeSynced(body)
                if (!temporary.renameTo(target)) return@runCatching
                var bytes = 0L
                directory.listFiles { file -> file.name.matches(Regex("[1-9][0-9]{0,9}-(highlights|probability)\\.json")) }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                    bytes += file.length(); if (index >= 24 || bytes > 12L * 1024 * 1024) file.delete()
                }
            } finally { temporary.delete() }
        }
        Unit
    } }
    private fun read(id: String, kind: String): Saved? = runCatching {
        val file = SportsFile(directory, "$id-$kind.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE.toLong()) return@runCatching null
        val raw = parseJson(file.readText()); val at = raw["at"].string().toLongOrNull() ?: return@runCatching null
        val payload = raw["payload"] ?: return@runCatching null
        if (raw["version"].int() != 1 || raw["gameId"].string() != id || raw["kind"].string() != kind || !validTime(at) || !validPayload(kind, payload)) return@runCatching null
        Saved(payload, at)
    }.getOrNull()
    private fun valid(id: String, kind: String) = id.matches(Regex("[1-9][0-9]{0,9}")) && kind in setOf("highlights", "probability")
    private fun validTime(at: Long) = at > 0 && at <= now() + 300_000 && now() - at <= 30L * 24 * 3_600_000
    companion object {
        private const val MAX_FILE = 2 * 1024 * 1024
        fun validPayload(kind: String, raw: JsonElement) = when (kind) {
            "highlights" -> raw["highlights"]["highlights"]["items"] is JsonArray
            "probability" -> raw is JsonArray
            else -> false
        }
    }
}
