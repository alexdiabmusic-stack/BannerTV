package com.alexdiab.stadiatv.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.MatchCard
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.Shelf
import com.alexdiab.stadiatv.ui.components.SkeletonShelf
import com.alexdiab.stadiatv.ui.components.icon
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

@Composable
fun ScheduleScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val viewModel: ScheduleViewModel = viewModel(
        key = "schedule",
        factory = viewModelFactory { initializer { ScheduleViewModel(container.sports) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val leagues = settings.followedLeagues
    val open: (Match) -> Unit = { navigator.push(Route.MatchDetail(it)) }

    LaunchedEffect(leagues) { viewModel.ensureLeague(leagues) }
    DisposableEffect(Unit) {
        viewModel.startAutoRefresh()
        onDispose { viewModel.stopAutoRefresh() }
    }

    val days = remember { (-1..5).toList() }
    PagePivot(0.3f) {
        LazyColumn(
            contentPadding = PaddingValues(top = LocalTopInset.current - 20.dp, bottom = Spacing.ScreenBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "leagues") {
                ChipRow {
                    items(leagues, key = { it.path }) { league ->
                        FilterChip(league.shortName, state.league == league, { viewModel.select(league) }, icon = league.group.icon)
                    }
                }
            }
            item(key = "days") {
                ChipRow {
                    items(days, key = { it }) { offset ->
                        val millis = Dates.addDays(System.currentTimeMillis(), offset)
                        val label = when (offset) {
                            0 -> "Today"
                            1 -> "Tomorrow"
                            -1 -> "Yesterday"
                            else -> Dates.format("EEE, MMM d", millis)
                        }
                        FilterChip(label, state.dayOffset == offset, { viewModel.selectDay(offset) })
                    }
                }
            }
            when {
                state.isLoading && state.matches.isEmpty() -> item(key = "loading") { SkeletonShelf("Games") }
                state.error != null && state.matches.isEmpty() -> item(key = "error") {
                    EmptyState(
                        Icons.Rounded.WifiOff,
                        "Couldn't load the schedule",
                        subtitle = state.error,
                        action = {
                            FocusPill(onClick = viewModel::retry) {
                                androidx.compose.foundation.layout.Row(
                                    Modifier.padding(horizontal = 28.dp).align(Alignment.Center),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, tint = Palette.TextPrimary)
                                    Text("Try Again", style = Type.BodyStrong, color = Palette.TextPrimary)
                                }
                            }
                        },
                    )
                }
                state.matches.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Rounded.CalendarMonth,
                        if (state.dayOffset == 0) "No games today" else "No games on this day",
                        subtitle = "Try another day or follow different leagues.",
                    )
                }
                else -> {
                    val live = state.live
                    val upcoming = state.upcoming
                    val finished = state.finished
                    if (live.isNotEmpty()) item(key = "live") {
                        Shelf("Live Now", icon = Icons.Rounded.Sensors, tint = Palette.Live) {
                            items(live, key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                        }
                    }
                    if (upcoming.isNotEmpty()) item(key = "upcoming") {
                        Shelf("Upcoming", icon = Icons.Rounded.AccessTime, tint = Palette.Upcoming) {
                            items(upcoming, key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                        }
                    }
                    if (finished.isNotEmpty()) item(key = "results") {
                        Shelf("Results", icon = Icons.Rounded.Flag, tint = Palette.TextSecondary) {
                            items(finished, key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                        }
                    }
                }
            }
        }
    }
}
