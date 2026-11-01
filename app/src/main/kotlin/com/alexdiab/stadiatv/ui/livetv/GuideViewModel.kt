package com.alexdiab.stadiatv.ui.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdiab.stadiatv.AppContainer
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.lineup.Lineup
import com.alexdiab.stadiatv.lineup.LineupPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The timeline is the guide; "On Now" is its simpler cousin and "Live Games" links sport to the channel carrying it. */
enum class GuideView(val label: String) { Grid("Timeline"), OnNow("On Now"), LiveGames("Live Games") }

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GuideViewModel(private val container: AppContainer) : ViewModel() {
    private val preferences = container.preferences

    val view = MutableStateFlow(GuideView.Grid)
    val filter = MutableStateFlow(GuideFilter.All)
    val options = MutableStateFlow(GuideOptions())
    val query = MutableStateFlow("")

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis() / 30_000L)
            delay(30_000L)
        }
    }

    private class Inputs(val lineup: Lineup, val settings: UserSettings, val prefs: LineupPrefs, val guideRevision: Int)

    private val inputs = combine(
        container.lineup.lineup,
        preferences.settings,
        container.lineup.prefs,
        container.guide.state.map { it.revision }.distinctUntilChanged(),
    ) { lineup, settings, prefs, revision -> Inputs(lineup, settings, prefs, revision) }

    private class Choices(val filter: GuideFilter, val options: GuideOptions, val query: String)

    private val choices = combine(filter, options, query.debounce(200).distinctUntilChanged()) { f, o, q -> Choices(f, o, q) }

    val ui: StateFlow<GuideUi> = combine(inputs, choices, ticker) { inputs, choices, _ -> inputs to choices }
        .mapLatest { (inputs, choices) ->
            GuideLogic.build(
                lineup = inputs.lineup, settings = inputs.settings, prefs = inputs.prefs,
                filter = choices.filter, options = choices.options, query = choices.query,
                nowAndNext = { ref, now -> container.guide.nowAndNext(ref, now) },
                now = System.currentTimeMillis(),
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, GuideUi())

    init {
        viewModelScope.launch { ui.collect { state -> container.lineup.zapChannels = state.rows.map { it.pick.channel } } }
    }

    fun select(filter: GuideFilter) {
        this.filter.value = filter
        if (filter != GuideFilter.Sports) options.update { it.copy(sports = SportsFilter.Any) }
    }

    fun selectSports(sports: SportsFilter) = options.update { it.copy(sports = sports) }
    fun setView(view: GuideView) { this.view.value = view }
    fun toggleManaging() = options.update { it.copy(managing = !it.managing) }
    fun toggleAllAreas() = options.update { it.copy(allAreas = !it.allAreas) }
    fun toggleShowHidden() = options.update { it.copy(showHidden = !it.showHidden) }

    fun toggleFavorite(key: String) = preferences.toggleFavoriteEntry(key)
    fun toggleHidden(key: String) = preferences.toggleHiddenEntry(key)
    fun setHomeMarket(market: String) = preferences.update { it.copy(homeMarket = market) }
    fun setHomeCountry(country: String) = preferences.update { it.copy(homeCountry = country) }

    fun toggleCountry(country: String) = preferences.update {
        it.copy(guideCountries = if (country in it.guideCountries) it.guideCountries - country else it.guideCountries + country)
    }

    /** Moves a channel up or down within what is listed now, and remembers the order. */
    fun move(key: String, delta: Int) {
        val keys = ui.value.rows.map { it.key }.toMutableList()
        val from = keys.indexOf(key)
        val to = (from + delta).coerceIn(0, keys.lastIndex)
        if (from < 0 || from == to) return
        keys.add(to, keys.removeAt(from))
        preferences.update { settings -> settings.copy(entryOrder = keys + (settings.entryOrder - keys.toSet())) }
    }

    fun resetOrder() = preferences.update { it.copy(entryOrder = emptyList()) }
}
