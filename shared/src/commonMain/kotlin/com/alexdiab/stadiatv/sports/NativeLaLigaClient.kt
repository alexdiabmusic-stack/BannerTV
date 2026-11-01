package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** The official site's PUBLIC frontend configuration, verified on laliga.com on 2026-10-23.
 * Not an account/service credential. Never use the separate internal backendServerUrl. */
class NativeLaLigaClient(private val http: SportsHttp,
    private val base: String = "https://apim.laliga.com/public-service",
    private val publicWebsiteSetting: String = PUBLIC_WEBSITE_SETTING,
    private val now: () -> Long = ::nowMillis) {
    private val gate = Semaphore(2)
    @Volatile private var blockedUntil = 0L
    private data class Subscription(val at: Long, val slug: String?)
    private val subscriptions = SportsMemo<Int, Subscription>()
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), ttl: Long = 0): JsonElement = gate.withPermit {
        require(path.matches(Regex("/api/v1/[a-z0-9/-]+")) && ".." !in path && query.size <= 12 && query.values.all { it.length <= 160 })
        require(publicWebsiteSetting.isNotBlank() && publicWebsiteSetting.length <= 256 && '\n' !in publicWebsiteSetting && '\r' !in publicWebsiteSetting)
        if (blockedUntil > now()) throw IOException("La Liga official gateway is cooling down")
        val url = requestURL(base.trimEnd('/') + path, query)
        try {
            withTimeout(8_000) { http.getJson(url.toString(), headers = mapOf("Accept" to "application/json", "User-Agent" to "BannerTV/1.0",
                "Ocp-Apim-Subscription-Key" to publicWebsiteSetting, "Cache-Control" to "max-age=0"), ttlMillis = ttl, retries = 0, maxResponseBytes = 4L * 1024 * 1024) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) {
            if (error.code in setOf(401, 403, 429)) blockedUntil = maxOf(blockedUntil, now() + maxOf(when (error.code) { 401 -> 600_000; 403 -> 300_000; else -> 60_000 },
                (error.retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000))
            throw error
        }
    }
    /** API defaults can return only20/34 squad entries. Complete BEFORE applying role/current filters. */
    suspend fun pages(path: String, array: String, query: Map<String, String> = emptyMap(), ttl: Long = 0,
        maxPages: Int = 12, maxRows: Int = 1200): List<JsonElement> {
        val rows = ArrayList<JsonElement>(); val signatures = HashSet<String>(); var offset = 0; var expectedTotal: Int? = null; var characters = 0L
        repeat(maxPages) {
            val raw = get(path, query + mapOf("limit" to "100", "offset" to "$offset"), ttl)
            characters += raw.toString().length
            if (characters > 4L * 1024 * 1024) throw IOException("La Liga paged $array exceeds aggregate memory bound")
            val page = raw[array] as? JsonArray ?: throw IOException("La Liga $array format unavailable")
            val total = raw["total"]?.takeUnless { it is JsonNull }?.let { it.string().toIntOrNull()?.takeIf { count -> count in 0..maxRows }
                ?: throw IOException("La Liga $array total exceeds bounds or is malformed") }
            if (expectedTotal != null && total != null && total != expectedTotal) throw IOException("La Liga $array changed during pagination")
            if (total != null) expectedTotal = total
            if (page.size > 100 || rows.size + page.size > maxRows || total != null && offset + page.size > total) throw IOException("La Liga $array page exceeds bounds")
            val signature = page.toString().encodeUtf8().sha256().hex()
            if (page.isNotEmpty() && !signatures.add(signature)) throw IOException("La Liga repeated page")
            rows.addAll(page); offset += page.size
            if (expectedTotal != null && offset == expectedTotal || expectedTotal == null && page.size < 100) return rows
            if (page.isEmpty()) throw IOException("La Liga $array was truncated")
        }
        throw IOException("La Liga $array exceeds page limit")
    }
    suspend fun subscription(year: Int): String? {
        require(year in 1900..2200)
        subscriptions[year]?.takeIf { now() - it.at in 0 until 86_400_000 }?.let { return it.slug }
        val rows = pages("/api/v1/subscriptions", "subscriptions", ttl = 86_400_000, maxPages = 8, maxRows = 800)
        val matches = rows.filter { row -> row["year"].string().toIntOrNull() == year && row["competition"]["slug"].string() == "primera-division" && row["competition"]["id"].string() == "1" }
        if (matches.size > 1) throw IOException("La Liga season subscription is ambiguous")
        val slug = matches.singleOrNull()?.get("slug")?.stringOrNull()?.takeIf { it.matches(Regex("[a-z0-9-]{1,120}")) }
        if (matches.isNotEmpty() && slug == null) throw IOException("La Liga subscription slug missing")
        subscriptions.transaction {
            if (subscriptions.size >= 8 && !subscriptions.containsKey(year)) subscriptions.snapshot().entries.minByOrNull { it.value.at }?.key?.let { subscriptions.remove(it) }
            subscriptions[year] = Subscription(now(), slug)
        }
        return slug
    }
    companion object {
        // Same value is published in the official __NEXT_DATA__.runtimeConfig.backendSubscription.
        const val PUBLIC_WEBSITE_SETTING = "c13c3a8e2f6b46da9c5c425cf61fab3e"
    }
}
