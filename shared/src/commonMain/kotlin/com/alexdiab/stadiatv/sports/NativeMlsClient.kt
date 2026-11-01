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

/** Verified iOS Sportec routes, not the obsolete /v1/Opta API. Anonymous public data only. */
class NativeMlsClient(private val http: SportsHttp, private val base: String = "https://stats-api.mlssoccer.com",
    private val now: () -> Long = ::nowMillis) {
    private val gate = Semaphore(2)
    @Volatile private var blockedUntil = 0L
    private data class Seasons(val at: Long, val years: Map<Int, String>)
    private val seasons = SportsMemo<String, Seasons>()
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), ttl: Long = 0): JsonElement = gate.withPermit {
        require(path.matches(Regex("/[A-Za-z0-9_/-]+")) && ".." !in path && query.size <= 16 && query.values.all { it.length <= 2048 })
        if (blockedUntil > now()) throw IOException("MLS gateway is cooling down")
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
    suspend fun season(year: Int, competition: String = REGULAR): String? {
        require(year in 1900..2200 && competition in COMPETITIONS)
        seasons[competition]?.takeIf { now() - it.at in 0 until 86_400_000 }?.let { return it.years[year] }
        val raw = get("/competitions/$competition/seasons", ttl = 86_400_000)
        val rows = raw["seasons"] as? JsonArray ?: throw IOException("MLS season directory unavailable")
        if (rows.size > 200) throw IOException("MLS season directory exceeds limit")
        val years = LinkedHashMap<Int, String>()
        for (row in rows) {
            if (row["competition_id"].stringOrNull()?.let { it != competition } == true) throw IOException("MLS season competition mismatch")
            val y = row["season"].string().toIntOrNull()?.takeIf { it in 1900..2200 } ?: throw IOException("MLS season year missing")
            val id = NativeMlsMapping.rawId(row["season_id"].string(), "SEA") ?: throw IOException("MLS season identity missing")
            if (years.put(y, id)?.let { it != id } == true) throw IOException("Ambiguous MLS season year")
        }
        seasons[competition] = Seasons(now(), years)
        return years[year]
    }
    /** Reject partial/repeated pagination rather than returning a misleading complete directory/schedule. */
    suspend fun pages(path: String, array: String, query: Map<String, String>, ttl: Long,
        maxPages: Int = 20, maxRows: Int = 2000): List<JsonElement> {
        val rows = ArrayList<JsonElement>(); val seen = HashSet<String>(); var cursor: String? = null; var characters = 0L
        repeat(maxPages) {
            val raw = get(path, query + listOfNotNull(cursor?.let { "page_token" to it }).toMap(), ttl)
            characters += raw.toString().length
            if (characters > 4L * 1024 * 1024) throw IOException("MLS paged $array exceeds aggregate memory bound")
            val page = raw[array] as? JsonArray ?: throw IOException("MLS $array format unavailable")
            if (page.size > 100 || rows.size + page.size > maxRows) throw IOException("MLS $array exceeds requested bounds")
            rows.addAll(page)
            cursor = cursor(raw)
            if (cursor == null) return rows
            if (!seen.add(cursor!!)) throw IOException("MLS repeated page cursor")
        }
        throw IOException("MLS $array was truncated; narrower dates are required")
    }
    /** Incident pages are one complete authoritative resource; validate identity on EVERY page. */
    suspend fun complete(path: String, array: String, query: Map<String, String>, validate: (JsonElement) -> Boolean): JsonElement {
        var first: JsonElement? = null; var cursor: String? = null
        val seen = HashSet<String>(); val rows = ArrayList<JsonElement>(); var characters = 0L
        repeat(20) {
            val raw = get(path, query + listOfNotNull(cursor?.let { "page_token" to it }).toMap())
            characters += raw.toString().length
            if (characters > 4L * 1024 * 1024) throw IOException("MLS paged $array exceeds aggregate memory bound")
            if (!validate(raw)) throw IOException("MLS paged resource identity mismatch")
            val page = raw[array] as? JsonArray ?: throw IOException("MLS paged $array format unavailable")
            if (page.size > 100 || rows.size + page.size > 2000) throw IOException("MLS paged $array exceeds bounds")
            if (first == null) first = raw
            rows.addAll(page); cursor = cursor(raw)
            if (cursor == null) return JsonObject(first!!.obj.orEmpty() - "next_page_token" + (array to JsonArray(rows)))
            if (!seen.add(cursor!!)) throw IOException("MLS paged $array cursor repeated")
        }
        throw IOException("MLS paged $array was truncated")
    }
    companion object {
        const val REGULAR = "MLS-COM-000001"
        const val PLAYOFFS = "MLS-COM-000002"
        val COMPETITIONS = setOf(REGULAR, PLAYOFFS)
        fun cursor(raw: JsonElement): String? {
            val value = raw["next_page_token"]
            if (value == null || value is JsonNull) return null
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IOException("MLS cursor format unavailable")
            if (text.length > 2048) throw IOException("MLS page cursor exceeds limit")
            return text.takeIf(String::isNotBlank)
        }
    }
}
