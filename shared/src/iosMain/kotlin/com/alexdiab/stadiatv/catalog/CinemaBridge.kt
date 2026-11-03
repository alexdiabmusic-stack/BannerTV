package com.alexdiab.stadiatv.catalog

import com.alexdiab.stadiatv.addons.AddonLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Host storage for non-secret watchlist and progress metadata. A failed read MUST throw. */
interface CinemaStateStore {
    @Throws(Exception::class) fun read(): String?
    @Throws(Exception::class) fun replace(value: String)
}

/** Retain and cancel from Swift on view disappearance / search changes. */
class CinemaRequest internal constructor(private val job: Job) {
    fun cancel() { job.cancel() }
}

@Serializable
data class CinemaCardWire(
    val kind: String,
    val id: Int,
    val title: String,
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val rating: Double? = null,
    val posterURL: String? = null,
    val backdropURL: String? = null,
    val logoURL: String? = null
)

@Serializable data class CinemaRowWire(val name: String, val items: List<CinemaCardWire>)
@Serializable data class CinemaHomeWire(val featured: List<CinemaCardWire>, val rows: List<CinemaRowWire>)
@Serializable data class CinemaPageWire(val items: List<CinemaCardWire>, val hasMore: Boolean)
@Serializable data class CinemaPersonWire(val name: String, val role: String, val portraitURL: String?)
@Serializable data class CinemaDetailWire(
    val card: CinemaCardWire, val genres: List<String>, val runtimeMinutes: Int?, val tagline: String?,
    val cast: List<CinemaPersonWire>, val directors: List<String>, val creators: List<String>,
    val seasons: List<Int>, val recommendations: List<CinemaCardWire>, val imdbIdVerified: Boolean
)
@Serializable data class CinemaEpisodeWire(
    val season: Int, val number: Int, val title: String, val overview: String,
    val stillURL: String?, val runtimeMinutes: Int?, val airDate: String?
)
@Serializable data class CinemaResumeWire(
    val card: CinemaCardWire, val season: Int?, val episode: Int?,
    val positionMillis: Long, val durationMillis: Long, val updatedMillis: Long, val episodeTitle: String?
)
@Serializable data class CinemaLibraryWire(val saved: List<CinemaCardWire>, val continuing: List<CinemaResumeWire>)
@Serializable data class CinemaSourceWire(
    val provider: String, val name: String, val url: String, val headers: Map<String, String>
)
@Serializable data class CinemaSourcesWire(
    val sources: List<CinemaSourceWire>, val enabledProviders: Int, val failedProviders: Int
)
@Serializable data class CinemaAddonWire(val key: String, val name: String, val enabled: Boolean)

/**
 * Objective-C/Swift-friendly string/JSON facade over the ALREADY EXISTING shared TMDB,
 * Stremio add-on, resolver, and media-library implementations.
 *
 * Does not create a second metadata client, make up an IMDb ID, host sources, or store URLs.
 * Completion is always invoked from Main on success/failure (but NOT after cancellation).
 */
class CinemaBridge(
    private val tmdb: TmdbRepository,
    private val resolver: MediaPlaybackResolver,
    private val addons: AddonLibrary,
    private val store: CinemaStateStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var artwork: TmdbArtwork? = null

    private fun run(completion: (String?, String?) -> Unit, block: suspend () -> String): CinemaRequest =
        CinemaRequest(scope.launch {
            try {
                val output = block()
                ensureActive()
                completion(output, null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // In particular, don't leak configured add-on URLs or TMDB credentials.
                completion(null, "This content is temporarily unavailable. Check your connection or setup.")
            }
        })

    private suspend fun art(): TmdbArtwork = artwork ?: tmdb.artwork().also { artwork = it }
    private fun String.kind(): MediaKind = when (lowercase()) {
        "movie" -> MediaKind.Movie
        "series", "tv" -> MediaKind.Series
        else -> throw IllegalArgumentException("Unsupported media kind")
    }
    private fun CinemaCardWire.mediaCard(): MediaCard = MediaCard(
        MediaId(kind.kind(), id), title, overview, posterPath, backdropPath, releaseDate, rating
    )
    private fun MediaCard.wire(a: TmdbArtwork?, logo: String? = null) = CinemaCardWire(
        kind = id.kind.path, id = id.value, title = title, overview = overview,
        posterPath = posterPath, backdropPath = backdropPath, releaseDate = releaseDate, rating = rating,
        posterURL = a?.url(posterPath, ArtworkKind.Poster, 500),
        backdropURL = a?.url(backdropPath, ArtworkKind.Backdrop, 1280),
        logoURL = a?.url(logo, ArtworkKind.Logo, 500)
    )

    fun home(language: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val result = tmdb.home(language)
        artwork = result.artwork
        json.encodeToString(CinemaHomeWire(
            result.featured.map { it.wire(result.artwork) },
            result.shelves.map { CinemaRowWire(it.rail.title, it.titles.map { card -> card.wire(result.artwork) }) }
        ))
    }

    fun search(query: String, page: Int, language: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val response = tmdb.search(query.trim(), page, language)
        val a = art()
        json.encodeToString(CinemaPageWire(response.results.map { it.wire(a) }, response.page < response.pages))
    }

    /** row = popularMovies | popularSeries | trendingMovies | trendingSeries */
    fun browse(row: String, page: Int, language: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val selected = when (row) {
            "popularMovies" -> MediaRail.PopularMovies
            "popularSeries" -> MediaRail.PopularSeries
            "trendingMovies" -> MediaRail.TrendingMovies
            "trendingSeries" -> MediaRail.TrendingSeries
            else -> throw IllegalArgumentException("Unknown shelf")
        }
        val response = tmdb.browse(selected, page, language)
        val a = art()
        json.encodeToString(CinemaPageWire(response.results.map { it.wire(a) }, response.page < response.pages))
    }

    fun detail(kind: String, id: Int, language: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val d = tmdb.detail(MediaId(kind.kind(), id), language)
        val a = art()
        json.encodeToString(CinemaDetailWire(
            card = d.card.wire(a, d.logoPath).copy(backdropURL = a.url(d.heroPath, ArtworkKind.Backdrop, 1280)),
            genres = d.genres, runtimeMinutes = d.runtimeMinutes, tagline = d.tagline,
            cast = d.cast.map { CinemaPersonWire(it.name, it.role, a.url(it.profilePath, ArtworkKind.Profile, 342)) },
            directors = d.directors, creators = d.creators, seasons = d.seasons,
            recommendations = d.recommendations.map { it.wire(a) },
            imdbIdVerified = d.imdbId != null
        ))
    }

    fun episodes(id: Int, season: Int, language: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val a = art()
        json.encodeToString(tmdb.episodes(MediaId(MediaKind.Series, id), season, language).map {
            CinemaEpisodeWire(it.season, it.number, it.title, it.overview,
                a.url(it.stillPath, ArtworkKind.Still, 780), it.runtimeMinutes, it.airDate)
        })
    }

    fun sources(kind: String, id: Int, season: Int, episode: Int, language: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val mediaId = MediaId(kind.kind(), id)
        val video = MediaVideo(mediaId, season.takeIf { it >= 0 }, episode.takeIf { it > 0 })
        val detail = tmdb.detail(mediaId, language)
        val found = resolver.resolve(detail, video)
        json.encodeToString(CinemaSourcesWire(
            found.sources.map { CinemaSourceWire(it.provider, it.stream.name, it.stream.url, it.stream.headers) },
            found.enabledProviders, found.failedProviders
        ))
    }

    private fun readLibrary(): MediaLibrary = store.read()?.let { json.decodeFromString<MediaLibrary>(it) } ?: MediaLibrary()
    private fun writeLibrary(value: MediaLibrary) = store.replace(json.encodeToString(value))

    fun library(completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        // Offline library should not depend on TMDB configuration network availability.
        val a = artwork
        mutex.withLock {
            val data = readLibrary()
            json.encodeToString(CinemaLibraryWire(data.watchlist.map { it.wire(a) },
                data.continueWatching.map {
                    CinemaResumeWire(it.card.wire(a), it.video.season, it.video.episode,
                        it.positionMillis, it.durationMillis, it.updatedMillis, it.episodeTitle)
                }))
        }
    }

    /** cardJson must be a CinemaCardWire returned by this bridge. */
    fun toggleSaved(cardJson: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val card = json.decodeFromString<CinemaCardWire>(cardJson).mediaCard()
        mutex.withLock {
            val result = readLibrary().toggle(card)
            writeLibrary(result)
            json.encodeToString(result.watchlist.any { it.id == card.id })
        }
    }

    fun recordProgress(
        cardJson: String, season: Int, episode: Int, positionMillis: Long,
        durationMillis: Long, updatedMillis: Long, episodeTitle: String?,
        completion: (String?, String?) -> Unit
    ): CinemaRequest = run(completion) {
        val card = json.decodeFromString<CinemaCardWire>(cardJson).mediaCard()
        val progress = MediaProgress(card, MediaVideo(card.id, season.takeIf { it >= 0 }, episode.takeIf { it > 0 }),
            positionMillis, durationMillis, updatedMillis, episodeTitle)
        mutex.withLock { writeLibrary(readLibrary().record(progress)) }
        "{}"
    }

    fun listAddons(completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        json.encodeToString(addons.list().map { CinemaAddonWire(it.key, it.name, it.enabled) })
    }
    fun addAddon(manifestUrl: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        val connected = addons.connect(manifestUrl)
        json.encodeToString(CinemaAddonWire(connected.key, connected.name, connected.enabled))
    }
    fun enableAddon(key: String, enabled: Boolean, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        addons.enable(key, enabled)
        "{}"
    }
    fun removeAddon(key: String, completion: (String?, String?) -> Unit): CinemaRequest = run(completion) {
        addons.remove(key)
        "{}"
    }

    fun close() { scope.cancel() }
}
