package com.alexdiab.stadiatv.sports.pga

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.SportsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Public web frontend configuration, not a user's subscription or sign-in credential. */
data class PgaConfiguration(val graphql: String = "https://orchestrator.pgatour.com/graphql", val rest: String = "https://data-api.pgatour.com", val config: String = "https://orchestrator-config.pgatour.com", val frontendKey: String = com.alexdiab.stadiatv.foundation.ProviderDefaults.PGA_WEB_API_KEY)

class PgaRepository(private val http: SportsHttp, private val configuration: PgaConfiguration = PgaConfiguration()) : SportsProvider {
    override val league = requireNotNull(League.find("golf/pga"))
    override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Standings, SportsCapability.Field)
    private val scope = CoroutineScope(SupervisorJob() + sportsIO)
    private val pending = SportsMemo<String, Deferred<JsonElement>>()
    private data class Cached(val at: Long, val value: JsonElement)
    private val cache = LinkedHashMap<String,Cached>()
    private val cacheLock = SportsLock()
    private val gate = Mutex()
    private var lastDispatch = 0L
    private val hostGates = SportsMemo<String, Long>()
    private val headers get() = mapOf("Accept" to "application/graphql-response+json, application/json", "x-api-key" to configuration.frontendKey, "x-pgat-platform" to "web", "Origin" to "https://www.pgatour.com", "Referer" to "https://www.pgatour.com/", "User-Agent" to "BannerTV/1.0 (+PGA TOUR native client)")

    private suspend fun <T> dispatch(url: String, block: suspend () -> T): T {
        val host = url.substringAfter("://").substringBefore('/')
        val notBefore = hostGates[host] ?: 0
        if (notBefore > nowMillis()) throw IOException("PGA data is temporarily unavailable. Try again later.")
        gate.withLock { delay((lastDispatch + 400 - nowMillis()).coerceAtLeast(0)); lastDispatch = nowMillis() }
        try { return block() }
        catch (error: HttpStatusException) { if (error.code in setOf(401, 403, 429)) hostGates[host] = nowMillis() + if (error.code == 429) ((error.retryAfterSeconds ?: 30) * 1000).coerceAtLeast(1000) else 300000; throw error }
    }
    suspend fun query(operation: String, variables: JsonObject = JsonObject(emptyMap()), ttl: Long = 30000): JsonElement {
        val key = operation + ':' + variables.toString()
        cacheLock.locked { cache.remove(key)?.also { cache[key] = it } }?.takeIf { nowMillis() - it.at < ttl }?.let { return it.value }
        val created = scope.async(start = CoroutineStart.LAZY) {
            val document = PgaQueries.documents[operation] ?: throw IllegalArgumentException("Unknown PGA query")
            val request = buildJsonObject { put("operationName", operation); put("query", document); put("variables", variables) }
            val response = dispatch(configuration.graphql) { parseJson(http.postJson(configuration.graphql, request.toString(), headers)) }
            if (response["errors"].array.isNotEmpty()) throw IOException("PGA data is unavailable for this section.")
            val result = response["data"] ?: throw IOException("PGA returned no data")
            val weight = result.toString().length
            if (weight <= 4 * 1024 * 1024) cacheLock.locked {
                cache[key] = Cached(nowMillis(), result)
                while (cache.size > 24 || cache.size > 1 && cache.values.sumOf { it.value.toString().length.toLong() } > 4 * 1024 * 1024) cache.remove(cache.keys.first())
            }
            result
        }
        val existing = pending.putIfAbsent(key, created)
        val shared = if (existing != null) { created.cancel(); existing } else { created.invokeOnCompletion { pending.remove(key, created) }; created.start(); created }
        return shared.await()
    }
    private suspend fun compressed(operation: String, field: String, variables: JsonObject, ttl: Long = 30000): JsonElement {
        val encoded = query(operation, variables, ttl)[field]["payload"].stringOrNull() ?: throw IOException("PGA has not published this section yet.")
        return PgaPayload.decode(encoded)
    }
    suspend fun currentIds(): List<String> = dispatch(configuration.config) { http.getJson(configuration.config + "/web-config", headers, 900000, 0)["defaultTournaments"]["R"].array.mapNotNull { it["id"].stringOrNull() } }
    suspend fun tournaments(ids: List<String>): List<GolfTournament> = PgaMapper.tournaments(query("Tournaments", buildJsonObject { put("ids", JsonArray(ids.map(::JsonPrimitive))) }, 60000))
    suspend fun leaderboard(id: String): List<GolfRow> = PgaMapper.leaderboard(compressed("LeaderboardCompressedV3", "leaderboardCompressedV3", buildJsonObject { put("leaderboardCompressedV3Id", id) }))
    suspend fun currentLeaders(id: String): JsonElement = compressed("CurrentLeadersCompressed", "currentLeadersCompressed", buildJsonObject { put("tournamentId", id) })
    suspend fun courses(id: String): List<GolfCourse> = PgaMapper.courses(query("CourseStats", buildJsonObject { put("tournamentId", id) }, 180000)["courseStats"])
    suspend fun teeTimes(id: String): List<GolfTeeGroup> = PgaMapper.teeTimes(compressed("TeeTimesCompressedV2", "teeTimesCompressedV2", buildJsonObject { put("teeTimesCompressedV2Id", id) }, 120000))
    suspend fun weather(id: String): JsonElement? = query("Weather", buildJsonObject { put("tournamentId", id) }, 300000)["weather"]
    suspend fun coverage(id: String): List<JsonElement> = query("Coverage", buildJsonObject { put("tournamentId", id) }, 300000)["coverage"]["coverageType"].array
    suspend fun cup(year: Int): List<JsonElement> = PgaMapper.cup(query("TourCupSplit", buildJsonObject { put("tourCode", "R"); put("id", "02671"); put("year", year) }, 300000)["tourCupSplit"])
    suspend fun scorecard(id: String, player: String): List<GolfRoundScore> = PgaMapper.scorecard(compressed("ScorecardCompressedV3", "scorecardCompressedV3", buildJsonObject { put("tournamentId", id); put("playerId", player) }))
    suspend fun shots(id: String, player: String, round: Int): List<GolfShotHole> = PgaMapper.shots(compressed("shotDetailsV4Compressed", "shotDetailsV4Compressed", buildJsonObject { put("tournamentId", id); put("playerId", player); put("round", round); put("includeRadar", false) }))
    suspend fun hole(id: String, course: String, hole: Int): JsonElement? = query("HoleDetails", buildJsonObject { put("tournamentId", id); put("courseId", course); put("hole", hole) }, 180000)["holeDetails"]
    suspend fun season(year: Int): List<GolfSchedule> {
        val raw = dispatch(configuration.rest) { http.getJson(configuration.rest + "/schedule/R/$year", headers, 43200000, 0) }
        return raw["tournaments"].array.mapNotNull { t -> t["tournamentId"].stringOrNull()?.let { GolfSchedule(it, t["name"].string(), t["displayDate"].stringOrNull(), t["status"].stringOrNull(), t["purse"].stringOrNull(), t["standings"]["value"].stringOrNull(), t["champions"][0]["displayName"].stringOrNull(), t["courseData"]["name"].stringOrNull(), listOf(t["courseData"]["city"].string(), t["courseData"]["stateCode"].string(), t["courseData"]["country"].string()).filter(String::isNotBlank).joinToString(", ")) } }
    }
    /** Native ids are never inferred from an ESPN integer. Resolve via explicit tournament metadata. */
    suspend fun resolve(match: Match): String? {
        if (Regex("R\\d{7}").matches(match.id)) return match.id
        val candidates = tournaments(currentIds())
        fun normalized(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        return candidates.filter { normalized(it.name) == normalized(match.name) || normalized(it.name) == normalized(match.shortName) }.singleOrNull()?.id
    }
    override suspend fun scores(date: Long?): List<Match> {
        if (date != null && SportsTime.compact(nowMillis()) != SportsTime.compact(date)) return emptyList()
        val ids = currentIds(); if (ids.isEmpty()) return emptyList()
        return tournaments(ids).map(::match)
    }
    override suspend fun schedule(start: Long, days: Int): List<Match> = if (nowMillis() in start..(start + days.coerceAtLeast(1) * 86400000L) || nowMillis() - start in 0..86400000L) scores(null) else emptyList()
    override suspend fun standings(): List<StandingGroup> = listOf(StandingGroup("FedExCup standings", cup(SportsTime.format("yyyy", nowMillis()).toInt()).mapNotNull { row -> val id = row["id"].stringOrNull() ?: return@mapNotNull null; val name = row["displayName"].stringOrNull() ?: listOf(row["firstName"].string(), row["lastName"].string()).joinToString(" "); StandingRow(id, name, row["country"].string(name), null, row["pointData"]["official"].string("–") + " pts", mapOf("rank" to row["rankingData"]["official"].string(), "projectedRank" to row["rankingData"]["projected"].string(), "projectedPoints" to row["pointData"]["projected"].string())) }))
    override suspend fun field(): List<Racer> = currentIds().flatMap { leaderboard(it) }.map { Racer(Athlete(it.player.id, it.player.name), it.position.orEmpty(), it.total.orEmpty(), it.player.country.orEmpty()) }
    fun match(t: GolfTournament): Match { val side = TeamSide(t.courses.firstOrNull { it.host }?.name ?: t.name, "PGA", "PGA", t.logo); return Match(t.id, league, nowMillis(), t.name, t.name, if (t.live) GameState.LIVE else if (t.status in setOf("Final", "Round complete")) GameState.FINAL else GameState.PRE, t.statusDisplay ?: t.status, side, side, venue = t.courses.firstOrNull { it.host }?.name) }
}
