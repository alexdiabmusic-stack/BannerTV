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

/** NFLGameCache translation: accepted public game data + a small name directory, never bearer tokens/headers. */
class NativeFootballSnapshotCache(private val directory: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Snapshot(val row: JsonElement, val summaryAt: Long, val detailsAt: Long, val players: Map<String, String>) {
        fun match(): Match? {
            val week = NflCalendar.week(row) ?: return null
            return NflCalendar.matches(JsonArray(listOf(row)), week).singleOrNull()
        }
        fun details(match: Match): GameCentre? = NativeFootball.parse(row, match, summaryAt, players)?.let { data ->
            data.copy(football = data.football?.copy(summaryUpdatedAt = summaryAt, detailsUpdatedAt = detailsAt, savedSnapshot = true))
        }
    }
    private val mutex = Mutex()

    suspend fun load(id: String): Snapshot? = withContext(sportsIO) { mutex.withLock {
        if (!validId(id)) return@withLock null
        read(id)
    } }
    suspend fun save(id: String, value: Snapshot) = withContext(sportsIO) { mutex.withLock {
        if (!validId(id) || !valid(value) || value.row["id"].string() != id) return@withLock
        runCatching {
            if ((read(id)?.summaryAt ?: 0) > value.summaryAt || !directory.isDirectory && !directory.mkdirs()) return@runCatching
            val body = buildJsonObject {
                put("version", 1); put("gameId", id); put("summaryAt", value.summaryAt); put("detailsAt", value.detailsAt); put("game", value.row)
                put("players", buildJsonObject { value.players.forEach { (id, name) -> put(id, name) } })
            }.toString().encodeToByteArray()
            if (body.size > MAX_FILE) return@runCatching
            val target = SportsFile(directory, "$id.json"); val temporary = SportsFile(directory, "$id.pending")
            try {
                temporary.writeSynced(body)
                if (!temporary.renameTo(target)) return@runCatching
                var bytes = 0L
                directory.listFiles { file -> file.name.matches(Regex("[A-Za-z0-9_-]{1,128}\\.json")) }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                    bytes += file.length(); if (index >= 12 || bytes > 24L * 1024 * 1024) file.delete()
                }
            } finally { temporary.delete() }
        }
        Unit
    } }
    private fun read(id: String): Snapshot? = runCatching {
        val file = SportsFile(directory, "$id.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE.toLong()) return@runCatching null
        val raw = parseJson(file.readText())
        if (raw["version"].int() != 1 || raw["gameId"].string() != id || raw["game"]["id"].string() != id) return@runCatching null
        val summaryAt = raw["summaryAt"].string().toLongOrNull() ?: return@runCatching null
        val detailsAt = raw["detailsAt"].string().toLongOrNull() ?: return@runCatching null
        val players = raw["players"].obj?.mapValues { it.value.string() } ?: return@runCatching null
        Snapshot(raw["game"]!!, summaryAt, detailsAt, players).takeIf(::valid)
    }.getOrNull()
    private fun valid(value: Snapshot): Boolean = validTime(value.summaryAt) && validTime(value.detailsAt) && value.detailsAt <= value.summaryAt &&
        value.players.size <= 8192 && value.players.all { (id, name) -> validId(id) && name.isNotBlank() && name.length <= 512 } &&
        value.match()?.let { value.details(it) != null } == true
    private fun validTime(at: Long) = at > 0 && at <= now() + 300_000 && now() - at <= 30L * 24 * 3_600_000
    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    companion object { private const val MAX_FILE = 4 * 1024 * 1024 }
}
