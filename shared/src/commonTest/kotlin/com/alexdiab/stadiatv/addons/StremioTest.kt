package com.alexdiab.stadiatv.addons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class StremioTest {
    private val manifest = """{"id":"example","name":"Private library","version":"1.0.0","types":["movie","series"],"resources":["catalog","meta",{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]},"subtitles"],"catalogs":[{"type":"movie","id":"latest","extra":[{"name":"search","isRequired":true},{"name":"skip"}]}]}"""
    private class Vault : AddonVault {
        var text: String? = null
        var fail = false
        override fun read() = text
        override fun replace(value: String) { if (fail) error("storage unavailable"); text = value }
    }

    @Test fun supportsPublicPrivateAndSelfHostedHTTPSWithoutLoggingConfiguration() {
        for (host in listOf("example.com", "192.168.1.5:8443", "[::1]:8443", "aio.internal")) {
            val address = ManifestAddress.parse("https://$host/stremio/user/private-token/variant/manifest.json?signature=secret")
            assertEquals("https://$host/stremio/user/private-token/variant/configure?signature=secret", address.configurationPage())
            assertFalse(address.toString().contains("secret"))
            assertFalse(address.toString().contains("private-token"))
        }
    }
    @Test fun rejectsAmbiguousOrInsecureManifests() {
        for (url in listOf("http://host/manifest.json", "https://user:pass@host/manifest.json", "https://host/path", "https://host/../manifest.json",
            "https://host/%2e%2e/manifest.json", "https://host/manifest.json#token", "https://host/manifest.json\nsecret", "https://host\\other/manifest.json")) {
            assertFailsWith<AddonProblem> { ManifestAddress.parse(url) }
        }
    }
    @Test fun resourceRequestsPreserveConfiguredPrefixAndQueryAndEncodePathSegments() = runTest {
        val calls = mutableListOf<String>()
        val client = StremioClient { url -> calls += url; when {
            "manifest.json" in url -> manifest
            "/catalog/" in url -> """{"metas":[{"id":"tt100","type":"movie","name":"Film"}]}"""
            "/meta/" in url -> """{"meta":{"id":"tt100","type":"series","name":"Series","videos":[{"id":"tt100:2:3","season":2,"episode":3,"title":"Episode"}]}}"""
            "/stream/" in url -> """{"streams":[{"name":"HTTPS","url":"https://media.example/movie.m3u8","behaviorHints":{"proxyHeaders":{"request":{"Authorization":"example-secret"}}}},{"infoHash":"123"}]}"""
            else -> """{"subtitles":[{"lang":"eng","url":"https://media.example/en.vtt"}]}"""
        } }
        val a = ManifestAddress.parse("https://aio.example/config/private/manifest.json?token=private")
        val m = client.connect(a)
        assertEquals("Film",client.catalog(a,m,"movie","latest",mapOf("search" to "ice & fire", "skip" to "0")).single().name)
        assertEquals("https://aio.example/config/private/catalog/movie/latest/search=ice%20%26%20fire&skip=0.json?token=private",calls.last())
        assertEquals("tt100:2:3",client.metadata(a,m,"series","tt100").videos.single().id)
        val stream=client.streams(a,m,"series","tt100:2:3").single()
        assertEquals("example-secret",stream.headers["Authorization"])
        assertTrue(calls.last().contains("/series/tt100%3A2%3A3.json"))
        assertEquals("eng",client.subtitles(a,m,"series","tt100:2:3").single().language)
        val previous = calls.size
        assertFailsWith<AddonProblem> { client.catalog(a,m,"movie","latest") }
        assertFailsWith<AddonProblem> { client.streams(a,m,"movie","unsupported-id") }
        assertEquals(previous,calls.size)
    }
    @Test fun multipleConfigurationsRemainSeparateAndFailedEditsDoNotOverwriteGoodCredentials() = runTest {
        val vault = Vault()
        val library=AddonLibrary(StremioClient { if ("broken" in it) error("transport containing secret"); manifest },vault)
        val first=library.connect("https://host/a/manifest.json")
        val second=library.connect("https://host/b/manifest.json")
        assertNotEquals(first.key,second.key)
        assertEquals(2,library.list().size)
        assertFailsWith<AddonProblem> { library.connect("https://host/a/manifest.json") }
        val before=vault.text
        val error=assertFailsWith<AddonProblem> { library.connect("https://host/broken/manifest.json",first.key) }
        assertFalse(error.message.orEmpty().contains("secret"))
        assertEquals(before,vault.text)
        library.enable(first.key,false)
        assertFailsWith<AddonProblem> { library.connection(first.key) }
        assertFalse(library.check(first.key).enabled)
        library.remove(second.key)
        assertEquals(first.key,library.list().single().key)
        vault.fail=true
        assertFailsWith<AddonProblem> { library.enable(first.key,true) }
        assertFalse(library.list().single().enabled)
    }
    @Test fun cancellationAndLockedVaultNeverBecomeAnEmptyLibrary() = runTest {
        val address=ManifestAddress.parse("https://host/manifest.json")
        assertFailsWith<CancellationException> { StremioClient { throw CancellationException() }.connect(address) }
        val locked=object : AddonVault {
            override fun read(): String? = error("locked")
            override fun replace(value: String) = error("locked")
        }
        assertFailsWith<AddonProblem> { AddonLibrary(StremioClient { manifest },locked).list() }
    }
}
