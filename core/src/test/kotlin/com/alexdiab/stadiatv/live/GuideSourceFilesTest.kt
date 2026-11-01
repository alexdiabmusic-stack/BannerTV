package com.alexdiab.stadiatv.live

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuideSourceFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun normalReadsReuseFreshFileButExplicitRefreshDownloadsAgain() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("first")); server.enqueue(MockResponse().setBody("second")); server.start()
        try {
            val files = GuideSourceFiles(folder.newFolder(), OkHttpClient(), 1024)
            val url = server.url("/guide").toString()
            assertEquals("first", files.read(url).readText())
            assertEquals("first", files.read(url).readText()); assertEquals(1, server.requestCount)
            assertEquals("second", files.read(url, force = true).readText()); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun explicitRefreshAlsoBypassesOkHttpDiskCache() {
        val server = MockWebServer(); server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody("cached")); server.enqueue(MockResponse().setBody("network")); server.start()
        val cache = Cache(folder.newFolder(), 1_000_000)
        try {
            val files = GuideSourceFiles(folder.newFolder(), OkHttpClient.Builder().cache(cache).build(), 1024)
            val url = server.url("/guide").toString()
            assertEquals("cached", files.read(url).readText())
            assertEquals("network", files.read(url, force = true).readText())
            assertEquals(2, server.requestCount)
            server.takeRequest(); assertEquals("no-cache", server.takeRequest().getHeader("Cache-Control"))
        } finally { cache.close(); server.shutdown() }
    }

    @Test fun failedRefreshRetainsLastCompleteFileAndItsOriginalTime() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("kept")); server.enqueue(MockResponse().setResponseCode(503)); server.start()
        try {
            var clock = 1_000_000L
            val directory = folder.newFolder()
            val files = GuideSourceFiles(directory, OkHttpClient(), 1024) { clock }
            val url = server.url("/guide").toString()
            val first = files.read(url); assertEquals(clock, first.lastModified())
            clock += 30_000_000L
            var savedFallback = false
            assertEquals("kept", files.read(url, force = true, onSavedFallback = { savedFallback = true }).readText())
            assertTrue(savedFallback)
            assertEquals(1_000_000L, first.lastModified())
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } finally { server.shutdown() }
    }

    @Test fun bothKnownAndChunkedSizeLimitsPreservePriorCompleteData() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("kept")); server.enqueue(MockResponse().setBody("x".repeat(65))); server.enqueue(MockResponse().setChunkedBody("y".repeat(65), 7)); server.start()
        try {
            val directory = folder.newFolder()
            val files = GuideSourceFiles(directory, OkHttpClient(), 64)
            val url = server.url("/guide").toString()
            assertEquals("kept", files.read(url).readText())
            repeat(2) { assertEquals("kept", files.read(url, force = true).readText()) }
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } finally { server.shutdown() }
    }

    @Test fun hashCollidingProviderUrlsCannotReuseEachOthersGuide() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("provider-a")); server.enqueue(MockResponse().setBody("provider-b")); server.start()
        try {
            val a = server.url("/Aa").toString(); val b = server.url("/BB").toString()
            assertEquals(a.hashCode(), b.hashCode()) // The previous file-name algorithm collided.
            val files = GuideSourceFiles(folder.newFolder(), OkHttpClient(), 1024)
            val first = files.read(a); val second = files.read(b)
            assertNotEquals(first.name, second.name)
            assertEquals("provider-a", first.readText()); assertEquals("provider-b", second.readText())
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun cancellingAReplacementNeverReturnsAStaleFileAsSuccessfulNewWork() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("kept")); server.enqueue(MockResponse().setBody("x".repeat(64_000))); server.start()
        try {
            val directory = folder.newFolder()
            val files = GuideSourceFiles(directory, OkHttpClient(), 100_000)
            val url = server.url("/guide").toString(); val original = files.read(url)
            var checks = 0
            val result = runCatching { files.read(url, force = true) { ++checks < 4 } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals("kept", original.readText())
            assertFalse(directory.listFiles()!!.any { it.extension == "part" })
        } finally { server.shutdown() }
    }

    @Test fun futureTimestampDoesNotPinAFileAfterTheClockChanges() {
        val server = MockWebServer(); server.enqueue(MockResponse().setBody("one")); server.enqueue(MockResponse().setBody("two")); server.start()
        try {
            var clock = 2_000_000L
            val files = GuideSourceFiles(folder.newFolder(), OkHttpClient(), 1024) { clock }
            val url = server.url("/guide").toString(); files.read(url)
            clock -= 1_000_000
            assertEquals("two", files.read(url).readText()); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun gzipBytesStayCompressedAndFileNamesContainNoUrlCredentials() {
        val body = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write("<tv/>".toByteArray()) } }.toByteArray()
        val server = MockWebServer(); server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(body))); server.start()
        try {
            val files = GuideSourceFiles(folder.newFolder(), OkHttpClient(), 1024)
            val file = files.read(server.url("/guide?username=constructed-user&password=constructed-secret").toString())
            assertArrayEquals(body, file.readBytes())
            assertTrue(file.name.matches(Regex("[a-f0-9]{64}\\.xml")))
            assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
        } finally { server.shutdown() }
    }
}
