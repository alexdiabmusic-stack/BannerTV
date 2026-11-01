package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/** A zero app TTL must not silently re-date a still-fresh OkHttp disk-cache body. */
class NativeLaLigaHttpFreshnessTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun officialAndDetailRequestsRevalidateRatherThanHideAFailureBehindAFreshDiskBody(): Unit = runBlocking {
        for (official in listOf(true, false)) {
            val network = MockWebServer(); network.start()
            val cache = Cache(temporary.newFolder(), 1024 * 1024)
            val http = HttpGateway(OkHttpClient.Builder().cache(cache).build())
            val primary = NativeLaLigaClient(http, network.url("/").toString(), "constructed-public-setting")
            val detail = NativeFotMobClient(http, network.url("/").toString())
            suspend fun fetch() = if (official) primary.get("/api/v1/test") else detail.details("5868011")
            try {
                network.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=3600").setBody("{\"checked\":1}"))
                network.enqueue(MockResponse().setResponseCode(503))
                assertEquals("1", fetch()["checked"].string())
                assertEquals("max-age=0", network.takeRequest().getHeader("Cache-Control"))
                assertEquals(503, (runCatching { fetch() }.exceptionOrNull() as HttpStatusException).code)
                assertEquals(2, network.requestCount)
            } finally { cache.close(); network.shutdown() }
        }
    }
    @Test fun conditional304ReuseIsARealCheckBut403RemainsADenialWithNoRetryBypass(): Unit = runBlocking {
        for (official in listOf(true, false)) {
            val network = MockWebServer(); network.start()
            val cache = Cache(temporary.newFolder(), 1024 * 1024)
            val http = HttpGateway(OkHttpClient.Builder().cache(cache).build())
            val primary = NativeLaLigaClient(http, network.url("/").toString(), "constructed-public-setting")
            val detail = NativeFotMobClient(http, network.url("/").toString())
            suspend fun fetch() = if (official) primary.get("/api/v1/test") else detail.details("5868011")
            try {
                network.enqueue(MockResponse().setHeader("Cache-Control", "public, max-age=3600").setHeader("ETag", "source-v1").setBody("{\"checked\":1}"))
                network.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "source-v1"))
                network.enqueue(MockResponse().setResponseCode(403))
                assertEquals("1", fetch()["checked"].string()); network.takeRequest()
                assertEquals("1", fetch()["checked"].string())
                assertEquals("source-v1", network.takeRequest().getHeader("If-None-Match"))
                assertEquals(403, (runCatching { fetch() }.exceptionOrNull() as HttpStatusException).code)
                assertTrue(runCatching { fetch() }.exceptionOrNull() is IOException)
                assertEquals(3, network.requestCount)
            } finally { cache.close(); network.shutdown() }
        }
    }
}
