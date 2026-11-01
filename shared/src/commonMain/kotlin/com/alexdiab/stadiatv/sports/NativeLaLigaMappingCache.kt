package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Bounded, confirmed PUBLIC cross-provider identities only; never playlist credentials or fuzzy guesses. */
class NativeLaLigaMappingCache(private val folder: SportsFile, private val now: () -> Long = ::nowMillis) {
    private val lock = SportsLock()
    private data class Entry(val at: Long, val mapping: NativeLaLigaReconciliation.Mapping)
    suspend fun load(game: Match): NativeLaLigaReconciliation.Mapping? = withContext(sportsIO) {
        lock.locked { read()[game.nativeReference?.gameId]?.mapping?.takeIf { it.matches(game) } }
    }
    suspend fun save(game: Match, mapping: NativeLaLigaReconciliation.Mapping) = withContext(sportsIO) {
        if (!mapping.matches(game)) return@withContext
        lock.locked {
            val entries = read().toMutableMap(); entries[mapping.officialId] = Entry(now(), mapping)
            val payload = buildJsonObject {
                put("version", 1); put("provider", "LaLiga-FotMob")
                put("entries", buildJsonObject { entries.values.sortedByDescending { it.at }.take(128).forEach { entry ->
                    val value = entry.mapping
                    put(value.officialId, buildJsonObject {
                        put("at", entry.at); put("year", value.year); put("subscription", value.subscription); put("kickoff", value.kickoff)
                        put("officialHome", value.officialHome); put("officialAway", value.officialAway)
                        put("fotmobId", value.fotmobId); put("fotmobHome", value.fotmobHome); put("fotmobAway", value.fotmobAway)
                    })
                } })
            }.toString().encodeToByteArray()
            if (payload.size > SIZE) return@locked
            try {
                folder.mkdirs(); val pending = SportsFile(folder, "mapping.pending")
                try { pending.writeSynced(payload); pending.renameTo(SportsFile(folder, "mapping.json")) }
                finally { pending.delete() }
            } catch (_: Exception) { /* A persistence error does not change a verified match's runtime identity. */ }
        }
    }
    private fun read(): Map<String, Entry> = runCatching {
        val file = SportsFile(folder, "mapping.json")
        if (!file.isFile || file.length() !in 1..SIZE.toLong()) return emptyMap()
        val raw = parseJson(file.readText())
        if (raw["provider"].string() != "LaLiga-FotMob" || raw["version"].string() != "1") return emptyMap()
        val entries = raw["entries"] as? JsonObject ?: return emptyMap()
        if (entries.size > 128) return emptyMap()
        entries.mapNotNull { (id, row) ->
            val at = row["at"].string().toLongOrNull() ?: return@mapNotNull null
            val value = NativeLaLigaReconciliation.Mapping(id, row["year"].string().toIntOrNull() ?: return@mapNotNull null,
                row["subscription"].string(), row["officialHome"].string(), row["officialAway"].string(), row["kickoff"].string().toLongOrNull() ?: return@mapNotNull null,
                row["fotmobId"].string(), row["fotmobHome"].string(), row["fotmobAway"].string())
            if (!value.valid() || at <= 0 || at > now() + 300_000 || now() - at > 30L * 86_400_000) null else id to Entry(at, value)
        }.toMap()
    }.getOrDefault(emptyMap())
    companion object { private const val SIZE = 128 * 1024 }
}
