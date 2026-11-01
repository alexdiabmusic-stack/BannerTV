package com.alexdiab.stadiatv.sports.f1

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.SportsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

fun F1Repository(http: HttpGateway, directory: File? = null) =
    F1Repository(http, F1Stream(http.client), directory?.let { com.alexdiab.stadiatv.foundation.SportsFile(it.path) })

fun androidF1Transport(http: HttpGateway): F1Transport = F1Stream(http.client)

private sealed interface F1SocketEvent {
    data class Bytes(val bytes: ByteArray) : F1SocketEvent
    data class Failure(val error: Exception) : F1SocketEvent
}

/** Affinity cookies are scoped to F1. Access-denied terminates; it never rotates IPs or bypasses gates. */
private class F1Stream(base: OkHttpClient) : F1SessionTransport() {
    private val cookies = mutableListOf<Cookie>()
    private val client = base.newBuilder().callTimeout(20, TimeUnit.SECONDS).cookieJar(object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) = synchronized(cookies) { incoming.forEach { c -> cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; cookies.add(c) }; Unit }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(cookies) { cookies.removeAll { it.expiresAt < System.currentTimeMillis() }; cookies.filter { it.matches(url) } }
    }).build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "BestHTTP").header("Origin", "https://www.formula1.com")
    override suspend fun connect(deliver: suspend (F1TransportEvent) -> Unit) = coroutineScope {
        val origin = "https://livetiming.formula1.com/signalrcore"
        client.newCall(request("$origin/negotiate").method("OPTIONS", null).build()).f1Await().use { if (!it.isSuccessful && it.code != 405) throw status(it) }
        val token = client.newCall(request("$origin/negotiate?negotiateVersion=1").post(ByteArray(0).toRequestBody()).header("Content-Type", "text/plain").build()).f1Await().use { response -> if (!response.isSuccessful) throw status(response); parseJson(response.body.string())["connectionToken"].stringOrNull() ?: throw IOException("Invalid F1 negotiation response") }
        val events = Channel<F1SocketEvent>(64)
        val queuedBytes = AtomicInteger(0)
        val parser = F1Protocol()
        val url = origin.toHttpUrl().newBuilder().addQueryParameter("id", token).build()
        val socket = client.newWebSocket(request(url.toString()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { if (!webSocket.send(F1Wire.handshake)) webSocket.cancel() }
            private fun offer(webSocket: WebSocket, bytes: ByteArray) { if (bytes.size > F1Wire.LIMIT || queuedBytes.addAndGet(bytes.size) > F1Wire.LIMIT || events.trySend(F1SocketEvent.Bytes(bytes)).isFailure) { events.close(IOException("Timing consumer fell behind; a fresh snapshot is required.")); webSocket.cancel() } }
            override fun onMessage(webSocket: WebSocket, text: String) = offer(webSocket, text.toByteArray())
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = offer(webSocket, bytes.toByteArray())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(F1SocketEvent.Failure(response?.let(::status) ?: IOException(t))); events.close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.close(IOException(reason.ifBlank { "Timing socket closed" })) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        val ping = launch { while (isActive) { delay(10000); if (!socket.send("{\"type\":6}\u001e")) throw IOException("Timing ping failed") } }
        try {
            while (isActive) {
                val event = try { withTimeout(40000) { events.receive() } } catch (_: TimeoutCancellationException) { throw IOException("Live timing stopped responding.") }
                when (event) {
                    is F1SocketEvent.Failure -> throw event.error
                    is F1SocketEvent.Bytes -> { queuedBytes.addAndGet(-event.bytes.size); deliver(F1Activity(System.currentTimeMillis())); for (parsed in parser.receive(event.bytes)) {
                        when (parsed) { F1HubEvent.Handshake -> if (!socket.send(F1Wire.subscription)) throw IOException("F1 subscription failed")
                            is F1HubEvent.Closed -> throw IOException(parsed.reason)
                            is F1HubEvent.Snapshot -> { deliver(parsed); deliver(F1Connection("connected", 0)) }
                            else -> deliver(parsed)
                        }
                    } }
                }
            }
        } finally { ping.cancel(); socket.cancel(); events.cancel() }
    }
    private fun status(response: Response) = HttpStatusException(response.code, retryAfter(response.header("Retry-After")))
    companion object {
        fun retryAfter(value: String?, now: Long = System.currentTimeMillis()): Long? {
            value?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it.toLong().coerceAtLeast(1) }
            return value?.let { runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(it)?.time?.let { date -> ((date - now) / 1000).coerceAtLeast(1) } }.getOrNull() }
        }
    }
}
private suspend fun Call.f1Await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback { override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }; override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() } })
}
