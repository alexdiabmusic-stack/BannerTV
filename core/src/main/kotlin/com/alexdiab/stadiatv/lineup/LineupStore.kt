package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.data.PreferencesStore
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.live.GuideStore
import com.alexdiab.stadiatv.live.PlaylistStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The lineup the guide shows: every playlist's channels read once, then grouped, matched to the catalogue and given a
 * schedule. Rebuilt in the background whenever the playlists or the guide's directory change; the viewer's choices
 * (HD or not, home country) only change which stream of a channel plays, so they never rebuild it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LineupStore(
    private val playlists: PlaylistStore,
    private val guide: GuideStore,
    private val preferences: PreferencesStore,
    val builder: LineupBuilder,
    scope: CoroutineScope,
) {
    /** Every stream of every playlist, read once per playlist change. */
    val streams: StateFlow<List<ResolvedStream>> = playlists.state
        .map { it.revision }
        .distinctUntilChanged()
        .mapLatest { com.alexdiab.stadiatv.util.PerfLog.measure("lineup analyse (${playlists.state.value.channels.size} streams)") { builder.analyse(playlists.state.value.channels) } }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _lineup = MutableStateFlow(Lineup.EMPTY)
    val lineup: StateFlow<Lineup> = _lineup.asStateFlow()

    val prefs: StateFlow<LineupPrefs> = preferences.settings
        .map { prefsOf(it) }
        .distinctUntilChanged { a, b -> a.preferHd == b.preferHd && a.preferUhd == b.preferUhd && a.homeCountry == b.homeCountry }
        .stateIn(scope, SharingStarted.Eagerly, prefsOf(preferences.current))

    /** The countries whose channels (and guide data) the lineup holds. */
    val countries: StateFlow<Set<String>> = preferences.settings
        .map { countriesOf(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, countriesOf(preferences.current))

    /**
     * The channels the viewer is flipping through (the guide's current list), so channel up/down in the player follows
     * what they were browsing rather than the provider's twenty thousand streams.
     */
    @Volatile var zapChannels: List<com.alexdiab.stadiatv.live.Channel> = emptyList()

    init {
        scope.launchLineup()
    }

    private fun CoroutineScope.launchLineup() {
        val job = combine(streams, guide.directory) { streams, directory -> streams to directory }
            .mapLatest { (streams, directory) -> com.alexdiab.stadiatv.util.PerfLog.measure("lineup build (directory=${directory != null})") { builder.build(streams, directory) } }
            .flowOn(Dispatchers.Default)
        launch {
            job.collect { lineup ->
                _lineup.value = lineup
                guide.use(lineup)
            }
        }
    }

    companion object {
        fun prefsOf(settings: UserSettings) = LineupPrefs(
            preferHd = settings.preferHdStreams,
            preferUhd = settings.preferUhdStreams,
            homeCountry = homeCountryOf(settings),
        )

        /** The viewer's country: their choice, else the device's region if it is Canada or the US, else Canada. */
        fun homeCountryOf(settings: UserSettings): String =
            settings.homeCountry.ifEmpty { Locale.getDefault().country.takeIf { it == "US" || it == "CA" } ?: "CA" }

        fun countriesOf(settings: UserSettings): Set<String> = settings.guideCountries + homeCountryOf(settings)
    }
}
