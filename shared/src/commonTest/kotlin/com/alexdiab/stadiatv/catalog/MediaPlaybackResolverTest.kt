package com.alexdiab.stadiatv.catalog

import com.alexdiab.stadiatv.addons.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MediaPlaybackResolverTest {
    private val manifest="""{"id":"fixture","name":"Fixture","version":"1.0.0","types":["series"],"resources":["stream","subtitles"],"catalogs":[]}"""
    private class Vault: AddonVault { var value: String?=null; override fun read()=value; override fun replace(value: String) { this.value=value } }
    private val id=MediaId(MediaKind.Series,22)
    private val detail=MediaDetail(MediaCard(id,"Show","",null,null,null,null),"tt123",emptyList(),null,listOf(1))
    @Test fun episodeResolutionSkipsDisabledAddonsAndRetainsStreamsWhenSubtitlesFail() = runTest {
        val calls=mutableListOf<String>()
        val library=AddonLibrary(StremioClient { url -> calls+=url; when {
            "manifest.json" in url -> manifest
            "/subtitles/" in url -> error("unavailable")
            else -> """{"streams":[{"name":"Source","url":"https://media.example/episode.m3u8"}]}"""
        } },Vault())
        library.connect("https://example.com/on/manifest.json")
        val disabled=library.connect("https://example.com/off/manifest.json"); library.enable(disabled.key,false); calls.clear()
        val result=MediaPlaybackResolver(library).resolve(detail,MediaVideo(id,1,2))
        assertEquals(1,result.sources.size); assertEquals(1,result.enabledProviders); assertEquals(1,result.failedProviders)
        assertTrue(calls.any { "/series/tt123%3A1%3A2.json" in it }); assertFalse(calls.any { "/off/" in it })
    }
    @Test fun timedOutAddonDoesNotPreventAnotherSourceAndMissingIdentityMakesNoRequests() = runTest {
        val library=AddonLibrary(StremioClient { url -> when {
            "manifest.json" in url -> manifest
            "/slow/" in url -> awaitCancellation()
            "/subtitles/" in url -> """{"subtitles":[]}"""
            else -> """{"streams":[{"name":"Fast","url":"https://media.example/movie.m3u8"}]}"""
        } },Vault())
        library.connect("https://example.com/slow/manifest.json");library.connect("https://example.com/fast/manifest.json")
        val resolver=MediaPlaybackResolver(library)
        val result=resolver.resolve(detail,MediaVideo(id,1,2))
        assertEquals("Fast",result.sources.single().stream.name); assertEquals(1,result.failedProviders)
        assertTrue(resolver.resolve(detail.copy(imdbId=null),MediaVideo(id,1,2)).sources.isEmpty())
    }
    @Test fun subtitleTimeoutCannotDiscardAPlayableStream() = runTest {
        val library=AddonLibrary(StremioClient { url -> when {
            "manifest.json" in url -> manifest
            "/subtitles/" in url -> awaitCancellation()
            else -> """{"streams":[{"name":"Playable","url":"https://media.example/movie.m3u8"}]}"""
        } },Vault())
        library.connect("https://example.com/manifest.json")
        val result=MediaPlaybackResolver(library).resolve(detail,MediaVideo(id,1,2))
        assertEquals("Playable",result.sources.single().stream.name);assertEquals(1,result.failedProviders)
    }
}
