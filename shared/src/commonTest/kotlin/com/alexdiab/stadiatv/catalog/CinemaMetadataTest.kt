package com.alexdiab.stadiatv.catalog

import com.alexdiab.stadiatv.TestHttp
import com.alexdiab.stadiatv.net.parseJson
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CinemaMetadataTest {
    @Test fun detailSelectsLocalizedLogoNeutralBackdropAndOrderedCastWithoutInventingImdbRating() = runTest {
        val http=TestHttp { parseJson("""{"id":12,"title":"Example","runtime":107,"release_date":"2026-01-01","vote_average":7.5,"vote_count":20,"imdb_id":"tt123",
          "genres":[{"name":"Drama"}],"images":{"logos":[
          {"file_path":"/english.png","iso_639_1":"en","width":900,"vote_count":50},
          {"file_path":"/french.png","iso_639_1":"fr","width":400,"vote_count":1}],
          "backdrops":[{"file_path":"/text.jpg","iso_639_1":"fr","vote_count":10},{"file_path":"/neutral.jpg","iso_639_1":null}]},
          "credits":{"cast":[{"id":2,"name":"Second","order":1,"character":"B"},{"id":1,"name":"First","order":0,"profile_path":"/first.jpg"}],
          "crew":[{"name":"Director Name","job":"Director"},{"name":"Writer Name","job":"Writer"},{"name":"Writer Name","job":"Screenplay"}]},
          "recommendations":{"results":[{"id":13,"title":"Another","vote_average":0,"vote_count":0},{"id":14,"title":"Excluded","adult":true}]}}""") }
        val detail=TmdbRepository(http) { "fixture" }.detail(MediaId(MediaKind.Movie,12),"fr-CA")
        assertEquals("/french.png",detail.logoPath); assertEquals("/neutral.jpg",detail.heroPath)
        assertEquals(listOf("First","Second"),detail.cast.map { it.name })
        assertEquals(listOf("Director Name"),detail.directors); assertEquals(listOf("Writer Name"),detail.writers)
        assertEquals(7.5,detail.card.rating); assertNull(detail.recommendations.single().rating)
        assertEquals("tt123",detail.addonVideoId()); assertEquals("2026",detail.yearLabel())
        assertTrue(http.calls.single().contains("include_image_language=fr%2Cen%2Cnull"))
        assertTrue(http.calls.single().contains("credits")); assertTrue(http.calls.single().contains("images"))
    }
    @Test fun endedSeriesHasClosingYearAndEpisodeRuntimeAndKeepsSpecials() = runTest {
        val repo=TmdbRepository(TestHttp { parseJson("""{"id":22,"name":"Series","first_air_date":"2020-02-01","last_air_date":"2025-04-10","in_production":false,"episode_run_time":[0,44],"created_by":[{"name":"Creator"}],"seasons":[{"season_number":2},{"season_number":0},{"season_number":1}],"external_ids":{"imdb_id":"tt456"}}""") }) { "fixture" }
        val d=repo.detail(MediaId(MediaKind.Series,22))
        assertEquals(44,d.runtimeMinutes); assertEquals("2020–2025",d.yearLabel()); assertEquals(listOf(0,1,2),d.seasons)
        assertEquals("2020–",d.copy(inProduction=true).yearLabel()); assertEquals(listOf("Creator"),d.creators)
        assertEquals("tt456:0:2",d.addonVideoId(0,2))
    }
    @Test fun discoveryPreservesOtherRowsWhenOneProviderEndpointFails() = runTest {
        val http=TestHttp { url -> when {
            "configuration" in url -> parseJson("""{"images":{"secure_base_url":"https://image.tmdb.org/t/p/","poster_sizes":["w342"],"backdrop_sizes":["w780"],"logo_sizes":["w500"],"profile_sizes":["w185"],"still_sizes":["w300"]}}""")
            "/tv/popular" in url -> error("unavailable")
            "/tv/" in url -> parseJson("""{"page":1,"results":[{"id":10,"name":"Series","backdrop_path":"/series.jpg"}]}""")
            else -> parseJson("""{"page":1,"results":[{"id":10,"title":"Movie","backdrop_path":"/movie.jpg"}]}""")
        } }
        val home=TmdbRepository(http) { "fixture" }.home()
        assertEquals(1,home.shelves.count { it.unavailable }); assertEquals(2,home.featured.size)
        assertEquals(listOf(MediaKind.Movie,MediaKind.Series),home.featured.map { it.id.kind })
        assertEquals("https://image.tmdb.org/t/p/w185/cast.jpg",home.artwork.url("/cast.jpg",ArtworkKind.Profile,185))
        assertEquals("https://image.tmdb.org/t/p/w300/episode.jpg",home.artwork.url("/episode.jpg",ArtworkKind.Still,300))
        assertNull(home.artwork.url("/%2e%2e/secret",ArtworkKind.Logo))
    }
    @Test fun cancellationIsNotConvertedIntoFourUnavailableRows() = runTest {
        val repo=TmdbRepository(TestHttp { awaitCancellation() }) { "fixture" }
        val job=async { repo.home() }; yield(); job.cancel()
        assertFailsWith<CancellationException> { job.await() }
    }
}
