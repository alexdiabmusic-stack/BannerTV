package com.alexdiab.stadiatv.live

import android.content.Context
import com.alexdiab.stadiatv.net.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Persisted "Continue Watching": the last channels played, newest first. */
class WatchHistoryStore(context: Context) {
    @Serializable
    private class Entry(
        val lastWatched: Long,
        val id: String,
        val name: String,
        val streamUrl: String,
        val logoUrl: String? = null,
        val group: String = "Other",
        val playlistId: String = "",
        val playlistName: String = "",
        val tvgId: String? = null,
        val language: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    private val prefs = context.getSharedPreferences("stadiatv.watchhistory.v2", Context.MODE_PRIVATE)
    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<WatchEntry>> = _history.asStateFlow()

    fun record(channel: Channel) {
        _history.update { current ->
            (listOf(WatchEntry(channel, System.currentTimeMillis())) + current.filterNot { it.channel.key == channel.key }).take(LIMIT)
        }
        persist()
    }

    fun remove(channelKey: String) {
        _history.update { list -> list.filterNot { it.channel.key == channelKey } }
        persist()
    }

    fun clear() {
        _history.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    /** Restore only references already resolved to channels in the viewer's connected playlists. */
    fun merge(entries: List<WatchEntry>) {
        if (entries.isEmpty()) return
        _history.update { current -> (current + entries).sortedByDescending { it.lastWatchedMillis }.distinctBy { it.channel.key }.take(LIMIT) }
        persist()
    }

    /** History resolved against the live lineup (so renamed or removed channels don't linger), newest first. */
    fun resolved(lineup: List<Channel>): List<WatchEntry> {
        if (lineup.isEmpty()) return emptyList()
        val byKey = lineup.associateBy { it.key }
        return _history.value.mapNotNull { entry -> byKey[entry.channel.key]?.let { WatchEntry(it, entry.lastWatchedMillis) } }
    }

    private fun persist() {
        val entries = _history.value.map {
            Entry(it.lastWatchedMillis, it.channel.id, it.channel.name, it.channel.streamUrl, it.channel.logoUrl, it.channel.group,
                it.channel.playlistId, it.channel.playlistName, it.channel.tvgId, it.channel.language, it.channel.headers)
        }
        prefs.edit().putString(KEY, AppJson.encodeToString(entries)).apply()
    }

    private fun load(): List<WatchEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            AppJson.decodeFromString<List<Entry>>(raw).map {
                WatchEntry(
                    Channel(it.id, it.name, it.streamUrl, it.logoUrl, it.group, it.playlistId, it.playlistName, it.tvgId, language = it.language, headers = it.headers),
                    it.lastWatched,
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY = "history"
        const val LIMIT = 20
    }
}
