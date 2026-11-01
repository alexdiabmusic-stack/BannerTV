package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

class NativeMls(private val client: NativeMlsClient, private val now: () -> Long = ::nowMillis,
    private val snapshots: NativeMlsSnapshotCache? = null, private val upcoming: NativeMlsUpcomingCache? = null) {
    private data class Cooldown(val until: Long, val transport: Boolean)
    private val cooldown = SportsMemo<String, Cooldown>()
    private val unpublished = SportsMemo<String, Long>()
    fun onNetworkRestored() { cooldown.transaction { cooldown.removeWhere { it.transport } } }
    suspend fun cached(match: Match): GameCentre? {
        val id = identity(match)
        return snapshots?.load(id)?.details(match) ?: upcoming?.load(id)?.takeIf { sameFixture(it.match(), match) }?.let { scheduled(match, it) }
    }
    suspend fun cachedMatch(id: String) = snapshots?.load(id)?.match() ?: upcoming?.load(id)?.match()
    suspend fun rememberSchedule(rows: List<JsonElement>, at: Long) { upcoming?.save(rows, at) }
    private fun sameFixture(candidate: Match?, expected: Match) = candidate?.key == expected.key && candidate.nativeReference == expected.nativeReference && candidate.home.teamId == expected.home.teamId && candidate.away.teamId == expected.away.teamId
    private fun scheduled(match: Match, entry: NativeMlsUpcomingCache.Entry?): GameCentre {
        val cached = entry?.takeIf { sameFixture(it.match(), match) }
        return GameCentre(liveMatch = cached?.match() ?: match,
            providerStamp = cached?.let { ProviderStamp("MLS", match.nativeReference!!.gameId, it.at, revision = "schedule") },
            soccer = SoccerDetail(surfaceUpdatedAt = cached?.let { mapOf("schedule" to it.at) }.orEmpty(), savedSnapshot = cached != null, overviewNotPublished = true),
            gameInfo = listOf("Source" to "MLS announced schedule"))
    }
    private fun identity(match: Match): String {
        val id = NativeMlsMapping.rawId(match.id, "MAT")
        require(match.league == NativeMlsMapping.league && match.id.startsWith("mls:") && match.nativeReference?.provider == "MLS" && match.nativeReference.gameId == id)
        return requireNotNull(id)
    }
    suspend fun load(match: Match, previous: GameCentre?, force: Boolean = false, tab: String = "Overview"): GameCentre = coroutineScope {
        val id = identity(match); val at = now(); val saved = snapshots?.load(id)
        val prior = previous?.takeIf { it.providerStamp?.provider == "MLS" && it.providerStamp.gameId == id && it.liveMatch?.key == match.key } ?: saved?.details(match)
        val thin = upcoming?.load(id)?.takeIf { sameFixture(it.match(), match) }
        if (match.state == GameState.PRE && (unpublished[id] ?: 0) > at && prior?.soccer?.surfaceUpdatedAt?.containsKey("overview") != true) return@coroutineScope scheduled(match, thin)
        val raw = try { client.get("/matches/$id") } catch (error: HttpStatusException) {
            if (error.code == 404 && match.state == GameState.PRE && prior?.soccer?.surfaceUpdatedAt?.containsKey("overview") != true) {
                unpublished.transaction {
                    if (unpublished.size >= 128 && !unpublished.containsKey(id)) unpublished.snapshot().entries.minByOrNull { it.value }?.key?.let { unpublished.remove(it) }
                    unpublished[id] = at + 60_000
                }
                return@coroutineScope scheduled(match, thin)
            }
            throw error
        }
        unpublished.remove(id)
        val current = NativeMlsMapping.game(raw, true)?.takeIf { it.nativeReference == match.nativeReference && it.home.teamId == match.home.teamId && it.away.teamId == match.away.teamId }
            ?: throw IOException("MLS overview game/club/season identity mismatch")
        val season = raw["match_information"]["season_id"].string()
        val full = force || prior?.soccer == null || at - prior.soccer.lastFullRefresh !in 0 until 120_000
        val errors = SportsMemo(prior?.soccer?.errors.orEmpty())
        val accepted = SportsMemo<String, JsonElement>()
        accepted["overview"] = raw
        fun <T> decode(kind: String, payload: JsonElement, mapper: (JsonElement) -> T): T? = try {
            mapper(payload).also { errors.remove(kind); accepted[kind] = payload }
        } catch (error: Exception) { errors[kind] = "MLS $kind format was unavailable; showing available data."; null }
        suspend fun <T> surface(kind: String, path: String, query: Map<String, String> = emptyMap(), fetch: (suspend () -> JsonElement)? = null, mapper: (JsonElement) -> T): T? {
            if ((cooldown[kind]?.until ?: 0) > now()) { errors[kind] = "MLS $kind is cooling down; showing available data."; return null }
            return try { decode(kind, fetch?.invoke() ?: client.get(path, query), mapper) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val until = now() + if (error is HttpStatusException) NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds) else 60_000
                val transport = error is IOException && error !is HttpStatusException
                cooldown.transaction {
                    val old = cooldown[kind]
                    cooldown[kind] = if (old != null && old.until > now()) Cooldown(maxOf(old.until, until), old.transport && transport) else Cooldown(until, transport)
                }
                errors[kind] = "MLS $kind couldn't refresh; showing available data."; null
            }
        }
        val lineups = if (full || "lineups" !in prior?.soccer?.surfaceUpdatedAt.orEmpty()) decode("lineups", raw, NativeMlsMapping::lineups) else null
        if (lineups.isNullOrEmpty() && prior?.soccer?.lineups?.isNotEmpty() == true) accepted.remove("lineups")
        val officials = if (full) decode("officials", raw, NativeMlsMapping::officials) else null
        if (officials.isNullOrEmpty() && prior?.soccer?.officials?.isNotEmpty() == true) accepted.remove("officials")
        val events = async { if (full || tab in setOf("Overview", "Timeline", "Events")) surface("events", "/matches/$id/key_events",
            fetch = { client.complete("/matches/$id/key_events", "events", mapOf("per_page" to "100")) { NativeMlsMapping.resourceIdentity(it["match_info"], current, season) } }) {
                NativeMlsDetails.events(it, current, season, raw)
            } else null }
        val statistics = async { if (full || tab in setOf("Overview", "Team stats", "Stats")) surface("statistics", "/statistics/clubs/matches/$id") { NativeMlsDetails.stats(it, current, season) } else null }
        val players = async { if (full || tab in setOf("Team stats", "Stats")) surface("playerStats", "/statistics/players/matches/$id", mapOf("per_page" to "100")) { NativeMlsDetails.playerStats(it, current, season) } else null }
        val comments = async { if (full || tab == "Commentary") surface("commentary", "/matches/$id/commentary", mapOf("language" to "en")) { NativeMlsDetails.comments(it, current, season) } else null }
        val eventValues = events.await(); val statValues = statistics.await(); val playerValues = players.await(); val commentValues = comments.await()
        val result = NativeSoccerReducer.apply(prior, NativeSoccerUpdate(current, id, at, "MLS", "MLS", full,
            events = eventValues, lineups = lineups, statistics = statValues, officials = officials, commentary = commentValues,
            nextCursor = accepted["commentary"]?.let { NativeMlsClient.cursor(it) }, errors = errors.snapshot(), playerStats = playerValues,
            gameInfo = listOfNotNull("Source" to "MLS match overview", current.venue?.let { "Venue" to it }, raw["environment"]["number_of_spectators"].stringOrNull()?.let { "Attendance" to it })))
            ?: throw IOException("MLS game update rejected")
        if (result !== prior) snapshots?.save(id, (saved ?: NativeMlsSnapshotCache.Snapshot()).accept(accepted.snapshot(), at, full))
        result
    }
    suspend fun olderComments(match: Match, cursor: String): Pair<List<SoccerComment>, String?> {
        val id = identity(match); require(cursor.isNotBlank() && cursor.length <= 2048)
        val saved = snapshots?.load(id)
        if ((saved?.pages ?: 0) >= 10) return emptyList<SoccerComment>() to null
        val overview = saved?.surfaces?.get("overview")?.payload ?: client.get("/matches/$id", ttl = 15_000)
        val current = NativeMlsMapping.game(overview, true)?.takeIf { it.key == match.key && it.nativeReference == match.nativeReference } ?: throw IOException("MLS commentary game mismatch")
        val raw = client.get("/matches/$id/commentary", mapOf("language" to "en", "page_token" to cursor), 300_000)
        val comments = NativeMlsDetails.comments(raw, current, overview["match_information"]["season_id"].string())
        val next = NativeMlsClient.cursor(raw)
        if (next == cursor) throw IOException("MLS commentary cursor repeated")
        saved?.let { snapshots?.save(id, it.older(raw)) }
        return comments to next
    }
    suspend fun table(match: Match): SoccerTable {
        val id = identity(match)
        val overview = snapshots?.load(id)?.surfaces?.get("overview")?.payload
        val thin = upcoming?.load(id)?.takeIf { sameFixture(it.match(), match) }
        val reference = match.nativeReference!!
        val competition = reference.seasonType?.takeIf { it in NativeMlsClient.COMPETITIONS } ?: throw IOException("MLS table competition identity missing")
        val year = reference.season ?: throw IOException("MLS table season identity missing")
        val season = overview?.get("match_information")?.get("season_id")?.stringOrNull() ?: thin?.payload?.get("season_id")?.stringOrNull()
            ?: client.season(year, competition) ?: throw IOException("MLS table season unavailable")
        val live = match.state == GameState.LIVE
        val raw = client.get("/competitions/$competition/seasons/$season/standings", mapOf("category" to "conference", "is_live" to live.toString()), if (live) 20_000 else 300_000)
        return NativeMlsStandings.table(raw, year, season, competition, live, now())
    }
}
