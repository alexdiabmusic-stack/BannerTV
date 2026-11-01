package com.alexdiab.stadiatv.ui.schedule

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.sports.SportsRepository
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Immutable
data class ScheduleState(
    val league: League? = null,
    /** Days from today: -1 yesterday ... +5. */
    val dayOffset: Int = 0,
    val matches: List<Match> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val live: List<Match> get() = matches.filter { it.state == GameState.LIVE }
    val upcoming: List<Match> get() = matches.filter { it.state == GameState.PRE }
    val finished: List<Match> get() = matches.filter { it.state == GameState.FINAL }
}

/** Loads one league's games for one day, and keeps today's scores current. */
class ScheduleViewModel(private val sports: SportsRepository) : ViewModel() {
    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    fun ensureLeague(leagues: List<League>) {
        val current = _state.value.league
        if (current == null || current !in leagues) leagues.firstOrNull()?.let { select(it, _state.value.dayOffset) }
    }

    fun select(league: League, dayOffset: Int = _state.value.dayOffset) {
        _state.update { it.copy(league = league, dayOffset = dayOffset, isLoading = true, error = null) }
        load()
    }

    fun selectDay(offset: Int) {
        val league = _state.value.league ?: return
        select(league, offset)
    }

    private fun load(silent: Boolean = false) {
        val snapshot = _state.value
        val league = snapshot.league ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val day = Dates.startOfDay(Dates.addDays(System.currentTimeMillis(), snapshot.dayOffset))
            try {
                val matches = sports.schedule(league, day, 1).sortedBy { it.startMillis }
                _state.update { if (it.league == league && it.dayOffset == snapshot.dayOffset) it.copy(matches = matches, isLoading = false, error = null) else it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (!silent) _state.update { it.copy(matches = emptyList(), isLoading = false, error = error.message ?: "Couldn't load the schedule.") }
                else _state.update { it.copy(isLoading = false) }
            }
        }
    }

    /** Called while the screen is visible. Refreshes every 30s on today's schedule. */
    fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (_state.value.dayOffset == 0) load(silent = true)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun retry() = load()

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }
}
