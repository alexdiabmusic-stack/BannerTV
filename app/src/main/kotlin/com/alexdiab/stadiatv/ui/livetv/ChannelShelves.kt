package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdiab.stadiatv.AppContainer
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.lineup.GuideRef
import com.alexdiab.stadiatv.lineup.Lineup
import com.alexdiab.stadiatv.lineup.LineupPrefs
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** A titled row of channels with what is on them, for a discovery screen. */
@Immutable
class ChannelShelf(val key: String, val title: String, val rows: List<GuideRow>)

/**
 * The channels worth putting in front of a viewer, grouped the way they go looking: what they were just watching,
 * what they starred, sport that is on now, then by kind. Built from the same lineup and guide as the TV Guide, so a
 * tile always shows a channel with a trustworthy name, the stream that will play, and what is on it.
 */
@Immutable
class ChannelShelves(
    /** What the viewer watched last, newest first, one tile per channel; the first is where "resume" goes. */
    val recent: List<GuideRow> = emptyList(),
    val shelves: List<ChannelShelf> = emptyList(),
    /** Every starred channel with what is on it, uncapped (a shelf shows the first few of these). */
    val favorites: List<GuideRow> = emptyList(),
    val total: Int = 0,
    val isEmpty: Boolean = true,
    /** False until the lineup has been turned into shelves once, so "still working" is never shown as "nothing here". */
    val ready: Boolean = false,
)

object ChannelShelvesLogic {
    private const val PER_SHELF = 16

    fun build(
        lineup: Lineup,
        settings: UserSettings,
        prefs: LineupPrefs,
        history: List<Channel>,
        nowAndNext: (GuideRef, Long) -> Pair<Programme?, Programme?>,
        now: Long,
        timeZone: String,
    ): ChannelShelves {
        val ui = GuideLogic.build(lineup, settings, prefs, GuideFilter.All, GuideOptions(), "", nowAndNext, now, timeZone)
        if (ui.rows.isEmpty()) return ChannelShelves(isEmpty = true, ready = true)
        val byKey = ui.rows.associateBy { it.key }

        // Continue watching: the lineup channels the viewer last played, newest first (one tile per channel, not per stream).
        val recents = ArrayList<GuideRow>()
        for (channel in history) {
            val entry = lineup.entryFor(channel.key) ?: continue
            val row = byKey[entry.key] ?: GuideLogic.row(entry, settings, prefs, nowAndNext, now)
            if (recents.none { it.key == row.key }) recents += row
            if (recents.size >= 12) break
        }

        val shelves = ArrayList<ChannelShelf>()
        fun add(key: String, title: String, rows: List<GuideRow>) { if (rows.isNotEmpty()) shelves += ChannelShelf(key, title, rows.take(PER_SHELF)) }
        add("favorites", "Favorite Channels", ui.rows.filter { it.isFavorite })
        add("sports", "Sports On Now", ui.rows.filter { GuideLogic.belongs(GuideFilter.Sports, it, settings) && it.now != null })
        add("news", "News", ui.rows.filter { GuideLogic.belongs(GuideFilter.News, it, settings) })
        add("movies", "Movies", ui.rows.filter { GuideLogic.belongs(GuideFilter.Movies, it, settings) })
        add("canada", "Canada", ui.rows.filter { GuideLogic.belongs(GuideFilter.Canada, it, settings) && !it.entry.isLocalStation })
        add("usa", "USA", ui.rows.filter { GuideLogic.belongs(GuideFilter.Usa, it, settings) && !it.entry.isLocalStation })
        add("french", "French", ui.rows.filter { GuideLogic.belongs(GuideFilter.French, it, settings) })
        add("kids", "Kids", ui.rows.filter { GuideLogic.belongs(GuideFilter.Kids, it, settings) })
        return ChannelShelves(recent = recents, shelves = shelves, favorites = ui.rows.filter { it.isFavorite }, total = ui.rows.size, isEmpty = false, ready = true)
    }
}

/** Shared by Home and Live TV so the lineup is turned into shelves once, off the main thread, and refreshed as time passes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelShelvesViewModel(container: AppContainer) : ViewModel() {
    private val ticker = flow {
        while (true) { emit(System.currentTimeMillis() / 60_000L); delay(60_000L) }
    }

    val shelves: StateFlow<ChannelShelves> = combine(
        container.lineup.lineup,
        container.preferences.settings,
        container.lineup.prefs,
        container.watchHistory.history.map { entries -> entries.map { it.channel } },
        container.guide.state.map { it.revision }.distinctUntilChanged(),
    ) { lineup, settings, prefs, history, _ -> arrayOf(lineup, settings, prefs, history) }
        .combine(ticker) { inputs, _ -> inputs }
        .mapLatest { inputs ->
            @Suppress("UNCHECKED_CAST")
            ChannelShelvesLogic.build(
                lineup = inputs[0] as Lineup, settings = inputs[1] as UserSettings, prefs = inputs[2] as LineupPrefs, history = inputs[3] as List<Channel>,
                nowAndNext = { ref, now -> container.guide.nowAndNext(ref, now) }, now = System.currentTimeMillis(),
                timeZone = java.util.TimeZone.getDefault().id,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelShelves())
}
