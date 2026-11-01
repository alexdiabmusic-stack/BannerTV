package com.alexdiab.stadiatv.net

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class HttpGatewayBodyLimitTest {
    @Test fun binaryGetsEnforceDeclaredChunkedAndDecompressedLimitsWithoutRetries(): Unit = runBlocking {
        val encoded = Buffer(); GzipSink(encoded).buffer().use { it.writeUtf8("x".repeat(4096)) }
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(65)))
        server.enqueue(MockResponse().setChunkedBody("x".repeat(65), 7))
        server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(encoded)); server.start()
        try {
            val http = HttpGateway(OkHttpClient())
            for (path in listOf("declared", "chunked", "gzip")) assertTrue(runCatching { http.getBytes(server.url("/$path").toString(), retries = 2, maxResponseBytes = 32) }.exceptionOrNull() is ResponseTooLargeException)
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun binaryGetsAcceptExactUtf8ByteBoundaryAndPreserveUnboundedCallerCompatibility(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("€€")); server.enqueue(MockResponse().setBody("€€€")); server.start()
        try {
            val http = HttpGateway(OkHttpClient())
            assertArrayEquals("€€".toByteArray(), http.getBytes(server.url("/exact").toString(), maxResponseBytes = 6))
            assertArrayEquals("€€€".toByteArray(), http.getBytes(server.url("/legacy").toString()))
        } finally { server.shutdown() }
    }
    @Test fun declaredOversizedBodyIsRejectedWithoutRetrying(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("x".repeat(64))); server.start()
        try {
            val failure = runCatching { HttpGateway(OkHttpClient()).getJson(server.url("/large").toString(), retries = 2, maxResponseBytes = 32) }.exceptionOrNull()
            assertTrue(failure is ResponseTooLargeException); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun unknownLengthChunkedBodyIsBoundedWhileReading(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setChunkedBody("x".repeat(65), 7)); server.start()
        try {
            assertTrue(runCatching { HttpGateway(OkHttpClient()).getString(server.url("/chunked").toString(), maxResponseBytes = 32) }.exceptionOrNull() is ResponseTooLargeException)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun gzipLimitAppliesToDecompressedBytesNotTheSmallerWireBody(): Unit = runBlocking {
        val encoded = Buffer(); GzipSink(encoded).buffer().use { it.writeUtf8("x".repeat(4096)) }
        assertTrue(encoded.size < 128)
        val server = MockWebServer(); server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(encoded)); server.start()
        try {
            assertTrue(runCatching { HttpGateway(OkHttpClient()).getString(server.url("/gzip").toString(), maxResponseBytes = 128) }.exceptionOrNull() is ResponseTooLargeException)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun exactByteBoundaryAndUtf8CharactersAreDecodedCorrectly(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("€€")); server.enqueue(MockResponse().setBody("€€€")); server.start()
        try {
            val http = HttpGateway(OkHttpClient())
            assertEquals("€€", http.getString(server.url("/exact").toString(), maxResponseBytes = 6))
            assertTrue(runCatching { http.getString(server.url("/too-many-bytes").toString(), maxResponseBytes = 6) }.exceptionOrNull() is ResponseTooLargeException)
        } finally { server.shutdown() }
    }
    @Test fun differentLimitsHaveSeparateFlightsAndCacheEntries(): Unit = runBlocking {
        val server = MockWebServer(); server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("x".repeat(48))
        }; server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val url = server.url("/policies").toString()
            assertEquals(48, http.getString(url, ttlMillis = 60_000, maxResponseBytes = 64).length)
            assertTrue(runCatching { http.getString(url, ttlMillis = 60_000, maxResponseBytes = 32) }.exceptionOrNull() is ResponseTooLargeException)
            assertEquals(2, server.requestCount)
            assertEquals(48, http.getString(url, ttlMillis = 60_000, maxResponseBytes = 64).length); assertEquals(2, server.requestCount)
            http.invalidate(url)
            coroutineScope { listOf(async { http.getString(url, maxResponseBytes = 64) }, async { http.getString(url, maxResponseBytes = 96) }).awaitAll() }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }
}
