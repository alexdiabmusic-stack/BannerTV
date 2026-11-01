package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Bounded, atomic public EPL payloads. Other soccer providers cannot read or overwrite this namespace. */
class NativeSoccerSnapshotCache(private val directory: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Surface(val at: Long, val payload: JsonElement)
    data class Snapshot(val surfaces: Map<String, Surface> = emptyMap(), val playerNames: Map<String, String> = emptyMap(), val lastFullRefresh: Long = 0, val commentaryPages: Int = 0) {
        fun match(): Match? = surfaces["overview"]?.payload?.let(NativePremierLeagueMapping::game)
        fun details(expected: Match? = null): GameCentre? {
            val match = match() ?: return null
            if (expected != null && (expected.key != match.key || expected.nativeReference?.provider != "EPL" ||
                    expected.home.teamId != match.home.teamId || expected.away.teamId != match.away.teamId)) return null
            val overview = surfaces["overview"] ?: return null
            val id = match.nativeReference?.gameId ?: return null
            val lineups = surfaces["lineups"]?.payload
            val comments = surfaces["commentary"]?.payload
            val update = NativeSoccerUpdate(match, id, overview.at,
                events = surfaces["events"]?.payload?.let { NativeSoccerDetails.events(it, match, id, playerNames, rawTeamId(match.home), rawTeamId(match.away)) },
                lineups = lineups?.let { listOfNotNull(NativeSoccer.lineup(it["home_team"] ?: it["homeTeam"], true), NativeSoccer.lineup(it["away_team"] ?: it["awayTeam"], false)) },
                statistics = surfaces["statistics"]?.payload?.let { NativeSoccerDetails.stats(it, rawTeamId(match.home).orEmpty(), rawTeamId(match.away).orEmpty()) },
                officials = surfaces["officials"]?.payload?.let { NativeSoccerDetails.officials(it, id) },
                commentary = comments?.let { NativeSoccer.comments(it, id) }, nextCursor = comments.path("pagination", "_next").stringOrNull(),
                playerNames = playerNames, gameInfo = information(overview.payload))
            return NativeSoccerReducer.apply(null, update)?.let { result -> result.copy(soccer = result.soccer?.copy(
                surfaceUpdatedAt = surfaces.mapValues { it.value.at }, lastFullRefresh = lastFullRefresh, savedSnapshot = true, commentaryPages = commentaryPages)) }
        }
        fun accept(payloads: Map<String, JsonElement>, requested: Long, accepted: GameCentre): Snapshot {
            val fresh = surfaces.toMutableMap()
            for ((kind, payload) in payloads) if (accepted.soccer?.surfaceUpdatedAt?.get(kind) == requested) {
                val compact = if (kind == "overview") compactOverview(payload) else if (kind == "commentary") mergeComments(fresh[kind]?.payload, payload, commentaryPages > 0) else payload
                fresh[kind] = Surface(requested, compact)
            }
            return Snapshot(fresh, accepted.soccer?.playerNames.orEmpty(), accepted.soccer?.lastFullRefresh ?: lastFullRefresh, accepted.soccer?.commentaryPages ?: commentaryPages)
        }
        fun withOlderComments(page: JsonElement): Snapshot {
            val old = surfaces["commentary"] ?: return this
            return copy(surfaces = surfaces + ("commentary" to Surface(old.at, mergeComments(old.payload, page))), commentaryPages = (commentaryPages + 1).coerceAtMost(10))
        }
    }

    private val lock = Mutex()
    suspend fun load(id: String): Snapshot? = withContext(sportsIO) { lock.withLock { if (validId(id)) read(id) else null } }
    suspend fun save(id: String, value: Snapshot) = withContext(sportsIO) { lock.withLock {
        if (!validId(id) || !valid(id, value)) return@withLock
        runCatching {
            val old = read(id)
            if (old?.match()?.state == GameState.FINAL && value.match()?.state != GameState.FINAL) return@runCatching
            val merged = old?.let {
                val surfaces = it.surfaces.toMutableMap()
                value.surfaces.forEach { (kind, surface) -> if (surface.at >= (surfaces[kind]?.at ?: 0)) surfaces[kind] = surface }
                if (it.surfaces["commentary"] != null && value.surfaces["commentary"] != null) {
                    val before = it.surfaces.getValue("commentary"); val incoming = value.surfaces.getValue("commentary")
                    val newest = if (incoming.at >= before.at) incoming else before
                    val older = if (incoming.at >= before.at) before else incoming
                    val cursorOwner = when { it.commentaryPages > value.commentaryPages -> before; value.commentaryPages > it.commentaryPages -> incoming; else -> newest }
                    val mergedComments = mergeComments(older.payload, newest.payload)
                    surfaces["commentary"] = Surface(newest.at, JsonObject(mergedComments.obj.orEmpty() + ("pagination" to (cursorOwner.payload["pagination"] ?: JsonObject(emptyMap())))))
                }
                val newer = value.surfaces.values.maxOf { surface -> surface.at } >= it.surfaces.values.maxOf { surface -> surface.at }
                Snapshot(surfaces, if (newer) (it.playerNames + value.playerNames).entries.toList().takeLast(256).associate { entry -> entry.key to entry.value } else it.playerNames,
                    maxOf(it.lastFullRefresh, value.lastFullRefresh), maxOf(it.commentaryPages, value.commentaryPages))
            } ?: value
            if (!valid(id, merged)) return@runCatching
            val body = buildJsonObject {
                put("version", 1); put("provider", "EPL"); put("gameId", id); put("lastFullRefresh", merged.lastFullRefresh); put("commentaryPages", merged.commentaryPages)
                put("players", buildJsonObject { merged.playerNames.forEach { (id, name) -> put(id, name) } })
                put("surfaces", buildJsonObject { merged.surfaces.forEach { (kind, surface) -> put(kind, buildJsonObject {
                    put("at", surface.at); put("payload", surface.payload)
                }) } })
            }.toString().encodeToByteArray()
            if (body.size > MAX_FILE_BYTES || !directory.isDirectory && !directory.mkdirs()) return@runCatching
            val pending = SportsFile(directory, "epl-$id.pending"); val target = SportsFile(directory, "epl-$id.json")
            try {
                pending.writeSynced(body)
                if (!pending.renameTo(target)) return@runCatching
                var bytes = 0L
                directory.listFiles { file -> file.name.matches(Regex("epl-[0-9]{1,20}\\.json")) }?.sortedByDescending { it.lastModified() }?.forEachIndexed { index, file ->
                    bytes += file.length(); if (index >= 12 || bytes > MAX_TOTAL_BYTES) file.delete()
                }
            } finally { pending.delete() }
        }
        Unit
    } }
    private fun read(id: String): Snapshot? = runCatching {
        val file = SportsFile(directory, "epl-$id.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES.toLong()) return@runCatching null
        val root = parseJson(file.readText())
        if (root["version"].int() != 1 || root["provider"].string() != "EPL" || root["gameId"].string() != id) return@runCatching null
        val surfaces = root["surfaces"].obj?.mapValues { (_, row) -> Surface(row["at"].string().toLongOrNull() ?: return@runCatching null, row["payload"] ?: return@runCatching null) } ?: return@runCatching null
        val players = root["players"].obj?.mapValues { (_, value) -> value.stringOrNull() ?: return@runCatching null }.orEmpty()
        Snapshot(surfaces, players, root["lastFullRefresh"].string().toLongOrNull() ?: 0, root["commentaryPages"].int()).takeIf { valid(id, it) }
    }.getOrNull()
    private fun valid(id: String, snapshot: Snapshot): Boolean {
        val match = snapshot.match() ?: return false
        if (match.nativeReference?.gameId != id || snapshot.surfaces.keys.any { it !in KINDS } || snapshot.playerNames.size > 256 ||
            snapshot.playerNames.any { !validId(it.key) || it.value.isBlank() || it.value.length > 256 } ||
            snapshot.lastFullRefresh < 0 || snapshot.lastFullRefresh > (snapshot.surfaces["overview"]?.at ?: 0) || snapshot.commentaryPages !in 0..10 ||
            NativePremierLeagueMapping.reportedSeasons(snapshot.surfaces.getValue("overview").payload).any { it != NativeSoccer.seasonFor(match.startMillis).toString() }) return false
        return snapshot.surfaces.all { (kind, surface) ->
            surface.at > 0 && surface.at <= now() + 300_000 && now() - surface.at <= MAX_AGE &&
                surface.payload["matchId"].stringOrNull()?.let { it == id } != false && when (kind) {
                    "overview" -> NativeSoccer.teamMatches(surface.payload["homeTeam"], match.home) && NativeSoccer.teamMatches(surface.payload["awayTeam"], match.away)
                    "lineups" -> NativeSoccerDetails.validLineups(surface.payload, id)
                    "events" -> NativeSoccerDetails.events(surface.payload, match, id, snapshot.playerNames, rawTeamId(match.home), rawTeamId(match.away)) != null
                    "statistics" -> surface.payload is JsonArray && (surface.payload.isEmpty() || NativeSoccerDetails.stats(surface.payload, rawTeamId(match.home).orEmpty(), rawTeamId(match.away).orEmpty()) != null)
                    "commentary" -> surface.payload["data"] is JsonArray && surface.payload["data"].array.size <= 500
                    "officials" -> NativeSoccerDetails.officials(surface.payload, id) != null
                    else -> false
                }
        } && snapshot.details() != null
    }
    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 12L * 1024 * 1024
        private const val MAX_AGE = 30L * 86_400_000
        private val KINDS = setOf("overview", "lineups", "events", "statistics", "commentary", "officials")
        private fun validId(id: String) = NativePremierLeagueMapping.rawId("epl:$id") != null
        private fun rawTeamId(side: TeamSide) = side.teamId?.let(NativePremierLeagueMapping::rawId)
        private fun mergeComments(old: JsonElement?, fresh: JsonElement, keepCursor: Boolean = false): JsonElement {
            val rows = (old["data"].array + fresh["data"].array).filter { it["comment"].stringOrNull() != null }
                .associateBy { "${it["timestamp"].string()}|${it["type"].string()}" }.values.sortedByDescending { it["timestamp"].string() }.take(500)
            val payload = fresh.obj.orEmpty() + ("data" to JsonArray(rows))
            return JsonObject(if (keepCursor) payload + ("pagination" to (old["pagination"] ?: JsonObject(emptyMap()))) else payload)
        }
        private fun information(raw: JsonElement) = listOfNotNull(raw["ground"].stringOrNull()?.let { "Venue" to it },
            raw["attendance"].stringOrNull()?.let { "Attendance" to it }, raw["matchweek"].stringOrNull()?.let { "Matchweek" to it })
        private fun compactOverview(raw: JsonElement) = JsonObject(raw.obj.orEmpty().filterKeys { it in setOf("matchId", "competitionId", "season", "seasonId", "seasonInfo", "kickoff", "period", "clock", "homeTeam", "awayTeam", "ground", "attendance", "matchweek") }
            .mapValues { (key, value) -> if (key in setOf("homeTeam", "awayTeam")) JsonObject(value.obj.orEmpty().filterKeys { it in setOf("id", "name", "shortName", "abbr", "score", "redCards") }) else value })
    }
}
