package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.alexdiab.stadiatv.sports.NativeLaLigaSnapshotCache.Evidence
import com.alexdiab.stadiatv.sports.NativeLaLigaSnapshotCache.Surface
import com.alexdiab.stadiatv.sports.NativeLaLigaSnapshotCache.Snapshot

/** Independent official discovery/score and verified public detail; denial never triggers a bypass. */
class NativeLaLiga(private val official: NativeLaLigaProvider, private val fotmob: NativeFotMobClient,
    private val snapshots: NativeLaLigaSnapshotCache? = null, private val mappings: NativeLaLigaMappingCache? = null,
    private val now: () -> Long = ::nowMillis) {
    private data class Cooldown(val until: Long, val transport: Boolean)
    private val cooldowns = SportsMemo<String, Cooldown>()
    private val locks = Array(16) { Mutex() }
    private val memoryLock = SportsLock()
    private val memory = LinkedHashMap<String, Snapshot>()
    private val memoryWeights = HashMap<String, Long>()
    private val failures = LinkedHashMap<String, Map<String, String>>()
    fun onNetworkRestored() { cooldowns.transaction { cooldowns.removeWhere { it.transport } } }
    private fun identity(match: Match): String {
        val ref = match.nativeReference
        if (match.league != NativeLaLigaMapping.league || ref?.provider != "LaLiga" || match.id != "laliga:${ref.gameId}" ||
            NativeLaLigaMapping.rawId(ref.gameId) != ref.gameId || ref.season !in 1900..2200 || ref.seasonType.isNullOrBlank()) throw IOException("An official La Liga game identity is required")
        return ref.gameId
    }
    private suspend fun saved(id: String): Snapshot? = memoryLock.locked { memory[id] } ?: snapshots?.load(id)
    suspend fun cachedMatch(id: String): Match? = if (NativeFotMobClient.validId(id)) saved(id)?.match() else null
    suspend fun cached(match: Match): GameCentre? = saved(identity(match))?.details(match)
    suspend fun load(match: Match, previous: GameCentre? = null, force: Boolean = false, tab: String = "Overview"): GameCentre {
        val id = identity(match)
        return locks[(id.hashCode() and Int.MAX_VALUE) % locks.size].withLock { coroutineScope {
            val at = now()
            val saved = saved(id)?.takeIf { NativeLaLigaSnapshotCache.sameFixture(it.official.match, match) }
            val errors = SportsMemo(memoryLock.locked { failures[id].orEmpty() })
            suspend fun <T> attempt(key: String, operation: suspend () -> T): T? {
                val scope = "$id:$key"
                if ((cooldowns[scope]?.until ?: 0) > now()) {
                    errors[key] = "${label(key)} is cooling down; any accepted data is unchanged."
                    return null
                }
                return try { withTimeout(10_000) { operation() }.also { errors.transaction { errors.remove(key) }; cooldowns.remove(scope) } }
                catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    cooldowns[scope] = Cooldown(now() + 60_000, true)
                    errors.transaction { errors[key] = "${label(key)} timed out; any accepted data is unchanged." }
                    null
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    val wait = if (error is HttpStatusException) NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds) else 60_000
                    cooldowns.transaction {
                        if (cooldowns.size >= 128 && !cooldowns.containsKey(scope)) cooldowns.snapshot().entries.minByOrNull { it.value.until }?.key?.let { cooldowns.remove(it) }
                        cooldowns[scope] = Cooldown(now() + wait, error is IOException && error !is HttpStatusException)
                    }
                    errors.transaction { errors[key] = "${label(key)} couldn't refresh; any accepted data is unchanged." }
                    null
                }
            }
            // Previously verified details do not wait for the official season catalogue's network I/O.
            val known = saved?.mapping?.takeIf { it.matches(saved.official.match) }
            val independent = known?.let { confirmed -> async {
                attempt("fotmob") { fotmob.details(confirmed.fotmobId).also { NativeLaLigaReconciliation.validate(it, confirmed, saved.official.match) } }?.let { it to now() }
            } }
            val incoming = attempt("official") {
                official.officialGame(id, match.nativeReference!!.season!!) ?.takeIf { NativeLaLigaSnapshotCache.sameFixture(it.match, match) }
                    ?: throw IOException("Official La Liga fixture unavailable")
            }
            val baseline = incoming?.takeIf { saved == null || it.checkedAt >= saved.official.checkedAt } ?: saved?.official
                ?: throw IOException("Official La Liga fixture couldn't be checked and no verified snapshot exists")
            var mapping = saved?.mapping?.takeIf { it.matches(baseline.match) } ?: mappings?.load(baseline.match)
            // Never guess a new historical mapping. A previously confirmed one can still restore rich detail.
            val historical = baseline.match.state == GameState.FINAL && at - baseline.match.startMillis > 86_400_000
            if (mapping == null && !historical) {
                mapping = attempt("mapping") {
                    NativeLaLigaReconciliation.discover(fotmob, baseline.match) ?: throw IOException("No unique verified match mapping")
                }
                mapping?.let { mappings?.save(baseline.match, it) }
            }
            if (mapping == null) errors["fotmob"] = "Detailed match data is unavailable without a unique verified FotMob mapping."
            val confirmed = mapping
            val response = if (independent != null) independent.await()?.takeIf { confirmed == known }?.let { pair ->
                attempt("fotmob") { NativeLaLigaReconciliation.validate(pair.first, requireNotNull(confirmed), baseline.match); pair }
            } else confirmed?.let { attempt("fotmob") {
                fotmob.details(it.fotmobId).also { detail -> NativeLaLigaReconciliation.validate(detail, it, baseline.match) } to now()
            } }
            val raw = response?.first
            val detailedAt = response?.second ?: at
            val fmEvidence = if (raw != null) attempt("score") {
                Evidence(baseline, Surface(detailedAt, NativeLaLigaSnapshotCache.scoped(raw, "score"))).also { it.sample(confirmed) }
            } else null
            // A changed fixture invalidates its old enrichment, never reassigns it to another match.
            val retained = saved?.takeIf { it.mapping == confirmed }
            val priorState = retained?.state() ?: saved?.state()?.takeIf { it.owner == NativeLaLigaAuthority.Owner.Official }
            val state = NativeLaLigaAuthority.apply(baseline.match, priorState,
                official = incoming?.let { NativeLaLigaAuthority.Sample(it.match, it.checkedAt) }, fotmob = fmEvidence?.sample(confirmed), now = now())
                ?: NativeLaLigaAuthority.apply(baseline.match, null, official = NativeLaLigaAuthority.Sample(baseline.match, baseline.checkedAt), now = now())!!
            val choices = listOfNotNull(Evidence(baseline), fmEvidence, retained?.accepted, retained?.fotmob, saved?.accepted?.takeIf { it.detail == null })
            val accepted = choices.firstOrNull { runCatching { it.sample(confirmed) == state.accepted }.getOrDefault(false) }
                ?: throw IOException("La Liga accepted score evidence missing")
            val latestFm = choices.firstOrNull { it.detail != null && runCatching { it.sample(confirmed) == state.fotmob }.getOrDefault(false) }
            val full = force || retained == null || at - retained.fullAt !in 0 until 120_000
            val resources = (retained?.surfaces ?: saved?.surfaces.orEmpty().filterKeys { it == "officials" }).toMutableMap()
            incoming?.let { current ->
                attempt("officials") { NativeLaLigaMapping.officials(current.payload, current.match) }?.let { assignments ->
                    if (assignments.isNotEmpty() || "officials" !in resources) resources["officials"] = Surface(current.checkedAt, current.payload)
                }
            }
            if (raw != null && confirmed != null) {
                suspend fun <T> decode(key: String, mapper: (kotlinx.serialization.json.JsonElement, Match, NativeLaLigaReconciliation.Mapping) -> T) {
                    attempt(key) { mapper(raw, baseline.match, confirmed) }?.let {
                        if (key == "pressure" && it is List<*> && it.isEmpty() && resources.containsKey(key)) {
                            errors[key] = "FotMob pressure has no reported samples; showing accepted data."
                            return@let
                        }
                        resources[key] = Surface(detailedAt, NativeLaLigaSnapshotCache.scoped(raw, key))
                    }
                }
                if (full || tab in setOf("Overview", "Timeline", "Events")) decode("events", NativeFotMobDetails::events)
                if (full || tab == "Lineups" || "lineups" !in resources) decode("lineups", NativeFotMobDetails::lineups)
                if (full || tab in setOf("Overview", "Team stats", "Stats")) decode("statistics", NativeFotMobDetails::statistics)
                if (full || tab == "Shots") decode("shots", NativeFotMobDetails::shots)
                if (full) decode("pressure", NativeFotMobDetails::pressure)
                if (full || tab == "Commentary") attempt("commentary") {
                    val ticker = fotmob.ticker(confirmed.fotmobId, raw) ?: throw IOException("Commentary not published")
                    NativeFotMobDetails.commentary(ticker, confirmed)
                    Surface(now(), ticker)
                }?.let { resources["commentary"] = it }
            }
            val result = Snapshot(baseline, latestFm, accepted, confirmed, resources,
                if (full && raw != null) at else retained?.fullAt ?: 0, now())
            val centre = result.details(match, saved = incoming == null && raw == null, errors = errors.snapshot())
                ?: throw IOException("La Liga snapshot identity mismatch")
            val presented = centre.copy(soccer = centre.soccer!!.copy(recoverableCoreFailure =
                errors.keys.any { it in setOf("official", "score") } || confirmed != null && errors.containsKey("fotmob")))
            memoryLock.locked {
                memory[id] = result; failures[id] = errors.snapshot()
                // Entry count alone is insufficient when an otherwise valid public detail approaches4MiB.
                memoryWeights[id] = result.surfaces.values.sumOf { it.payload.toString().length.toLong() } +
                    result.official.payload.toString().length + (result.fotmob?.detail?.payload?.toString()?.length ?: 0) +
                    result.accepted.official.payload.toString().length + (result.accepted.detail?.payload?.toString()?.length ?: 0)
                while (memory.size > 12 || memoryWeights.values.sum() > 6L * 1024 * 1024) {
                    val first = memory.keys.first(); memory.remove(first); failures.remove(first); memoryWeights.remove(first)
                }
            }
            snapshots?.save(id, result)
            presented
        } }
    }
    private fun label(key: String) = when (key) { "official" -> "LaLiga official fixture"; "officials" -> "LaLiga match officials"; "mapping" -> "LaLiga/FotMob match reconciliation"; else -> "FotMob $key" }
}
