package com.alexdiab.stadiatv.net

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random


/**
 * The one network entry point for data providers.
 *
 * - Coroutine native (cancelling the caller cancels the call).
 * - Bounded retries with jittered backoff for transient failures; HTTP 429 honours `Retry-After`.
 * - Optional short TTL cache and coalescing of identical in-flight GETs, so a screen change or a
 *   channel zap never refetches a scoreboard that was loaded a few seconds ago.
 */
class HttpGateway(val client: OkHttpClient) : SportsHttp {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Flight(val result: Deferred<String>, var waiters: Int = 1)
    private val flightLock = Any()
    private val inFlight = HashMap<String, Flight>()
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    private class Cached(val body: String, val expiresAt: Long)

    override suspend fun getString(
        url: String,
        headers: Map<String, String>,
        ttlMillis: Long,
        retries: Int,
        maxResponseBytes: Long?,
    ): String {
        require(maxResponseBytes == null || maxResponseBytes in 1..64L * 1024 * 1024)
        // Different memory policies must not coalesce or reuse a body accepted under a larger limit.
        val key = responseKey(url, headers, maxResponseBytes)
        if (ttlMillis > 0) {
            synchronized(cache) { cache[key] }?.let { if (it.expiresAt > now()) return it.body }
        }
        // Coalesce identical in-flight requests onto one network call.
        val shared = synchronized(flightLock) {
            inFlight[key]?.also { it.waiters++ } ?: Flight(scope.async(start = CoroutineStart.LAZY) {
                val body = execute(Request.Builder().url(url).applyHeaders(headers).get().build(), retries, maxResponseBytes)
                if (ttlMillis > 0 && body.length <= MAX_CACHE_CHARACTERS) synchronized(cache) {
                    cache[key] = Cached(body, now() + ttlMillis)
                    // Full native play/roster payloads must not turn an entry-count cap into an unbounded heap.
                    while (cache.size > 1 && cache.values.sumOf { it.body.length.toLong() } > MAX_CACHE_CHARACTERS) cache.remove(cache.keys.first())
                }
                body
            }).also { flight -> inFlight[key] = flight; flight.result.start() }
        }
        // NFLShieldClient's waiter ownership: one cancelled screen cannot kill another consumer,
        // but a flight with no remaining consumer must not keep its socket/payload alive.
        try { return shared.result.await() }
        finally {
            val released = synchronized(flightLock) {
                shared.waiters--
                if (shared.waiters == 0 && inFlight[key] === shared) { inFlight.remove(key); true } else false
            }
            if (released && !shared.result.isCompleted) shared.result.cancel()
        }
    }

    override suspend fun getJson(
        url: String,
        headers: Map<String, String>,
        ttlMillis: Long,
        retries: Int,
        maxResponseBytes: Long?,
    ): JsonElement {
        val body = getString(url, headers, ttlMillis, retries, maxResponseBytes)
        return try { parseServiceJson(body) }
        catch (error: kotlinx.serialization.SerializationException) {
            // An HTTP-200 HTML/block/error page is not a successful JSON cache entry. Do not pin it for
            // the roster/profile's hour-long TTL, and do not automatically retry a decoding failure.
            val key = responseKey(url, headers, maxResponseBytes)
            synchronized(cache) { if (cache[key]?.body == body) cache.remove(key) }
            throw error
        }
    }

    override suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): String {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).applyHeaders(headers).post(body).build(), retries = 1)
    }

    override suspend fun postJson(url: String, json: String, headers: Map<String, String>): String {
        val body = json.toRequestBody("application/json".toMediaType())
        return execute(Request.Builder().url(url).applyHeaders(headers).post(body).build(), retries = 1)
    }

    /** A raw GET that returns bytes (gzip XMLTV, binary guides). */
    override suspend fun getBytes(url: String, headers: Map<String, String>, retries: Int, maxResponseBytes: Long?): ByteArray {
        require(maxResponseBytes == null || maxResponseBytes in 1..64L * 1024 * 1024)
        val request = Request.Builder().url(url).applyHeaders(headers).get().build()
        return attempt(request, retries) { response ->
            val body = response.body
            if (maxResponseBytes == null) body.bytes() else {
                if (body.contentLength() > maxResponseBytes) throw ResponseTooLargeException(maxResponseBytes)
                val output = ByteArrayOutputStream(minOf(maxResponseBytes, 8192).toInt())
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(chunk, 0, minOf(chunk.size.toLong(), maxResponseBytes - total + 1).toInt())
                        if (count < 0) break
                        total += count
                        if (total > maxResponseBytes) throw ResponseTooLargeException(maxResponseBytes)
                        output.write(chunk, 0, count)
                    }
                }
                output.toByteArray()
            }
        }
    }

    override fun invalidate(urlPrefix: String?) {
        synchronized(cache) {
            if (urlPrefix == null) cache.clear() else cache.keys.removeAll { it.startsWith(urlPrefix) }
        }
    }

    private suspend fun execute(request: Request, retries: Int, maxResponseBytes: Long? = null): String =
        attempt(request, retries) { response ->
            if (maxResponseBytes == null) response.body.string() else {
                val body = response.body
                if (body.contentLength() > maxResponseBytes) throw ResponseTooLargeException(maxResponseBytes)
                // Read decompressed bytes, including chunked/unknown-length bodies, before constructing a JSON tree.
                val output = ByteArrayOutputStream(minOf(maxResponseBytes, 8192).toInt())
                body.byteStream().use { input ->
                    val chunk = ByteArray(8192); var total = 0L
                    while (true) {
                        val count = input.read(chunk, 0, minOf(chunk.size.toLong(), maxResponseBytes - total + 1).toInt())
                        if (count < 0) break
                        total += count
                        if (total > maxResponseBytes) throw ResponseTooLargeException(maxResponseBytes)
                        output.write(chunk, 0, count)
                    }
                }
                output.toString((body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8).name()).removePrefix("\uFEFF")
            }
        }

    private suspend fun <T> attempt(request: Request, retries: Int, read: (Response) -> T): T {
        var lastError: IOException? = null
        for (attemptNumber in 0..retries) {
            try {
                return client.newCall(request).readResponse { response ->
                    if (response.isSuccessful) read(response)
                    else {
                        val retryAfter = HttpRetryAfter.seconds(response.header("Retry-After"), System.currentTimeMillis())
                        throw HttpStatusException(response.code, retryAfter)
                    }
                }
            } catch (error: HttpStatusException) {
                lastError = error
                val transient = error.code == 408 || error.code == 429 || error.code >= 500
                if (!transient || attemptNumber == retries) throw error
                delay(backoff(attemptNumber, error.retryAfterSeconds))
            } catch (error: ResponseTooLargeException) { throw error } // Retrying the same oversized payload only repeats the memory spike.
            catch (error: IOException) {
                lastError = error
                if (attemptNumber == retries) throw error
                delay(backoff(attemptNumber, null))
            }
        }
        throw lastError ?: IOException("Request failed")
    }

    private fun backoff(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null) return (retryAfterSeconds.coerceIn(1, 8)) * 1000
        return (350L shl attempt) + Random.nextLong(0, 200)
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (name, value) -> header(name, value) }
        return this
    }

    private fun cacheKey(url: String, headers: Map<String, String>): String =
        if (headers.isEmpty()) url else url + "\u0000" + headers.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }

    private fun responseKey(url: String, headers: Map<String, String>, maxResponseBytes: Long?) =
        cacheKey(url, headers) + maxResponseBytes?.let { "\u0000response-limit=$it" }.orEmpty()

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val MAX_CACHE_ENTRIES = 96
        private const val MAX_CACHE_CHARACTERS = 8L * 1024 * 1024
        const val USER_AGENT = "StadiaTV-FireTV/2.0 (Android TV)"

        /** Shared client: HTTP/2, brotli, small disk cache, sensible timeouts for a TV on Wi-Fi. */
        fun createClient(context: Context): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 48
                maxRequestsPerHost = 10
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(40, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .cache(Cache(File(context.cacheDir, "http"), 24L * 1024 * 1024))
                .addInterceptor(BrotliInterceptor)
                .addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(
                        if (request.header("User-Agent") == null) {
                            request.newBuilder().header("User-Agent", USER_AGENT).build()
                        } else request
                    )
                }
                .build()
        }
    }
}

/** Keep cancellation attached until the body is consumed, not only until response headers arrive. */
private suspend fun <T> Call.readResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            val outcome = runCatching { response.use(read) }
            if (continuation.isActive) outcome.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}
