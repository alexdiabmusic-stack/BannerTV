package com.alexdiab.stadiatv.catalog

import kotlinx.serialization.Serializable

/** Device-independent identity. Never persist a resolved stream URL or its credentials. */
@Serializable
data class MediaVideo(val id: MediaId, val season: Int? = null, val episode: Int? = null) {
    val valid: Boolean get() = if (id.kind == MediaKind.Movie) season == null && episode == null
        else season != null && season in 0..1000 && episode != null && episode in 1..10000
}

@Serializable
data class MediaProgress(val card: MediaCard, val video: MediaVideo, val positionMillis: Long, val durationMillis: Long,
    val updatedMillis: Long, val episodeTitle: String? = null) {
    val fraction: Float get() = if (durationMillis > 0) (positionMillis.toDouble() / durationMillis).coerceIn(0.0,1.0).toFloat() else 0f
    val completed: Boolean get() = durationMillis > 0 && fraction >= .95f
    val resumable: Boolean get() = video.valid && card.id == video.id && positionMillis >= 30_000 && durationMillis > positionMillis && !completed
}

@Serializable
data class MediaLibrary(val watchlist: List<MediaCard> = emptyList(), val progress: List<MediaProgress> = emptyList()) {
    val continueWatching: List<MediaProgress> get() = progress.filter { it.resumable }.sortedByDescending { it.updatedMillis }
    fun toggle(card: MediaCard): MediaLibrary = copy(watchlist = if (watchlist.any { it.id == card.id }) watchlist.filterNot { it.id == card.id }
        else (listOf(card) + watchlist).take(500))
    fun record(value: MediaProgress): MediaLibrary {
        if (!value.video.valid || value.card.id != value.video.id || value.positionMillis < 0 || value.durationMillis <= 0 || value.updatedMillis <= 0) return this
        val clean = value.copy(positionMillis = value.positionMillis.coerceAtMost(value.durationMillis))
        return copy(progress = (listOf(clean) + progress.filterNot { it.video == clean.video }).sortedByDescending { it.updatedMillis }.take(100))
    }
    fun merge(other: MediaLibrary): MediaLibrary {
        val saved = (watchlist + other.watchlist).distinctBy { it.id }.take(500)
        return (progress + other.progress).sortedBy { it.updatedMillis }.fold(MediaLibrary(watchlist=saved)) { library, entry -> library.record(entry) }
    }
}
