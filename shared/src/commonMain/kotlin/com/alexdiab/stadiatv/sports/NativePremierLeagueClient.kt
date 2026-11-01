package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** EPLPulseLiveClient's bounded public SDP routes. No key, auth bypass, FPL IDs or name-based profile lookup. */
class NativePremierLeagueClient(private val http: SportsHttp,
    private val base: String = "https://sdp-prem-prod.premier-league-prod.pulselive.com",
    private val now: () -> Long = ::nowMillis) {
    private val gate = Semaphore(2)
    @Volatile private var blockedUntil = 0L

    suspend fun get(path: String, query: Map<String, String> = emptyMap(), ttl: Long = 0): JsonElement = gate.withPermit {
        require(path.matches(Regex("/api/v[1-5]/[A-Za-z0-9_/-]+")) && ".." !in path && query.size <= 16 && query.values.all { it.length <= 4096 })
        if (blockedUntil > now()) throw IOException("Premier League gateway is cooling down")
        val url = requestURL((base.trimEnd('/') + path), query)
        try {
            withTimeout(8_000) { http.getJson(url.toString(), headers = mapOf("Accept" to "application/json", "User-Agent" to "BannerTV/1.0"),
                ttlMillis = ttl, retries = 0, maxResponseBytes = 4L * 1024 * 1024) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) {
            if (error.code in setOf(403, 429)) blockedUntil = maxOf(blockedUntil, now() + maxOf(if (error.code == 403) 300_000 else 60_000,
                (error.retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000))
            throw error
        }
    }

    suspend fun matches(season: Int, after: String, before: String, ttl: Long): List<JsonElement> {
        require(season in 1900..2200)
        val result = LinkedHashMap<String, JsonElement>()
        val seen = HashSet<String>()
        var cursor: String? = null
        repeat(10) {
            val query = linkedMapOf("competition" to "8", "season" to season.toString(), "_limit" to "100", "_sort" to "kickoff:asc", "kickoff>" to after, "kickoff<" to before)
            cursor?.let { query["_next"] = it }
            val raw = get("/api/v2/matches", query, ttl)
            val rows = raw["data"] as? JsonArray ?: throw IOException("Premier League match-list format unavailable")
            if (rows.size > 100) throw IOException("Premier League page exceeds its requested limit")
            rows.forEach { row ->
                val id = NativePremierLeagueMapping.id(row["matchId"]) ?: throw IOException("Premier League match identity missing")
                if (row["competitionId"].stringOrNull()?.let { it != "8" } == true || NativePremierLeagueMapping.reportedSeasons(row).any { it != season.toString() }) throw IOException("Premier League match-list competition/season mismatch")
                result[id] = row
            }
            cursor = raw["pagination"]["_next"].stringOrNull()?.takeIf(String::isNotBlank)
            if (cursor == null) return result.values.toList()
            if (cursor.length > 2048 || !seen.add(cursor)) throw IOException("Premier League returned a repeated/invalid page cursor")
        }
        throw IOException("Premier League schedule was truncated; narrower dates are required")
    }
}
