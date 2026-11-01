package com.alexdiab.stadiatv.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class HttpGatewayJsonCacheTest {
    @Test fun successfulHttpHtmlIsNotPinnedForTheJsonConsumersHourLongTtl(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("<!DOCTYPE html><html>Temporarily unavailable</html>"))
        server.enqueue(MockResponse().setBody("""{"recovered":true}""")); server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val url = server.url("/profile").toString()
            assertTrue(runCatching { http.getJson(url, ttlMillis = 3_600_000) }.isFailure); assertEquals(1, server.requestCount)
            assertTrue(http.getJson(url, ttlMillis = 3_600_000)["recovered"].bool()); assertEquals(2, server.requestCount)
            assertTrue(http.getJson(url, ttlMillis = 3_600_000)["recovered"].bool()); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun failedJsonDecodeDoesNotClearAnotherHeaderOrBodyLimitCachePolicy(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("""{"known":1}""")); server.enqueue(MockResponse().setBody("<!DOCTYPE html><html>Blocked</html>"));
        server.enqueue(MockResponse().setBody("""{"known":2}""")); server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val url = server.url("/headers").toString()
            val valid = mapOf("Accept" to "application/json", "X-Public-Variant" to "one")
            val bad = mapOf("Accept" to "application/json", "X-Public-Variant" to "two")
            assertEquals(1, http.getJson(url, valid, 60_000, maxResponseBytes = 256)["known"].int())
            assertTrue(runCatching { http.getJson(url, bad, 60_000, maxResponseBytes = 128) }.isFailure)
            assertEquals(1, http.getJson(url, valid, 60_000, maxResponseBytes = 256)["known"].int()); assertEquals(2, server.requestCount)
            assertEquals(2, http.getJson(url, bad, 60_000, maxResponseBytes = 128)["known"].int()); assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun plainTextConsumersCanStillCacheHtmlWithoutBeingForcedThroughJsonValidation(): Unit = runBlocking {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("<html>A public article</html>")); server.start()
        try {
            val http = HttpGateway(OkHttpClient()); val url = server.url("/article").toString()
            assertEquals(http.getString(url, ttlMillis = 60_000), http.getString(url, ttlMillis = 60_000)); assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
