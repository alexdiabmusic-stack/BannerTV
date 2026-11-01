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

/** Public web enrichment only. A denial is a retained-resource failure, never a browser-impersonation bypass. */
class NativeFotMobClient(private val http: SportsHttp, private val base: String = "https://www.fotmob.com",
    private val tickerBase: String = "https://data.fotmob.com", private val now: () -> Long = ::nowMillis) {
    private val gate = Semaphore(2)
    @Volatile private var blockedUntil = 0L
    @Volatile private var tickerBlockedUntil = 0L
    // Freshness is recorded by the coordinator. An HTTP-disk-cache hit is not a new source check.
    // Revalidation permits304 reuse after a real server check, without cache-busting or bypassing denial.
    private val headers = mapOf("Accept" to "application/json", "User-Agent" to "BannerTV/1.0", "Cache-Control" to "max-age=0")
    suspend fun championsLeague() = get("leagues", mapOf("id" to "42"), 30_000).also {
        if (it["details"]["id"].int() != 42) throw IOException("Wrong FotMob competition")
    }
    suspend fun day(date: String): JsonElement {
        require(date.matches(Regex("[0-9]{8}")))
        return get("matches", mapOf("date" to date, "timezone" to "UTC"), 60_000)
    }
    suspend fun details(id: String): JsonElement {
        require(validId(id))
        return get("matchDetails", mapOf("matchId" to id), 0)
    }
    private suspend fun get(route: String, query: Map<String, String>, ttl: Long): JsonElement = gate.withPermit {
        if (blockedUntil > now()) throw IOException("FotMob enrichment is cooling down")
        val url = requestURL(base.trimEnd('/') + "/api/data/$route", query)
        try { withTimeout(8_000) { http.getJson(url.toString(), headers, ttl, retries = 0, maxResponseBytes = LIMIT) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) { if (error.code in setOf(401, 403, 429)) blockedUntil = maxOf(blockedUntil, deadline(error)); throw error }
    }
    suspend fun ticker(id: String, details: JsonElement): JsonElement? {
        val source = tickerUrl(id, details) ?: return null
        return gate.withPermit {
            if (tickerBlockedUntil > now()) throw IOException("FotMob commentary is cooling down")
            // The remote payload may name only the exact verified static host/path. A configurable
            // origin is solely for deterministic MockWebServer tests; source paths remain validated.
            val url = tickerBase.trimEnd('/') + source.substringAfter("data.fotmob.com")
            try { withTimeout(8_000) { decodeTicker(http.getBytes(url, headers, retries = 0, maxResponseBytes = LIMIT)) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: HttpStatusException) { if (error.code in setOf(401, 403, 429)) tickerBlockedUntil = maxOf(tickerBlockedUntil, deadline(error)); throw error }
        }
    }
    private fun deadline(error: HttpStatusException) = now() + maxOf(if (error.code == 429) 60_000 else 300_000,
        (error.retryAfterSeconds ?: 0).coerceIn(0, 86_400) * 1000)
    companion object {
        private const val LIMIT = 4L * 1024 * 1024
        fun validId(id: String) = id.matches(Regex("[1-9][0-9]{0,11}"))
        fun tickerUrl(id: String, raw: JsonElement): String? {
            require(validId(id))
            val supplied = raw["content"]["liveticker"]["ltcUrl"].stringOrNull()?.takeIf(String::isNotBlank)
            val url = if (supplied != null) supplied else {
                val langs = raw["content"]["liveticker"]["langs"].stringOrNull()?.takeIf { it.length <= 512 }?.split(',')?.map(String::trim).orEmpty()
                val allowed = langs.filter { it.matches(Regex("[a-z]{2}(?:_[a-z0-9]{1,12})?")) }
                val language = allowed.firstOrNull { it == "en" || it.startsWith("en_") } ?: allowed.firstOrNull() ?: return null
                "https://data.fotmob.com/webcl/ltc/gsm/${id}_$language.json.gz"
            }
            if (!safeWebURL(url) || !url.startsWith("https://data.fotmob.com/") || !url.removePrefix("https://data.fotmob.com").matches(Regex("/webcl/ltc/gsm/${id}_[a-z]{2}(?:_[a-z0-9]{1,12})?\\.json\\.gz"))) throw IOException("FotMob ticker host/path/game identity rejected")
            return url.toString()
        }
        /** Static ticker files may be gzip with NO Content-Encoding; HTTP may also already decode it. */
        fun decodeTicker(bytes: ByteArray): JsonElement {
            if (bytes.size > LIMIT) throw ResponseTooLargeException(LIMIT)
            val text = if (bytes.size >= 2 && bytes[0] == 31.toByte() && bytes[1] == 139.toByte())
                gunzip(bytes,LIMIT) else bytes.decodeToString()
            return try {
                val value = parseJson(text.removePrefix("\uFEFF"))
                val events = value["Events"] as? JsonArray ?: throw IOException("FotMob ticker events missing")
                if (value !is JsonObject || events.size > 2000) throw IOException("FotMob ticker format/size unavailable")
                value
            }
            catch (error: kotlinx.serialization.SerializationException) { throw IOException("FotMob ticker format unavailable", error) }
        }
    }
}
