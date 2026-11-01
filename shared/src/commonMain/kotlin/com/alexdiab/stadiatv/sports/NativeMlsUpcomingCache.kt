package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Thin real announced fixtures, separate from the 12 rich-game slots so calendars cannot evict watched games. */
class NativeMlsUpcomingCache(private val folder: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Entry(val at: Long, val payload: JsonElement) {
        fun match(): Match? = NativeMlsMapping.game(payload)?.takeIf { it.state == GameState.PRE }
    }
    private val lock = SportsLock()
    suspend fun load(id: String): Entry? = withContext(sportsIO) {
        if (NativeMlsMapping.rawId(id, "MAT") != id) null else lock.locked { read()[id] }
    }
    suspend fun save(rows: List<JsonElement>, at: Long) = withContext(sportsIO) {
        if (rows.isEmpty() || rows.size > 2000 || at <= 0 || at > now() + 300_000 || now() - at > AGE) return@withContext
        lock.locked {
            val entries = read().toMutableMap()
            for (raw in rows) {
                val game = NativeMlsMapping.game(raw) ?: continue
                val id = game.nativeReference!!.gameId
                if (game.state != GameState.PRE) { if ((entries[id]?.at ?: 0) <= at) entries.remove(id); continue }
                val compact = JsonObject(raw.obj.orEmpty().filterKeys { it in FIELDS })
                if (compact.toString().length > 8192) continue
                if ((entries[id]?.at ?: 0) <= at) entries[id] = Entry(at, compact)
            }
            val bounded = LinkedHashMap<String, JsonElement>(); var budget = 64
            for ((id, entry) in entries.entries.sortedWith(compareByDescending<Map.Entry<String, Entry>> { it.value.at }.thenBy { it.value.match()?.startMillis ?: Long.MAX_VALUE })) {
                val value = buildJsonObject { put("at", entry.at); put("payload", entry.payload) }
                val size = id.encodeToByteArray().size + value.toString().encodeToByteArray().size + 8
                if (bounded.size >= 128) break
                if (budget + size > SIZE) continue
                bounded[id] = value; budget += size
            }
            val bytes = buildJsonObject {
                put("version", 1); put("provider", "MLS")
                put("entries", JsonObject(bounded))
            }.toString().encodeToByteArray()
            if (bytes.size > SIZE) return@locked
            try {
                folder.mkdirs(); val pending = SportsFile(folder, "upcoming.pending")
                try { pending.writeSynced(bytes); pending.renameTo(SportsFile(folder, "upcoming.json")) }
                finally { pending.delete() }
            } catch (_: Exception) { /* A cache failure never turns a real announced schedule into a failed request. */ }
        }
    }
    private fun read(): Map<String, Entry> = runCatching {
        val file = SportsFile(folder, "upcoming.json")
        if (!file.isFile || file.length() !in 1..SIZE.toLong()) return emptyMap()
        val root = parseJson(file.readText())
        if (root["provider"].string() != "MLS" || root["version"].string() != "1") return emptyMap()
        val rows = root["entries"] as? JsonObject ?: return emptyMap()
        if (rows.size > 128) return emptyMap()
        rows.mapNotNull { (id, raw) ->
            val at = raw["at"].string().toLongOrNull() ?: return@mapNotNull null
            val payload = raw["payload"] ?: return@mapNotNull null
            val entry = Entry(at, payload); val game = entry.match() ?: return@mapNotNull null
            if (NativeMlsMapping.rawId(id, "MAT") != id || game.nativeReference?.gameId != id || at <= 0 || at > now() + 300_000 || now() - at > AGE) null else id to entry
        }.toMap()
    }.getOrDefault(emptyMap())
    companion object {
        private const val SIZE = 384 * 1024
        private const val AGE = 30L * 86_400_000
        private val FIELDS = setOf("match_id", "competition_id", "competition_name", "season_id", "season", "planned_kickoff_time", "match_status", "minute_of_play", "match_day", "match_type", "stadium_name",
            "home_team_id", "home_team_name", "home_team_short_name", "home_team_three_letter_code", "away_team_id", "away_team_name", "away_team_short_name", "away_team_three_letter_code")
    }
}
