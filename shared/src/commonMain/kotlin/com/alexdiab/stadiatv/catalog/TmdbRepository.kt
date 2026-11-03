package com.alexdiab.stadiatv.catalog

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.*

@Serializable
enum class MediaKind(val path: String) { Movie("movie"), Series("tv") }
@Serializable
data class MediaId(val kind: MediaKind, val value: Int) { init { require(value > 0) } }
@Serializable
data class MediaCard(val id: MediaId, val title: String, val overview: String, val posterPath: String?, val backdropPath: String?, val releaseDate: String?, val rating: Double?, val genreIds: List<Int> = emptyList())
data class MediaPage(val page: Int, val pages: Int, val results: List<MediaCard>)
data class MediaEpisode(val season: Int, val number: Int, val title: String, val overview: String, val stillPath: String?, val runtimeMinutes: Int? = null, val airDate: String? = null)
data class MediaPerson(val id: Int, val name: String, val role: String, val profilePath: String?)
data class MediaDetail(val card: MediaCard, val imdbId: String?, val genres: List<String>, val runtimeMinutes: Int?, val seasons: List<Int>,
    val logoPath: String? = null, val heroPath: String? = null, val cast: List<MediaPerson> = emptyList(),
    val directors: List<String> = emptyList(), val writers: List<String> = emptyList(), val creators: List<String> = emptyList(),
    val status: String? = null, val lastAirDate: String? = null, val inProduction: Boolean = false,
    val recommendations: List<MediaCard> = emptyList(), val tagline: String? = null) {
    /** Only an explicitly returned IMDb identity may be sent to an IMDb-based Stremio add-on. */
    fun addonVideoId(season: Int? = null, episode: Int? = null): String? = imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { id ->
        if (card.id.kind == MediaKind.Movie) id else if (season != null && episode != null && season >= 0 && episode > 0) "$id:$season:$episode" else null
    }
    fun yearLabel(): String {
        val first = card.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null } ?: return ""
        if (card.id.kind == MediaKind.Movie) return first
        val last = lastAirDate?.take(4)?.takeIf { it.toIntOrNull() != null }
        return when { inProduction -> "$first–"; last != null && last != first -> "$first–$last"; else -> first }
    }
}
enum class ArtworkKind { Poster, Backdrop, Logo, Profile, Still }
class TmdbArtwork internal constructor(private val base: String, private val posterSizes: List<String>, private val backdropSizes: List<String>,
    private val logoSizes: List<String> = posterSizes, private val profileSizes: List<String> = posterSizes, private val stillSizes: List<String> = backdropSizes) {
    fun url(path: String?, backdrop: Boolean = false, desiredWidth: Int = 500): String? {
        return url(path, if (backdrop) ArtworkKind.Backdrop else ArtworkKind.Poster, desiredWidth)
    }
    fun url(path: String?, kind: ArtworkKind, desiredWidth: Int = 500): String? {
        if (path == null || !path.matches(Regex("/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+"))) return null
        val sizes = when (kind) { ArtworkKind.Poster -> posterSizes; ArtworkKind.Backdrop -> backdropSizes; ArtworkKind.Logo -> logoSizes; ArtworkKind.Profile -> profileSizes; ArtworkKind.Still -> stillSizes }
        val widths = sizes.mapNotNull { s -> s.removePrefix("w").toIntOrNull()?.let { it to s } }.sortedBy { it.first }
        val size = widths.firstOrNull { it.first >= desiredWidth }?.second ?: widths.lastOrNull()?.second ?: "original".takeIf { it in sizes } ?: return null
        return base.trimEnd('/') + "/$size$path"
    }
}

enum class MediaRail(val title: String, val kind: MediaKind, val trending: Boolean) {
    PopularMovies("Popular · Movies", MediaKind.Movie, false), PopularSeries("Popular · Series", MediaKind.Series, false),
    TrendingMovies("Trending this week · Movies", MediaKind.Movie, true), TrendingSeries("Trending this week · Series", MediaKind.Series, true)
}
data class MediaShelf(val rail: MediaRail, val titles: List<MediaCard>, val unavailable: Boolean = false)
data class CinemaHome(val artwork: TmdbArtwork, val shelves: List<MediaShelf>) {
    val featured: List<MediaCard> get() {
        val movies = shelves.firstOrNull { it.rail == MediaRail.TrendingMovies }?.titles.orEmpty()
        val series = shelves.firstOrNull { it.rail == MediaRail.TrendingSeries }?.titles.orEmpty()
        return (0 until maxOf(movies.size, series.size)).flatMap { listOfNotNull(movies.getOrNull(it), series.getOrNull(it)) }
            .ifEmpty { shelves.flatMap { it.titles } }.filter { it.backdropPath != null }.distinctBy { it.id }.take(6)
    }
}

/** TMDB is metadata, never a playback provider. The host injects a token from secure storage; no embedded key. */
class TmdbRepository(private val http: SportsHttp, private val readToken: () -> String) {
    private suspend fun get(path: String, query: Map<String,String> = emptyMap(), ttl: Long = 300_000): JsonElement {
        val credential = readToken().trim()
        require(credential.isNotEmpty() && credential.length <= 2048 &&
            credential.none { it.code < 33 || it.code > 126 }) { "TMDB credentials are unavailable" }
        // TMDB supports both a v3 personal API key (32 hexadecimal characters) and
        // the v4 API Read Access Token. Nuvio's personal-key setting uses the former.
        // Never put a v4 bearer token in a URL.
        val v3Key = credential.matches(Regex("[A-Fa-f0-9]{32}"))
        val parameters = if (v3Key) query + ("api_key" to credential) else query
        val headers = if (v3Key) mapOf("Accept" to "application/json")
            else mapOf("Authorization" to "Bearer $credential", "Accept" to "application/json")
        return http.getJson(requestURL("https://api.themoviedb.org/3/$path", parameters),
            headers, ttlMillis = ttl, maxResponseBytes = 4*1024*1024)
    }
    @Throws(Exception::class)
    suspend fun artwork(): TmdbArtwork {
        val images=get("configuration",ttl=86_400_000)["images"]
        val base=images["secure_base_url"].string()
        if (base.trimEnd('/') != "https://image.tmdb.org/t/p") throw SportsIOException("Invalid TMDB artwork configuration")
        return TmdbArtwork(base,images["poster_sizes"].array.map { it.string() },images["backdrop_sizes"].array.map { it.string() },
            images["logo_sizes"].array.map { it.string() },images["profile_sizes"].array.map { it.string() },images["still_sizes"].array.map { it.string() })
    }
    /** A fresh, authenticated TMDB request for the iPhone connection check. */
    @Throws(Exception::class)
    suspend fun checkConnection(): Boolean {
        val images = get("configuration", ttl = 0)["images"]
        return images["secure_base_url"].string().trimEnd('/') == "https://image.tmdb.org/t/p"
    }

    @Throws(Exception::class)
    suspend fun search(query: String, page: Int = 1, language: String = "en-US"): MediaPage {
        require(query.isNotBlank() && query.length <= 512); require(page in 1..500)
        return page(get("search/multi",mapOf("query" to query.trim(),"page" to page.toString(),"language" to language,"include_adult" to "false")),null)
    }
    @Throws(Exception::class)
    suspend fun popular(kind: MediaKind, page: Int = 1, language: String = "en-US"): MediaPage {
        require(page in 1..500)
        return page(get("${kind.path}/popular",mapOf("page" to page.toString(),"language" to language)),kind)
    }
    @Throws(Exception::class)
    suspend fun browse(rail: MediaRail, page: Int = 1, language: String = "en-US"): MediaPage {
        require(page in 1..500)
        val path = if (rail.trending) "trending/${rail.kind.path}/week" else "${rail.kind.path}/popular"
        return page(get(path,mapOf("page" to page.toString(),"language" to language)),rail.kind)
    }
    @Throws(Exception::class)
    suspend fun home(language: String = "en-US"): CinemaHome = supervisorScope {
        val art = async { artwork() }
        val shelves = MediaRail.entries.map { rail -> async {
            try { MediaShelf(rail,browse(rail,language=language).results) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { MediaShelf(rail,emptyList(),unavailable=true) }
        } }
        CinemaHome(art.await(),shelves.awaitAll())
    }
    @Throws(Exception::class)
    suspend fun detail(id: MediaId, language: String = "en-US",
        includeArtwork: Boolean = true, includeCredits: Boolean = true,
        includeRecommendations: Boolean = true): MediaDetail {
        val imageLanguage = language.substringBefore('-').lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val append = listOf("external_ids") +
            listOfNotNull(if (includeArtwork) "images" else null,
                if (includeCredits) "credits" else null,
                if (includeRecommendations) "recommendations" else null)
        val params = mutableMapOf("language" to language, "append_to_response" to append.joinToString(","))
        if (includeArtwork) params["include_image_language"] =
            listOf(imageLanguage, "en", "null").distinct().joinToString(",")
        val raw = get("${id.kind.path}/${id.value}", params)
        val card=card(raw,id.kind)?.takeIf { it.id == id } ?: throw SportsIOException("TMDB returned a different title")
        val crew = raw["credits"]["crew"].array
        fun crewNames(jobs: Set<String>) = crew.filter { it["job"].string() in jobs }.mapNotNull { it["name"].stringOrNull() }.distinct()
        val runtime = if (id.kind == MediaKind.Movie) raw["runtime"].int() else raw["episode_run_time"].array.firstOrNull { it.int() > 0 }?.int() ?: raw["last_episode_to_air"]["runtime"].int()
        return MediaDetail(card,(raw["imdb_id"].stringOrNull() ?: raw["external_ids"]["imdb_id"].stringOrNull())?.takeIf { it.matches(Regex("tt[0-9]+")) },
            raw["genres"].array.mapNotNull { it["name"].stringOrNull() },runtime.takeIf { it > 0 },
            raw["seasons"].array.mapNotNull { it["season_number"].string().toIntOrNull()?.takeIf { it >= 0 } }.distinct().sorted(),
            logoPath = selectImage(raw["images"]["logos"],imageLanguage), heroPath = selectImage(raw["images"]["backdrops"],imageLanguage,neutralFirst=true) ?: card.backdropPath,
            cast = raw["credits"]["cast"].array.sortedBy { it["order"].int(Int.MAX_VALUE) }.mapNotNull { person ->
                val personId = person["id"].int().takeIf { it > 0 } ?: return@mapNotNull null
                val name = person["name"].stringOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                MediaPerson(personId,name,person["character"].string(),person["profile_path"].stringOrNull())
            }.distinctBy { it.id }.take(30), directors=crewNames(setOf("Director")), writers=crewNames(setOf("Writer","Screenplay","Story","Teleplay")),
            creators=raw["created_by"].array.mapNotNull { it["name"].stringOrNull() }.distinct(), status=raw["status"].stringOrNull(),
            lastAirDate=raw["last_air_date"].stringOrNull(), inProduction=raw["in_production"].bool(),
            recommendations=raw["recommendations"]["results"].array.mapNotNull { card(it,id.kind) }.filter { it.id != id }.distinctBy { it.id }.take(20),
            tagline=raw["tagline"].stringOrNull()?.takeIf(String::isNotBlank))
    }
    @Throws(Exception::class)
    suspend fun episodes(id: MediaId, season: Int, language: String = "en-US"): List<MediaEpisode> {
        require(id.kind == MediaKind.Series && season in 0..1000)
        val raw=get("tv/${id.value}/season/$season",mapOf("language" to language))
        if (raw["season_number"].string().toIntOrNull() != season) throw SportsIOException("TMDB returned a different season")
        return raw["episodes"].array.mapNotNull { r ->
            if (r["season_number"].int(-1) != season) return@mapNotNull null
            val number=r["episode_number"].string().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            MediaEpisode(season,number,r["name"].string("Episode $number"),r["overview"].string(),r["still_path"].stringOrNull(),r["runtime"].int().takeIf { it > 0 },r["air_date"].stringOrNull())
        }.distinctBy { it.number }.sortedBy { it.number }
    }
    private fun page(raw: JsonElement, kind: MediaKind?) = MediaPage(raw["page"].int(1),raw["total_pages"].int().coerceIn(0,500),raw["results"].array.mapNotNull { card(it,kind) })
    private fun card(raw: JsonElement, suppliedKind: MediaKind?): MediaCard? {
        if (raw["adult"].bool()) return null
        val kind=suppliedKind ?: when(raw["media_type"].string()) { "movie" -> MediaKind.Movie; "tv" -> MediaKind.Series; else -> return null }
        val id=raw["id"].string().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val title=raw[if(kind==MediaKind.Movie) "title" else "name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return MediaCard(MediaId(kind,id),title,raw["overview"].string(),raw["poster_path"].stringOrNull(),raw["backdrop_path"].stringOrNull(),
            raw[if(kind==MediaKind.Movie) "release_date" else "first_air_date"].stringOrNull(),raw["vote_average"].string().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= 10.0 && raw["vote_count"].int(1) > 0 },raw["genre_ids"].array.map { it.int() }.filter { it > 0 })
    }
    private fun selectImage(images: JsonElement?, language: String, neutralFirst: Boolean = false): String? = images.array
        .filter { it["file_path"].string().matches(Regex("/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) && it["width"].int(1) > 0 }
        .sortedWith(compareBy<JsonElement> {
            val tag=it["iso_639_1"].stringOrNull()
            when { neutralFirst && tag == null -> 0; tag == language -> 1; tag == null -> 2; tag == "en" -> 3; else -> 4 }
        }.thenByDescending { it["vote_count"].int() }.thenByDescending { it["vote_average"].string().toDoubleOrNull() ?: 0.0 }
            .thenByDescending { it["width"].int() })
        .firstOrNull()?.get("file_path")?.stringOrNull()
    companion object {
        const val ATTRIBUTION = "This product uses the TMDB API but is not endorsed or certified by TMDB."
    }
}
