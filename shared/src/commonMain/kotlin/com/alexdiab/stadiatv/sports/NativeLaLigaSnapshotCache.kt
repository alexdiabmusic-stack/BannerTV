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

/** Public, independently dated source evidence. Never persists a guessed cross-provider identity. */
class NativeLaLigaSnapshotCache(private val folder: SportsFile, private val now: () -> Long = ::nowMillis) {
    data class Surface(val at: Long, val payload: JsonElement)
    data class Evidence(val official: NativeLaLigaProvider.OfficialGame, val detail: Surface? = null) {
        fun sample(mapping: NativeLaLigaReconciliation.Mapping?): NativeLaLigaAuthority.Sample = detail?.let {
            NativeLaLigaAuthority.fotmob(it.payload, official.match, requireNotNull(mapping), it.at)
        } ?: NativeLaLigaAuthority.Sample(official.match, official.checkedAt)
    }
    data class Snapshot(val official: NativeLaLigaProvider.OfficialGame, val fotmob: Evidence?, val accepted: Evidence,
        val mapping: NativeLaLigaReconciliation.Mapping?, val surfaces: Map<String, Surface> = emptyMap(),
        val fullAt: Long = 0, val envelopeAt: Long = official.checkedAt) {
        fun state() = NativeLaLigaAuthority.State(NativeLaLigaAuthority.Sample(official.match, official.checkedAt), fotmob?.sample(mapping),
            accepted.sample(mapping), if (accepted.detail == null) NativeLaLigaAuthority.Owner.Official else NativeLaLigaAuthority.Owner.FotMob)
        fun match() = accepted.sample(mapping).match
        fun details(expected: Match? = null, saved: Boolean = true, errors: Map<String, String> = emptyMap()): GameCentre? {
            val game = match()
            if (expected != null && !sameFixture(expected, game)) return null
            val identity = mapping
            fun <T> surface(key: String, mapper: (JsonElement, Match, NativeLaLigaReconciliation.Mapping) -> T): T? =
                surfaces[key]?.let { mapper(it.payload, game, requireNotNull(identity)) }
            val score = accepted.sample(mapping)
            val sources = surfaces.keys.associateWith { if (it == "officials") "LaLiga" else "FotMob" }
            val source = if (accepted.detail == null) "LaLiga" else "FotMob"
            val result = NativeSoccerReducer.apply(null, NativeSoccerUpdate(game, game.nativeReference!!.gameId, envelopeAt,
                "LaLiga", "LaLiga", events = surface("events", NativeFotMobDetails::events),
                lineups = surface("lineups", NativeFotMobDetails::lineups), statistics = surface("statistics", NativeFotMobDetails::statistics),
                officials = surfaces["officials"]?.let { NativeLaLigaMapping.officials(it.payload, game) },
                shots = surface("shots", NativeFotMobDetails::shots), pressure = surface("pressure", NativeFotMobDetails::pressure),
                commentary = surfaces["commentary"]?.let { NativeFotMobDetails.commentary(it.payload, requireNotNull(identity)) },
                scoreUpdatedAt = score.checkedAt, scoreSource = source,
                resourceTimes = surfaces.mapValues { it.value.at }, resourceSources = sources, errors = errors,
                gameInfo = listOfNotNull("Source" to "LaLiga official fixture · FotMob match detail", "Score source" to source,
                    game.venue?.let { "Venue" to it }, game.nativeReference.week?.let { "Matchweek" to "$it" }))) ?: return null
            return result.copy(soccer = result.soccer!!.copy(savedSnapshot = saved, lastFullRefresh = fullAt))
        }
    }
    private val lock = SportsLock()
    suspend fun load(id: String): Snapshot? = withContext(sportsIO) { lock.locked { read(id) } }
    suspend fun save(id: String, incoming: Snapshot) = withContext(sportsIO) { lock.locked {
        if (!valid(id, incoming)) return@locked
        // A delayed writer cannot roll any independently checked resource or the authority state back.
        val old = read(id)
        if (old != null && (old.envelopeAt > incoming.envelopeAt || old.official.checkedAt > incoming.official.checkedAt ||
                old.surfaces.any { (key, value) -> (key == "officials" || old.mapping == incoming.mapping) && value.at > (incoming.surfaces[key]?.at ?: 0) })) return@locked
        val body = encode(id, incoming).toString().encodeToByteArray()
        if (body.size > MAX_FILE) return@locked
        try {
            if (!folder.isDirectory && !folder.mkdirs()) return@locked
            val temporary = SportsFile(folder, "$id.pending")
            try {
                temporary.writeSynced(body)
                if (!temporary.renameTo(SportsFile(folder, "$id.json"))) return@locked
                var bytes = 0L
                folder.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[1-9][0-9]{0,11}\\.json")) }
                    .sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                        bytes += file.length()
                        if (index >= 12 || bytes > 12L * 1024 * 1024 || now() - file.lastModified() > AGE) file.delete()
                    }
            } finally { temporary.delete() }
        } catch (_: Exception) { /* Cache failure cannot remove accepted in-memory data. */ }
    } }
    private fun read(id: String): Snapshot? = runCatching {
        if (!NativeFotMobClient.validId(id)) return null
        val file = SportsFile(folder, "$id.json")
        if (!file.isFile || file.length() !in 1..MAX_FILE.toLong()) return null
        val raw = parseJson(file.readText())
        if (raw["version"].string() != "1" || raw["provider"].string() != "LaLiga" || raw["gameId"].string() != id) return null
        val official = decodeOfficial(raw["official"] ?: return null) ?: return null
        val mapping = raw["mapping"]?.takeUnless { it is JsonNull }?.let(::decodeMapping)
        val surfaces = (raw["surfaces"] as? JsonObject)?.mapValues { (_, value) -> decodeSurface(value) ?: return null } ?: return null
        val result = Snapshot(official, raw["fotmob"]?.takeUnless { it is JsonNull }?.let(::decodeEvidence),
            decodeEvidence(raw["accepted"] ?: return null), mapping, surfaces,
            raw["fullAt"].string().toLongOrNull() ?: return null, raw["envelopeAt"].string().toLongOrNull() ?: return null)
        result.takeIf { valid(id, it) }
    }.getOrNull()
    private fun valid(id: String, value: Snapshot): Boolean = runCatching {
        if (!NativeFotMobClient.validId(id) || value.official.match.nativeReference?.gameId != id || value.surfaces.keys.any { it !in KEYS }) return false
        fun time(at: Long) = at > 0 && at <= now() + 300_000 && now() - at <= AGE
        if (!time(value.envelopeAt) || value.fullAt < 0 || value.fullAt > value.envelopeAt) return false
        val evidence = listOfNotNull(Evidence(value.official), value.fotmob, value.accepted)
        if (evidence.any { !time(it.official.checkedAt) || it.detail?.let { detail -> !time(detail.at) } == true ||
                !sameFixture(it.official.match, value.official.match) ||
                NativeLaLigaMapping.match(it.official.payload, it.official.match.nativeReference!!.season!!, it.official.match.nativeReference.seasonType!!) != it.official.match }) return false
        if (value.mapping != null && !value.mapping.matches(value.official.match)) return false
        if (value.fotmob != null && value.fotmob.detail == null) return false
        if (value.surfaces.values.any { !time(it.at) }) return false
        val state = value.state()
        val checked = NativeLaLigaAuthority.apply(value.official.match, state, now = now()) ?: return false
        if (checked.accepted != state.accepted || checked.owner != state.owner) return false
        value.details() != null
    }.getOrDefault(false)
    private fun encode(id: String, value: Snapshot) = buildJsonObject {
        put("version", 1); put("provider", "LaLiga"); put("gameId", id); put("fullAt", value.fullAt); put("envelopeAt", value.envelopeAt)
        put("official", encodeOfficial(value.official)); put("fotmob", value.fotmob?.let(::encodeEvidence) ?: JsonNull)
        put("accepted", encodeEvidence(value.accepted)); put("mapping", value.mapping?.let(::encodeMapping) ?: JsonNull)
        put("surfaces", buildJsonObject { value.surfaces.forEach { (key, item) -> put(key, encodeSurface(item)) } })
    }
    companion object {
        private const val MAX_FILE = 2 * 1024 * 1024
        private const val AGE = 30L * 86_400_000
        private val KEYS = setOf("events", "lineups", "statistics", "shots", "pressure", "commentary", "officials")
        fun sameFixture(a: Match, b: Match) = a.key == b.key && a.nativeReference?.provider == b.nativeReference?.provider &&
            a.nativeReference?.season == b.nativeReference?.season && a.nativeReference?.seasonType == b.nativeReference?.seasonType &&
            a.home.teamId == b.home.teamId && a.away.teamId == b.away.teamId
        private fun encodeSurface(value: Surface) = buildJsonObject { put("at", value.at); put("payload", value.payload) }
        private fun decodeSurface(raw: JsonElement): Surface? = Surface(raw["at"].string().toLongOrNull() ?: return null, raw["payload"] ?: return null)
        private fun encodeOfficial(value: NativeLaLigaProvider.OfficialGame) = buildJsonObject {
            put("at", value.checkedAt); put("year", value.match.nativeReference!!.season); put("subscription", value.match.nativeReference.seasonType); put("payload", value.payload)
        }
        private fun decodeOfficial(raw: JsonElement): NativeLaLigaProvider.OfficialGame? {
            val payload = raw["payload"] ?: return null
            val game = NativeLaLigaMapping.match(payload, raw["year"].string().toIntOrNull() ?: return null, raw["subscription"].string()) ?: return null
            return NativeLaLigaProvider.OfficialGame(game, payload, raw["at"].string().toLongOrNull() ?: return null)
        }
        private fun encodeEvidence(value: Evidence) = buildJsonObject { put("official", encodeOfficial(value.official)); put("detail", value.detail?.let(::encodeSurface) ?: JsonNull) }
        private fun decodeEvidence(raw: JsonElement) = Evidence(requireNotNull(decodeOfficial(raw["official"] ?: error("Missing fixture"))),
            raw["detail"]?.takeUnless { it is JsonNull }?.let { requireNotNull(decodeSurface(it)) })
        internal fun encodeMapping(value: NativeLaLigaReconciliation.Mapping) = buildJsonObject {
            put("id", value.officialId); put("year", value.year); put("subscription", value.subscription); put("kickoff", value.kickoff)
            put("home", value.officialHome); put("away", value.officialAway); put("fotmob", value.fotmobId); put("fmHome", value.fotmobHome); put("fmAway", value.fotmobAway)
        }
        internal fun decodeMapping(raw: JsonElement) = NativeLaLigaReconciliation.Mapping(raw["id"].string(), raw["year"].string().toInt(), raw["subscription"].string(),
            raw["home"].string(), raw["away"].string(), raw["kickoff"].string().toLong(), raw["fotmob"].string(), raw["fmHome"].string(), raw["fmAway"].string()).also { require(it.valid()) }
        /** Keep only the resource's identity/header + own content; never duplicate a 4MiB detail response six times. */
        fun scoped(raw: JsonElement, kind: String): JsonElement {
            val content = raw["content"]
            val selected = when (kind) {
                "events" -> buildJsonObject { put("matchFacts", buildJsonObject { put("events", content["matchFacts"]["events"] ?: JsonNull) }) }
                "lineups" -> buildJsonObject { put("lineup", content["lineup"] ?: JsonNull) }
                "statistics" -> buildJsonObject { put("stats", content["stats"] ?: JsonNull) }
                "shots" -> buildJsonObject { put("shotmap", content["shotmap"] ?: JsonNull) }
                "pressure" -> buildJsonObject { put("momentum", content["momentum"] ?: JsonNull) }
                "score" -> buildJsonObject { }
                else -> error("Unknown La Liga resource")
            }
            return buildJsonObject { put("general", raw["general"] ?: JsonNull); put("header", raw["header"] ?: JsonNull); put("content", selected) }
        }
    }
}
