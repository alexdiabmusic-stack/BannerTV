package com.alexdiab.stadiatv.sports.f1

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.net.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray

/** The Apple engine uses URLSession, with an isolated in-memory F1 affinity-cookie jar. */
class KtorF1Transport internal constructor(private val client: HttpClient) : F1SessionTransport() {
    private fun HttpRequestBuilder.identify() {
        header(HttpHeaders.UserAgent, "BestHTTP")
        header(HttpHeaders.Origin, "https://www.formula1.com")
    }
    override suspend fun connect(deliver: suspend (F1TransportEvent) -> Unit): Unit = coroutineScope {
        val origin = "https://livetiming.formula1.com/signalrcore"
        client.prepareRequest("$origin/negotiate") { method = HttpMethod.Options; identify() }.execute { response ->
            if (!response.status.isSuccess() && response.status.value != 405) throw status(response)
        }
        val token = client.preparePost("$origin/negotiate?negotiateVersion=1") {
            identify(); contentType(ContentType.Text.Plain); setBody("")
        }.execute { response ->
            if (!response.status.isSuccess()) throw status(response)
            val bytes = response.bodyAsChannel().readRemaining(64 * 1024L + 1).readByteArray()
            if (bytes.size > 64*1024) throw ResponseTooLargeException(64*1024)
            parseJson(bytes.decodeToString())["connectionToken"].stringOrNull()
                ?: throw SportsIOException("Invalid F1 negotiation response")
        }
        client.webSocket(urlString = "wss://livetiming.formula1.com/signalrcore?id=" + encodeComponent(token), request = { identify() }) {
            val parser = F1Protocol()
            send(F1Wire.handshake)
            val ping = launch { while (isActive) { delay(10_000); send("{\"type\":6}\u001e") } }
            try {
                while (isActive) {
                    val frame = try { withTimeout(40_000) { incoming.receive() } }
                        catch (_: TimeoutCancellationException) { throw SportsIOException("Live timing stopped responding") }
                    if (frame !is Frame.Text && frame !is Frame.Binary) continue
                    if (frame.data.size > F1Wire.LIMIT) throw ResponseTooLargeException(F1Wire.LIMIT.toLong())
                    deliver(F1Activity(nowMillis()))
                    for (event in parser.receive(frame.data)) when (event) {
                        F1HubEvent.Handshake -> send(F1Wire.subscription)
                        is F1HubEvent.Closed -> throw SportsIOException("Timing socket closed")
                        is F1HubEvent.Snapshot -> { deliver(event); deliver(F1Connection("connected",0)) }
                        else -> deliver(event)
                    }
                }
            } finally { ping.cancel() }
        }
    }
    private fun status(response: HttpResponse) = HttpStatusException(response.status.value, retryAfterSeconds(response.headers[HttpHeaders.RetryAfter]))
}
