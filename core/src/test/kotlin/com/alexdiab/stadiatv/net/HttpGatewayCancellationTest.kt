package com.alexdiab.stadiatv.net

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.EventListener
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real local sockets, no external requests. Mirrors NFLShieldClient's shared-flight ownership tests. */
class HttpGatewayCancellationTest {
    @Test fun oneCancelledWaiterDoesNotCancelTheSurvivingConsumer(): Unit = runBlocking {
        val release = CountDownLatch(1); val call = AtomicReference<Call>()
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { release.await(3, TimeUnit.SECONDS); return MockResponse().setBody("shared body") }
        }; server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain -> call.set(chain.call()); chain.proceed(chain.request()) }.build()
            val http = HttpGateway(client); val url = server.url("/same").toString()
            val first = async(start = CoroutineStart.UNDISPATCHED) { http.getString(url, retries = 0) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val survivor = async(start = CoroutineStart.UNDISPATCHED) { http.getString(url, retries = 0) }
            first.cancelAndJoin(); assertFalse(call.get().isCanceled())
            release.countDown(); assertEquals("shared body", withTimeout(3000) { survivor.await() }); assertEquals(1, server.requestCount)
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun lastCancelledWaiterClosesItsCallAndANewRequestDoesNotJoinTheAbandonedFlight(): Unit = runBlocking {
        val call = AtomicReference<Call>(); val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.enqueue(MockResponse().setBody("fresh")); server.start()
        try {
            val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain -> call.set(chain.call()); chain.proceed(chain.request()) }.build())
            val url = server.url("/same").toString()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { http.getString(url, ttlMillis = 60_000, retries = 0) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); val abandoned = call.get()
            pending.cancelAndJoin()
            withTimeout(1000) { while (!abandoned.isCanceled()) delay(5) }
            assertEquals("fresh", withTimeout(3000) { http.getString(url, ttlMillis = 60_000, retries = 0) })
            assertEquals(2, server.requestCount)
            assertEquals("fresh", http.getString(url, ttlMillis = 60_000, retries = 0)); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancellationStaysAttachedWhileTheResponseBodyIsBeingRead(): Unit = runBlocking {
        val bodyStarted = CountDownLatch(1); val activeCall = AtomicReference<Call>(); val server = MockWebServer()
        // Deliver the first byte immediately, then stall the rest: responseBodyStart is emitted on first read,
        // not merely when headers arrive (a body-delay would race the latch timeout instead of testing reads).
        server.enqueue(MockResponse().setBody("slow body").throttleBody(1, 2, TimeUnit.SECONDS)); server.start()
        try {
            val http = HttpGateway(OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { activeCall.set(call); bodyStarted.countDown() }
            }).build())
            val pending = async(start = CoroutineStart.UNDISPATCHED) { http.getString(server.url("/body").toString(), retries = 0) }
            assertTrue(bodyStarted.await(3, TimeUnit.SECONDS))
            // The body is deliberately still blocked after response headers; this is not only a header-wait test.
            assertFalse(pending.isCompleted); pending.cancelAndJoin()
            withTimeout(1000) { while (!activeCall.get().isCanceled()) delay(5) }
            assertTrue(activeCall.get().isCanceled())
        } finally { server.shutdown() }
    }

    @Test fun timeoutReleasesTheOnlyWaiterInsteadOfLeavingASocketForTheWholeClientTimeout(): Unit = runBlocking {
        val call = AtomicReference<Call>(); val server = MockWebServer(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
        try {
            val http = HttpGateway(OkHttpClient.Builder().addInterceptor { chain -> call.set(chain.call()); chain.proceed(chain.request()) }.build())
            val pending = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(500) { http.getString(server.url("/timeout").toString(), retries = 0) } }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNull(pending.await())
            withTimeout(1000) { while (!call.get().isCanceled()) delay(5) }
        } finally { server.shutdown() }
    }

    @Test fun failedFlightsAreReleasedAndDifferentHeaderIdentitiesNeverCoalesce(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody("one")); server.enqueue(MockResponse().setBody("two")); server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val url = server.url("/headers").toString()
            assertTrue(runCatching { http.getString(url, mapOf("X-Test-Identity" to "one"), 60_000, retries = 0) }.exceptionOrNull() is HttpStatusException)
            assertEquals("one", http.getString(url, mapOf("X-Test-Identity" to "one"), 60_000, retries = 0))
            assertEquals("two", http.getString(url, mapOf("X-Test-Identity" to "two"), 60_000, retries = 0))
            assertEquals("one", http.getString(url, mapOf("X-Test-Identity" to "one"), 60_000, retries = 0)); assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
