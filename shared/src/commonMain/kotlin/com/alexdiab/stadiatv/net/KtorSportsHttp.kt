package com.alexdiab.stadiatv.net

import com.alexdiab.stadiatv.foundation.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonElement

/** Bounded transport for native hosts. Cache keys include headers and body limits; no credentials are logged. */
class KtorSportsHttp internal constructor(private val client: HttpClient) : SportsHttp {
    private data class Key(val url: String, val headers: Map<String,String>, val limit: Long)
    private data class Cached(val body: String, val expiry: Long)
    private class Flight(val result: Deferred<String>, var consumers: Int = 1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SportsLock()
    private val cache = linkedMapOf<Key,Cached>()
    private val flights = mutableMapOf<Key,Flight>()

    override suspend fun getString(url: String, headers: Map<String,String>, ttlMillis: Long, retries: Int, maxResponseBytes: Long?): String {
        val key = Key(url, headers.toMap(), limit(maxResponseBytes))
        if (ttlMillis > 0) lock.locked { cache[key]?.takeIf { it.expiry > nowMillis() } }?.let { return it.body }
        val flight = lock.locked {
            flights[key]?.also { it.consumers++ } ?: Flight(scope.async(start = CoroutineStart.LAZY) {
                val body = request(HttpMethod.Get, url, headers, retries, key.limit).decodeToString()
                if (ttlMillis > 0 && body.length <= CACHE_CHARS) lock.locked {
                    cache.remove(key)
                    cache[key] = Cached(body, nowMillis() + ttlMillis)
                    while (cache.size > 64 || cache.values.sumOf { it.body.length.toLong() } > CACHE_CHARS) cache.remove(cache.keys.first())
                }
                body
            }).also { flights[key] = it; it.result.start() }
        }
        try { return flight.result.await() }
        finally {
            lock.locked {
                flight.consumers--
                if (flight.consumers == 0 && flights[key] === flight) {
                    flights.remove(key)
                    if (!flight.result.isCompleted) flight.result.cancel()
                }
            }
        }
    }

    override suspend fun getJson(url: String, headers: Map<String,String>, ttlMillis: Long, retries: Int, maxResponseBytes: Long?): JsonElement {
        val body = getString(url, headers, ttlMillis, retries, maxResponseBytes)
        return try { parseServiceJson(body) } catch (e: kotlinx.serialization.SerializationException) {
            lock.locked { cache.remove(Key(url, headers, limit(maxResponseBytes))) }
            throw SportsIOException("The sports service returned invalid JSON")
        }
    }
    override suspend fun getBytes(url: String, headers: Map<String,String>, retries: Int, maxResponseBytes: Long?) =
        request(HttpMethod.Get, url, headers, retries, limit(maxResponseBytes))
    override suspend fun postForm(url: String, form: Map<String,String>, headers: Map<String,String>) =
        request(HttpMethod.Post, url, headers + ("Content-Type" to "application/x-www-form-urlencoded"), 0, limit(null), form.entries.map { it.key to it.value }.formUrlEncode()).decodeToString()
    override suspend fun postJson(url: String, json: String, headers: Map<String,String>) =
        request(HttpMethod.Post, url, headers + ("Content-Type" to "application/json"), 0, limit(null), json).decodeToString()
    override fun invalidate(urlPrefix: String?) = lock.locked {
        cache.keys.filter { urlPrefix == null || it.url.startsWith(urlPrefix) }.forEach(cache::remove)
    }
    fun close() { scope.cancel(); client.close(); invalidate() }

    private suspend fun request(method: HttpMethod, url: String, headers: Map<String,String>, retries: Int, limit: Long, body: String? = null): ByteArray {
        require(safeWebURL(url)) { "Sports APIs require a valid HTTPS URL" }
        var attempt = 0
        while (true) {
            try {
                return client.prepareRequest(url) {
                    this.method = method
                    headers.forEach { (k,v) -> header(k,v) }
                    body?.let { setBody(it) }
                }.execute { response ->
                    if (!response.status.isSuccess()) throw HttpStatusException(response.status.value, retryAfterSeconds(response.headers[HttpHeaders.RetryAfter]))
                    if ((response.contentLength() ?: 0) > limit) throw ResponseTooLargeException(limit)
                    val bytes = response.bodyAsChannel().readRemaining(limit + 1).readByteArray()
                    if (bytes.size > limit) throw ResponseTooLargeException(limit)
                    bytes
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                val status = error as? HttpStatusException
                val retry = error !is ResponseTooLargeException && (status == null || status.code == 429 || status.code in 500..599)
                if (!retry || attempt >= retries.coerceIn(0,3)) throw when(error) {
                    is HttpStatusException, is ResponseTooLargeException -> error
                    else -> SportsIOException("The sports service could not be reached")
                }
                // A very long server cooldown is surfaced to the repository, never shortened by retrying early.
                val wait = status?.retryAfterSeconds?.times(1000) ?: (500L shl attempt)
                if (wait > 30_000) throw error
                delay(wait.coerceAtLeast(1)); attempt++
            }
        }
    }
    private fun limit(value: Long?) = (value ?: 8L * 1024 * 1024).also { require(it in 1..64L*1024*1024) }
    private companion object { const val CACHE_CHARS = 2L * 1024 * 1024 }
}

internal fun retryAfterSeconds(value: String?, now: Long = nowMillis()): Long? {
    value?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it.toLong().coerceIn(1, 86400) }
    return value?.let { SportsTime.parseLocal(it, "EEE, dd MMM yyyy HH:mm:ss 'GMT'", "UTC") }
        ?.let { ((it - now) / 1000).coerceIn(1,86400) }
}
