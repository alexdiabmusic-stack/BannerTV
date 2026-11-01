package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdiab.stadiatv.AppContainer
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.ChannelFilter
import com.alexdiab.stadiatv.live.ChannelFilterEngine
import com.alexdiab.stadiatv.live.IndexedChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

@Immutable
data class LiveTvUi(
    val channels: List<Channel> = emptyList(),
    val total: Int = 0,
    val categoryCounts: List<Pair<String, Int>> = emptyList(),
    val isIndexing: Boolean = true,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LiveTvViewModel(container: AppContainer) : ViewModel() {
    private val playlists = container.playlists
    private val preferences = container.preferences

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(ChannelFilter())

    private class Index(val entries: List<IndexedChannel>, val categoryCounts: List<Pair<String, Int>>)

    /** The lineup is indexed once per change, off the main thread. Typing in search only re-filters this. */
    private val index: StateFlow<Index?> = playlists.state
        .map { it.revision }
        .distinctUntilChanged()
        .mapLatest {
            val entries = com.alexdiab.stadiatv.util.PerfLog.measure("channel index") { ChannelFilterEngine.index(playlists.state.value.channels) }
            val counts = ChannelFilterEngine.CATEGORIES.mapNotNull { category ->
                entries.count { category in it.categories }.takeIf { it > 0 }?.let { category to it }
            }
            Index(entries, counts)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val ui: StateFlow<LiveTvUi> = combine(
        index,
        filter,
        query.debounce(220).distinctUntilChanged(),
        preferences.settings.map { it.favoriteChannelIds }.distinctUntilChanged(),
    ) { index, filter, query, favorites ->
        if (index == null) LiveTvUi()
        else LiveTvUi(
            channels = ChannelFilterEngine.apply(index.entries, filter, query, favorites),
            total = index.entries.size,
            categoryCounts = index.categoryCounts,
            isIndexing = false,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LiveTvUi())

    fun selectAll() = filter.update { it.copy(favoritesOnly = false, categories = emptySet()) }
    fun selectFavorites() = filter.update { it.copy(favoritesOnly = true, categories = emptySet()) }
    fun selectCategory(category: String) = filter.update { it.copy(favoritesOnly = false, categories = setOf(category)) }
    fun toggleQuality(quality: String) = filter.update { it.copy(qualities = if (quality in it.qualities) it.qualities - quality else it.qualities + quality) }
    fun clearQuality() = filter.update { it.copy(qualities = emptySet()) }
}
