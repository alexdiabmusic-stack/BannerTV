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

/** Bounded, atomic, public MLS payloads only. Never shares EPL identity/cache or stores account data. */
class NativeMlsSnapshotCache(private val folder: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Surface(val at: Long, val payload: JsonElement)
    data class Snapshot(val surfaces: Map<String, Surface> = emptyMap(), val fullAt: Long = 0,
        val pages: Int = 0, val tail: String? = null) {
        fun match() = surfaces["overview"]?.let { NativeMlsMapping.game(it.payload, true) }
        fun details(expected: Match? = null): GameCentre? {
            val match = match() ?: return null
            if (expected != null && (expected.key != match.key || expected.nativeReference != match.nativeReference ||
                    expected.home.teamId != match.home.teamId || expected.away.teamId != match.away.teamId)) return null
            val overview = surfaces.getValue("overview"); val raw = overview.payload
            val season = raw["match_information"]["season_id"].string()
            for (kind in listOf("lineups", "officials")) surfaces[kind]?.let { surface ->
                val bundled = NativeMlsMapping.game(surface.payload, true) ?: return null
                if (bundled.key != match.key || bundled.nativeReference != match.nativeReference || bundled.home.teamId != match.home.teamId || bundled.away.teamId != match.away.teamId) return null
            }
            val result = NativeSoccerReducer.apply(null, NativeSoccerUpdate(match, match.nativeReference!!.gameId, overview.at, "MLS", "MLS",
                lineups = surfaces["lineups"]?.let { NativeMlsMapping.lineups(it.payload) },
                officials = surfaces["officials"]?.let { NativeMlsMapping.officials(it.payload) },
                events = surfaces["events"]?.let { NativeMlsDetails.events(it.payload, match, season, raw) },
                statistics = surfaces["statistics"]?.let { NativeMlsDetails.stats(it.payload, match, season) },
                playerStats = surfaces["playerStats"]?.let { NativeMlsDetails.playerStats(it.payload, match, season) },
                commentary = surfaces["commentary"]?.let { NativeMlsDetails.comments(it.payload, match, season) },
                nextCursor = if (pages > 0) tail else surfaces["commentary"]?.let { NativeMlsClient.cursor(it.payload) },
                gameInfo = listOfNotNull(match.venue?.let { "Venue" to it }, raw["environment"]["number_of_spectators"].stringOrNull()?.let { "Attendance" to it }))) ?: return null
            return result.copy(soccer = result.soccer!!.copy(surfaceUpdatedAt = surfaces.mapValues { it.value.at },
                lastFullRefresh = fullAt, commentaryPages = pages, nextCursor = if (pages > 0) tail else result.soccer.nextCursor, savedSnapshot = true))
        }
        fun accept(payloads: Map<String, JsonElement>, at: Long, full: Boolean) = copy(
            surfaces = surfaces + payloads.mapValues { Surface(at, it.value) }, fullAt = if (full) at else fullAt)
        fun older(payload: JsonElement): Snapshot {
            val old = surfaces["commentary"] ?: return this
            return copy(surfaces = surfaces + ("commentary" to old.copy(payload = mergeComments(old.payload, payload))),
                pages = (pages + 1).coerceAtMost(10), tail = NativeMlsClient.cursor(payload))
        }
    }
    private val lock = SportsLock()
    suspend fun load(id: String): Snapshot? = withContext(sportsIO) { lock.locked { read(id) } }
    suspend fun save(id: String, incoming: Snapshot) = withContext(sportsIO) {
        lock.locked {
            if (NativeMlsMapping.rawId(id, "MAT") != id || !valid(id, incoming)) return@locked
            val old = read(id)
            val merged = if (old == null) incoming else {
                val surfaces = (old.surfaces.keys + incoming.surfaces.keys).associateWith { key ->
                    val a = old.surfaces[key]; val b = incoming.surfaces[key]
                    when { a == null -> b!!; b == null -> a
                        key == "commentary" -> Surface(maxOf(a.at, b.at), if (a.at > b.at) mergeComments(b.payload, a.payload) else mergeComments(a.payload, b.payload))
                        b.at >= a.at -> b; else -> a }
                }
                val history = if (old.pages > incoming.pages) old else incoming
                Snapshot(surfaces, maxOf(old.fullAt, incoming.fullAt), history.pages, history.tail)
            }
            if (!valid(id, merged)) return@locked
            val json = buildJsonObject {
                put("version", 1); put("provider", "MLS"); put("gameId", id); put("fullAt", merged.fullAt); put("pages", merged.pages)
                put("tail", merged.tail?.let(::JsonPrimitive) ?: JsonNull)
                put("surfaces", buildJsonObject { merged.surfaces.forEach { (key, surface) -> put(key, buildJsonObject { put("at", surface.at); put("payload", surface.payload) }) } })
            }.toString().encodeToByteArray()
            if (json.size > MAX_FILE) return@locked
            try {
                folder.mkdirs()
                val target = SportsFile(folder, "$id.json"); val pending = SportsFile(folder, "$id.pending")
                try { pending.writeSynced(json); if (!pending.renameTo(target)) return@locked }
                finally { pending.delete() }
                var bytes = 0L
                files().sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                    bytes += file.length()
                    if (index >= 12 || bytes > 12L * 1024 * 1024 || now() - file.lastModified() > MAX_AGE) file.delete()
                }
            } catch (_: Exception) { /* Disk failure must not remove already accepted in-memory game data. */ }
        }
    }
    private fun read(id: String): Snapshot? {
        if (NativeMlsMapping.rawId(id, "MAT") != id) return null
        val file = SportsFile(folder, "$id.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE.toLong()) return null
        return runCatching {
            val root = parseJson(file.readText())
            if (root["provider"].string() != "MLS" || root["version"].string() != "1" || root["gameId"].string() != id) return null
            val surfaces = (root["surfaces"] as? JsonObject)?.mapValues { (_, raw) ->
                Surface(raw["at"].string().toLongOrNull() ?: return null, raw["payload"] ?: return null)
            } ?: return null
            Snapshot(surfaces, root["fullAt"].string().toLongOrNull() ?: return null,
                root["pages"].string().toIntOrNull() ?: return null, root["tail"].stringOrNull()).takeIf { valid(id, it) }
        }.getOrNull()
    }
    private fun valid(id: String, snapshot: Snapshot): Boolean = runCatching {
        val match = snapshot.match() ?: return false
        if (match.nativeReference?.gameId != id || snapshot.pages !in 0..10 || (snapshot.tail?.length ?: 0) > 2048 || snapshot.surfaces.keys.any { it !in KEYS }) return false
        val time = now()
        if (snapshot.surfaces.values.any { it.at <= 0 || it.at > time + 300_000 || time - it.at > MAX_AGE } || snapshot.fullAt < 0 || snapshot.fullAt > time + 300_000) return false
        snapshot.details() != null
    }.getOrDefault(false)
    private fun files() = folder.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("MLS-MAT-[A-Z0-9]{6,16}\\.json")) }
    companion object {
        private const val MAX_FILE = 2 * 1024 * 1024
        private const val MAX_AGE = 30L * 86_400_000
        private val KEYS = setOf("overview", "lineups", "officials", "events", "statistics", "playerStats", "commentary")
        private fun mergeComments(first: JsonElement, last: JsonElement): JsonElement {
            val comments = (first["commentary"].array + last["commentary"].array).groupBy { it["event_id"].string() }.values.map { versions ->
                versions.reduce { old, new -> if ((new["version"].string().toIntOrNull() ?: 0) >= (old["version"].string().toIntOrNull() ?: 0)) new else old }
            }.sortedByDescending { it["event_time"].string() }.take(500)
            return JsonObject(last.obj.orEmpty() + ("commentary" to JsonArray(comments)))
        }
    }
}
