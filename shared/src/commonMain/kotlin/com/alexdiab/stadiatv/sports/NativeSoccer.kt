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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/** PulseLive's structured EPL feed. Team/date identity is resolved explicitly; ESPN IDs are never sent to SDP. */
class NativeSoccer(private val http: SportsHttp, private val base: String = "https://sdp-prem-prod.premier-league-prod.pulselive.com", private val now: () -> Long = ::nowMillis,
    private val client: NativePremierLeagueClient = NativePremierLeagueClient(http, base, now),
    private val snapshots: NativeSoccerSnapshotCache? = null) {
    private data class Cooldown(val until: Long, val transportOnly: Boolean)
    private val cooldown = SportsMemo<String, Cooldown>()
    fun onNetworkRestored() { cooldown.transaction { cooldown.removeWhere { it.transportOnly } } }
    private fun backoff(kind: String, error: Exception) {
        val transportOnly = error is IOException && error !is HttpStatusException
        val time = now()
        val duration = if (error is HttpStatusException) NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds) else 60_000
        cooldown.transaction {
            val old = cooldown[kind]
            cooldown[kind] = if (old != null && old.until > time) Cooldown(maxOf(old.until, time + duration), old.transportOnly && transportOnly)
            else Cooldown(time + duration, transportOnly)
        }
    }

    suspend fun cached(match: Match): GameCentre? {
        if (match.nativeReference?.provider != "EPL") return null
        val id = NativePremierLeagueMapping.rawId(match.id)?.takeIf { it == match.nativeReference.gameId } ?: return null
        return snapshots?.load(id)?.details(match)
    }
    suspend fun cachedMatch(id: String): Match? = snapshots?.load(id)?.match()

    suspend fun load(match: Match, previous: GameCentre?, force: Boolean = false, tab: String = "Overview"): GameCentre? = coroutineScope {
        if (match.league.path != "soccer/eng.1") return@coroutineScope null
        val season = seasonFor(match.startMillis)
        fun day(offset: Int): String = NativePremierLeagueMapping.day(NativePremierLeagueMapping.addDays(match.startMillis, offset))
        val nativeId = match.nativeReference?.takeIf { it.provider.equals("EPL", true) }?.gameId
        if (match.nativeReference != null && (nativeId == null || NativePremierLeagueMapping.rawId(match.id) != nativeId)) return@coroutineScope null
        val id = nativeId ?: previous?.takeIf { it.liveMatch == null || it.liveMatch.key == match.key }?.providerStamp?.takeIf { it.provider == "Premier League" }?.gameId ?: run {
            val schedule = withTimeoutOrNull(4000) { client.matches(season, day(-1), day(1), 300_000) } ?: return@coroutineScope null
            schedule.filter { raw -> teamMatches(raw["awayTeam"], match.away) && teamMatches(raw["homeTeam"], match.home) && parseTime(raw["kickoff"].string())?.let { abs(it - match.startMillis) < 3 * 3_600_000L } == true }.singleOrNull()?.get("matchId").stringOrNull()
        } ?: return@coroutineScope null
        if (!id.matches(Regex("[A-Za-z0-9_-]+"))) return@coroutineScope null
        val saved = if (nativeId != null) snapshots?.load(id) else null
        val prior = previous?.takeIf { it.providerStamp?.provider == "Premier League" && it.providerStamp.gameId == id && (it.liveMatch == null || it.liveMatch.key == match.key) }
            ?: saved?.details(match)
        val requested = now()
        val raw = withTimeoutOrNull(4000) { client.get("/api/v2/matches/$id") } ?: return@coroutineScope null
        if (raw["matchId"].string() != id || !teamMatches(raw["awayTeam"], match.away) || !teamMatches(raw["homeTeam"], match.home)) return@coroutineScope null
        if (prior?.providerStamp?.requestedAt?.let { it > requested } == true) return@coroutineScope prior
        val priorSoccer = prior?.soccer
        val full = force || priorSoccer == null || priorSoccer.lastFullRefresh <= 0 || requested - priorSoccer.lastFullRefresh !in 0 until 120_000
        val errors = SportsMemo(priorSoccer?.errors.orEmpty())
        suspend fun surface(path: String, kind: String, ttl: Long = 0, timeout: Long = 3500, query: Map<String, String> = emptyMap()): JsonElement? {
            if ((cooldown[kind]?.until ?: 0) > now()) { errors[kind] = "Premier League $kind is cooling down; showing available data."; return null }
            return try {
                (withTimeoutOrNull(timeout) { client.get(path, query, ttl) } ?: throw IOException("Surface timed out")).also { errors.remove(kind) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                backoff(kind, error)
                errors[kind] = "Premier League $kind couldn't refresh; showing available data."
                null
            }
        }
        val lineups = async { if (full || "lineups" !in priorSoccer?.surfaceUpdatedAt.orEmpty()) surface("/api/v3/matches/$id/lineups", "lineups") else null }
        val commentary = async { if (full || tab == "Commentary") surface("/api/v1/matches/$id/commentary", "commentary", query = mapOf("_limit" to "100", "_sort" to "timestamp:desc")) else null }
        val eventResponse = async { if (full || tab in setOf("Overview", "Events", "Timeline")) surface("/api/v1/matches/$id/events", "events") else null }
        val statsResponse = async { if (full || tab in setOf("Overview", "Team stats", "Stats")) surface("/api/v3/matches/$id/stats", "statistics") else null }
        val officialsResponse = async { if (full) surface("/api/v1/matches/$id/officials", "officials") else null }
        val teamLineups = lineups.await()
        val rawComments = commentary.await()
        val validLineups = teamLineups?.takeIf { NativeSoccerDetails.validLineups(it, id) }
        val incomingLineups = validLineups?.let { listOfNotNull(lineup(it["away_team"] ?: it["awayTeam"], false), lineup(it["home_team"] ?: it["homeTeam"], true)) }
        if (teamLineups != null && validLineups == null) errors["lineups"] = "Premier League lineup format was unavailable; showing available players."
        val validComments = rawComments?.takeIf { it["data"] is kotlinx.serialization.json.JsonArray && it["data"].array.size <= 500 && it["matchId"].stringOrNull()?.let { returned -> returned == id } != false }
        if (rawComments != null && validComments == null) errors["commentary"] = "Premier League commentary format was unavailable; showing available comments."
        val rawEvents = eventResponse.await()
        var names = priorSoccer?.playerNames.orEmpty() + (incomingLineups ?: priorSoccer?.lineups.orEmpty()).flatMap { it.players }.associate { it.player.id to it.player.name }
        val missing = (NativeSoccerDetails.referencedPlayers(rawEvents) - names.keys).take(80)
        if (missing.isNotEmpty()) surface("/api/v2/players-by-id", "players", 300_000, 1500, mapOf("id" to missing.joinToString(",")))?.let { names = names + NativeSoccerDetails.playerNames(it) }
        val events = NativeSoccerDetails.events(rawEvents, match, id, names, raw.path("homeTeam", "id").stringOrNull(), raw.path("awayTeam", "id").stringOrNull())
        val rawStats = statsResponse.await()
        val stats = if (rawStats is kotlinx.serialization.json.JsonArray && rawStats.isEmpty()) emptyList() else NativeSoccerDetails.stats(rawStats, raw.path("homeTeam", "id").string(), raw.path("awayTeam", "id").string())
        if (rawEvents != null && events == null) errors["events"] = "Premier League events were incomplete; showing available data."
        if (stats == null && rawStats != null) errors["statistics"] = "Premier League statistic format was unavailable; showing available data."
        val rawOfficials = officialsResponse.await()
        val officials = NativeSoccerDetails.officials(rawOfficials, id)
        if (officials == null && rawOfficials != null) errors["officials"] = "Premier League officials were unavailable; showing available data."
        val mapped = NativePremierLeagueMapping.game(raw)
        val status = NativePremierLeagueMapping.status(raw["period"].string(), raw["clock"].string())
        val state = status.state
        fun score(side: String) = raw[side]["score"].stringOrNull()?.toIntOrNull()?.takeIf { state != GameState.PRE && it in 0..100 }
        val h = score("homeTeam"); val a = score("awayTeam")
        val nativeMatch = if (nativeId != null) mapped ?: return@coroutineScope null else match.copy(state = state,
            away = match.away.copy(score = a?.toString(), isWinner = state == GameState.FINAL && a != null && h != null && a > h),
            home = match.home.copy(score = h?.toString(), isWinner = state == GameState.FINAL && a != null && h != null && h > a),
            statusDetail = status.label, live = mapped?.live ?: LiveContext.EMPTY)
        val update = NativeSoccerUpdate(nativeMatch, id, requested, full = full, events = events, lineups = incomingLineups,
            statistics = stats, officials = officials, commentary = validComments?.let { comments(it, id) },
            nextCursor = validComments.path("pagination", "_next").stringOrNull(), playerNames = names, errors = errors.snapshot(),
            gameInfo = listOfNotNull(raw["ground"].stringOrNull()?.let { "Venue" to it }, raw["attendance"].stringOrNull()?.let { "Attendance" to it }, raw["matchweek"].stringOrNull()?.let { "Matchweek" to it }))
        val incoming = NativeSoccerReducer.apply(prior, update) ?: return@coroutineScope prior
        if (nativeId != null && incoming !== prior) {
            val payloads = buildMap {
                put("overview", raw)
                if (validLineups != null && (!incomingLineups.isNullOrEmpty() || priorSoccer?.lineups.isNullOrEmpty())) put("lineups", validLineups)
                if (events != null && rawEvents != null) put("events", rawEvents)
                if (stats != null && rawStats != null && (stats.isNotEmpty() || prior?.teamStats.isNullOrEmpty())) put("statistics", rawStats)
                if (officials != null && rawOfficials != null && (officials.isNotEmpty() || priorSoccer?.officials.isNullOrEmpty())) put("officials", rawOfficials)
                if (validComments != null) put("commentary", validComments)
            }
            snapshots?.save(id, (saved ?: NativeSoccerSnapshotCache.Snapshot()).accept(payloads, requested, incoming))
        }
        incoming
    }

    suspend fun table(match: Match): SoccerTable {
        require(match.league.path == "soccer/eng.1")
        if ((cooldown["standings"]?.until ?: 0) > now()) throw IOException("Standings cooldown")
        val season = seasonFor(match.startMillis); val live = match.state == GameState.LIVE
        try {
            val raw = withTimeoutOrNull(6000) { client.get("/api/v5/competitions/8/seasons/$season/standings", mapOf("live" to live.toString()), if (live) 20_000 else 300_000) } ?: throw IOException("Standings timed out")
            return NativeSoccerDetails.table(raw, season, live, now()) ?: throw IOException("Standings format unavailable")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { backoff("standings", error); throw error }
    }

    suspend fun olderComments(gameId: String, cursor: String): Pair<List<SoccerComment>, String?> {
        require(gameId.matches(Regex("[A-Za-z0-9_-]+")))
        require(cursor.length <= 2048)
        val saved = snapshots?.load(gameId)
        if ((saved?.commentaryPages ?: 0) >= 10) return emptyList<SoccerComment>() to null
        val raw = client.get("/api/v1/matches/$gameId/commentary", mapOf("_limit" to "50", "_sort" to "timestamp:desc", "_next" to cursor), 300_000)
        if (raw["data"] !is kotlinx.serialization.json.JsonArray || raw["matchId"].stringOrNull()?.let { it != gameId } == true) throw IOException("Commentary identity/format unavailable")
        val next = raw.path("pagination", "_next").stringOrNull()?.takeUnless { it == cursor }
        if (next != null && next.length > 2048) throw IOException("Commentary cursor exceeds limit")
        val page = kotlinx.serialization.json.JsonObject(raw.obj.orEmpty() + ("pagination" to kotlinx.serialization.json.JsonObject(mapOf("_next" to (next?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)))))
        saved?.let { snapshots?.save(gameId, it.withOlderComments(page)) }
        return comments(page, gameId) to next
    }

    companion object {
        fun seasonFor(start: Long): Int = NativePremierLeagueMapping.season(start)
        private fun normalized(s: String) = decomposeText(s).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("\\b(fc|afc|football club)\\b|[^a-z0-9]"), "")
        fun teamMatches(raw: JsonElement?, side: TeamSide): Boolean {
            side.teamId?.takeIf { it.startsWith("epl:") }?.let { return NativePremierLeagueMapping.rawId(it) == NativePremierLeagueMapping.id(raw["id"]) }
            val actual = listOf(raw["name"].string(), raw["shortName"].string()).map(::normalized).filter { it.length >= 4 }.toSet()
            return listOf(side.displayName, side.shortName).map(::normalized).any { it in actual }
        }
        private fun parseTime(value: String): Long? = NativePremierLeagueMapping.kickoff(value)
        fun lineup(raw: JsonElement?, home: Boolean): SoccerLineup? {
            val rows = raw.path("formation", "lineup").array.map { it.array.mapNotNull { player -> player.stringOrNull() } }
            val positions = rows.flatMapIndexed { row, ids -> ids.mapIndexed { column, id -> id to (row to column) } }.toMap()
            val subs = raw.path("formation", "subs").array.map { it.string() }.toSet()
            val players = raw["players"].array.mapNotNull { player ->
                val id = player["id"].stringOrNull() ?: return@mapNotNull null
                val name = listOf(player["firstName"].string(), player["lastName"].string()).filter(String::isNotBlank).joinToString(" ")
                val reference = SportPlayer(id, name.ifBlank { "Unknown player" }, player["shirtNum"].string(), player["position"].string(), "https://www.premierleague.com/players/$id")
                val position = positions[id]
                SoccerLineupPlayer(reference, if (rows.isEmpty()) id !in subs else position != null, player["isCaptain"].bool(), position?.first, position?.second,
                    membershipKnown = position != null || id in subs)
            }.distinctBy { it.player.id }
            if (players.isEmpty()) return null
            val manager = raw["managers"].array.firstOrNull()
            return SoccerLineup(home, raw.path("formation", "formation").stringOrNull(), players, listOf(manager["firstName"].string(), manager["lastName"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank))
        }
        fun comments(raw: JsonElement?, gameId: String): List<SoccerComment> = raw["data"].array.mapNotNull { entry ->
            val text = entry["comment"].stringOrNull() ?: return@mapNotNull null
            val timestamp = entry["timestamp"].string(); val type = entry["type"].string()
            SoccerComment("$gameId|$timestamp|$type", entry["time"].string().trim(), text, type, parseTime(timestamp))
        }.distinctBy { it.id }
    }
}
