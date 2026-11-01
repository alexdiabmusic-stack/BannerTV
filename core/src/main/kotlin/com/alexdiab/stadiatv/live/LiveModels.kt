package com.alexdiab.stadiatv.live

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class PlaylistKind { M3U, XTREAM }

/** A saved playlist. Xtream credentials are not stored here; see [CredentialStore]. */
@Immutable
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val kind: PlaylistKind,
    val m3uUrl: String = "",
    val host: String = "",
    /** Guide URLs discovered from the playlist itself (`x-tvg-url`, Xtream `xmltv.php`). */
    val epgUrls: List<String> = emptyList(),
    /** Sent for the playlist download, API calls and any stream without its own User-Agent. */
    val userAgent: String = "",
) {
    val summary: String get() = if (kind == PlaylistKind.M3U) m3uUrl.ifBlank { "M3U playlist" } else host.ifBlank { "Stream login" }
}

data class XtreamCredentials(val username: String, val password: String)

/** One playable stream. [id] is unique within a playlist; use [key] across playlists. */
@Immutable
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val group: String,
    val playlistId: String,
    val playlistName: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val language: String? = null,
    /** HTTP headers this provider expects on stream requests (User-Agent, Referer, Origin ...). */
    val headers: Map<String, String> = emptyMap(),
    val xtreamStreamId: Int? = null,
    val xtreamCategoryId: String? = null,
    val canonicalName: String? = null,
    val country: String? = null,
    /** Declared by this exact provider stream; never borrowed from another quality/source. */
    val catchup: CatchupSpec? = null,
) {
    /** Playlist-scoped identity. Two playlists can legitimately reuse the same provider stream number. */
    val key: String get() = "$playlistId|$id"
}

/** One programme in the guide. */
@Immutable
data class Programme(
    val guideId: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
) {
    fun isOn(now: Long) = now in startMillis until endMillis
    fun progress(now: Long): Float =
        if (endMillis <= startMillis) 0f else ((now - startMillis).toFloat() / (endMillis - startMillis)).coerceIn(0f, 1f)
}

/** What the user was last watching, for "Continue Watching". */
data class WatchEntry(val channel: Channel, val lastWatchedMillis: Long)
