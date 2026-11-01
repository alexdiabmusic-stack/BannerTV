package com.alexdiab.stadiatv.catalog

import com.alexdiab.stadiatv.TestHttp
import com.alexdiab.stadiatv.net.parseJson
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TmdbTest {
    @Test fun searchesUseBearerHeaderAndTypedIdentitiesNeverReturnPeopleAsShows() = runTest {
        val http=TestHttp { parseJson("""{"page":1,"total_pages":1,"results":[{"id":5,"media_type":"movie","title":"Film","adult":false},{"id":5,"media_type":"tv","name":"Show"},{"id":1,"media_type":"person","name":"Person"}]}""") }
        val page=TmdbRepository(http) { "unit-test-token" }.search("space & time")
        assertEquals(2,page.results.size)
        assertNotEquals(page.results[0].id,page.results[1].id)
        assertTrue(http.calls.single().contains("query=space%20%26%20time"))
        assertFalse(http.calls.single().contains("unit-test-token"))
        assertEquals("Bearer unit-test-token",http.headers.single()["Authorization"])
    }
    @Test fun episodeResolutionRequiresVerifiedIMDbIdentityAndSeason() = runTest {
        val http=TestHttp { parseJson("""{"id":42,"name":"Show","external_ids":{"imdb_id":"tt123"},"seasons":[{"season_number":0},{"season_number":2}]}""") }
        val title=TmdbRepository(http) { "unit-test-token" }.detail(MediaId(MediaKind.Series,42))
        assertNull(title.addonVideoId())
        assertEquals("tt123:2:3",title.addonVideoId(2,3))
        assertNull(title.copy(imdbId=null).addonVideoId(2,3))
        assertEquals(listOf(0,2),title.seasons)
    }
    @Test fun artworkUsesConfigurationSizesAndRejectsPathsOutsideImageHost() {
        val art=TmdbArtwork("https://image.tmdb.org/t/p/",listOf("w92","w500","original"),listOf("w780","w1280"))
        assertEquals("https://image.tmdb.org/t/p/w500/photo.jpg",art.url("/photo.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w1280/photo.jpg",art.url("/photo.jpg",true,1000))
        assertNull(art.url("https://elsewhere/image"))
        assertNull(art.url("/../secret"))
    }
}
