package com.alexdiab.stadiatv.ui.livetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.ui.components.CenteredSpinner
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.OverlayContentTop
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.components.TvTextField
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

/** What sits on top of the guide: nothing, the filter panel, or the actions for one channel. */
private sealed interface Sheet {
    data object Filters : Sheet
    data class Actions(val row: GuideRow) : Sheet
}

/**
 * The TV guide. By default it lists what is on now, channel by channel, for the viewer's own area; chips narrow it
 * (favourites, sport, country, French, news ...), search finds any channel, and the timeline and live-games views
 * show the same lineup another way.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen(asTab: Boolean = false) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val viewModel: GuideViewModel = viewModel(key = "guide", factory = viewModelFactory { initializer { GuideViewModel(container) } })
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val guideState by container.guide.state.collectAsStateWithLifecycle()
    val playlists by container.playlists.state.collectAsStateWithLifecycle()

    var searching by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var focused by remember { mutableStateOf<GuideRow?>(null) }
    var focusedProgramme by remember { mutableStateOf<Programme?>(null) }
    val listFocus = remember { FocusRequester() }
    BackHandler(enabled = sheet != null) { sheet = null }
    // When a panel closes its focused control disappears with it; put focus back on the channel list.
    val filterFocus = remember { FocusRequester() }
    var lastSheet by remember { mutableStateOf<Sheet?>(null) }
    LaunchedEffect(sheet) {
        if (sheet == null) {
            val from = lastSheet
            if (from != null) {
                awaitFrame()
                // Back to the control that opened the panel: the filter button, or the channel it was opened on.
                runCatching { if (from is Sheet.Filters) filterFocus.requestFocus() else listFocus.requestFocus() }
            }
        }
        lastSheet = sheet
    }

    val trailing: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            GuideView.entries.forEach { mode ->
                FilterChip(mode.label, view == mode, { viewModel.setView(mode) })
            }
            IconPill(Icons.Rounded.Search, "Search", selected = searching || query.isNotEmpty()) { searching = !searching }
            IconPill(Icons.Rounded.Tune, "Filters", selected = options.allAreas || options.showHidden || options.managing, modifier = Modifier.focusRequester(filterFocus)) { sheet = Sheet.Filters }
        }
    }
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (searching || query.isNotEmpty()) {
                    TvTextField(
                        value = query, onValueChange = { viewModel.query.value = it }, label = "Search channels",
                        placeholder = "Type a channel name", modifier = Modifier.padding(horizontal = Spacing.ScreenH),
                    )
                }
                if (view != GuideView.LiveGames) {
                    ChipRow {
                        GuideFilter.entries.forEach { chip ->
                            val count = ui.counts[chip] ?: 0
                            item(chip.name) { FilterChip(chip.label, filter == chip, { viewModel.select(chip) }, count = if (chip == GuideFilter.All || chip == GuideFilter.Favorites && count == 0) null else count, countColor = Palette.TextTertiary) }
                        }
                    }
                    if (filter == GuideFilter.Sports) {
                        ChipRow {
                            SportsFilter.entries.forEach { sport -> item(sport.name) { FilterChip(sport.label, options.sports == sport, { viewModel.selectSports(sport) }) } }
                        }
                    }
                }
                when {
                    view == GuideView.LiveGames -> LiveGamesView(viewModel, ui, Modifier.weight(1f))
                    playlists.playlists.isEmpty() -> EmptyState(
                        Icons.Rounded.CalendarMonth, "No channels yet",
                        subtitle = "Add a playlist or stream login in Live TV and your channels and their schedules appear here.",
                        modifier = Modifier.weight(1f),
                    )
                    ui.isEmpty && (guideState.isLoading || playlists.loading.isNotEmpty()) -> CenteredSpinner(Modifier.weight(1f), guideState.progress ?: "Loading your channels…")
                    ui.rows.isEmpty() -> EmptyState(
                        Icons.Rounded.CalendarMonth,
                        if (filter == GuideFilter.Favorites) "No favorites yet" else "No channels in this view",
                        subtitle = if (filter == GuideFilter.Favorites) "Hold OK on a channel and choose Add to favorites." else "Try another filter, or search for a channel by name.",
                        modifier = Modifier.weight(1f),
                    )
                    view == GuideView.Grid -> GuideTimeline(
                        rows = ui.rows, guideRevision = guideState.revision,
                        selectedRow = focused?.takeIf { row -> ui.rows.any { it.key == row.key } },
                        selectedProgramme = focusedProgramme.takeIf { focused?.let { row -> ui.rows.any { it.key == row.key } } == true },
                        onFocus = { row, programme -> focused = row; focusedProgramme = programme },
                        onPlay = { navigator.push(Route.Player(it.pick.channel)) },
                        onActions = { sheet = Sheet.Actions(it) },
                        modifier = Modifier.weight(1f),
                    )
                    else -> NowList(
                        ui = ui, filter = filter, managing = options.managing, listFocus = listFocus,
                        loadingText = if (guideState.isLoading) (guideState.progress ?: "Loading TV listings…") else null,
                        onFocus = { focused = it },
                        onPlay = { navigator.push(Route.Player(it.pick.channel)) },
                        onActions = { sheet = Sheet.Actions(it) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (view == GuideView.OnNow) DetailsBar(focused?.takeIf { row -> ui.rows.any { it.key == row.key } })
            }
            when (val current = sheet) {
                Sheet.Filters -> FilterPanel(viewModel, ui, onClose = { sheet = null })
                is Sheet.Actions -> ActionsDialog(
                    row = current.row, viewModel = viewModel, managing = options.managing,
                    onPlay = { sheet = null; navigator.push(Route.Player(current.row.pick.channel)) },
                    onClose = { sheet = null },
                )
                null -> Unit
            }
        }
    }
    if (asTab) {
        // As a destination of its own the guide sits under the tab bar like every other tab.
        Column(Modifier.fillMaxSize().padding(top = TopBarInset - 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH), verticalAlignment = Alignment.CenterVertically) {
                Text("TV Guide", style = Type.Title2, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
                trailing()
            }
            Box(Modifier.weight(1f)) { body() }
        }
    } else {
        OverlayScaffold(title = "TV Guide", subtitle = "Live TV", trailing = trailing) { body() }
    }
}

@Composable
private fun IconPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusPill(onClick = onClick, selected = selected, modifier = modifier.width(64.dp)) {
        Icon(icon, label, tint = if (selected) Color.White else Palette.TextPrimary, modifier = Modifier.align(Alignment.Center).size(30.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NowList(
    ui: GuideUi,
    filter: GuideFilter,
    managing: Boolean,
    listFocus: FocusRequester,
    loadingText: String?,
    onFocus: (GuideRow) -> Unit,
    onPlay: (GuideRow) -> Unit,
    onActions: (GuideRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    // Open on the first channel rather than on the filter chips, so OK plays something straight away.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(ui.rows.isNotEmpty()) {
        if (ui.rows.isNotEmpty() && !landed) { landed = true; awaitFrame(); runCatching { listFocus.requestFocus() } }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH).padding(top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                when (filter) {
                    GuideFilter.All -> "All channels"
                    GuideFilter.Favorites -> "Favorites"
                    else -> filter.label
                },
                style = Type.Title3, color = Palette.TextPrimary,
            )
            Text("${ui.rows.size}", style = Type.Callout, color = Palette.TextTertiary)
            if (ui.homeMarket.isNotEmpty()) Text("· Local area: ${GuideLogic.titleCase(ui.homeMarket)}", style = Type.Callout, color = Palette.TextTertiary)
            if (managing) Text("· Managing: hold OK on a channel to reorder or hide it", style = Type.Callout, color = Palette.AccessibleAccent)
            if (loadingText != null) {
                com.alexdiab.stadiatv.ui.components.Spinner(size = 22.dp)
                Text("$loadingText (the first load takes a minute)", style = Type.Callout, color = Palette.TextTertiary, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop - 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().focusRequester(listFocus).focusRestorer(),
            ) {
                items(ui.rows, key = { it.key }) { row ->
                    GuideRowCard(row, onFocus = { onFocus(row) }, onClick = { onPlay(row) }, onLongClick = { onActions(row) })
                }
            }
        }
    }
}

@Composable
private fun DetailsBar(row: GuideRow?) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(Palette.Surface.copy(alpha = 0.96f))
            .padding(horizontal = Spacing.ScreenH, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (row == null) {
            Text("Select a channel to see what's on", style = Type.Body, color = Palette.TextTertiary)
            return@Column
        }
        val programme = row.now
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(programme?.title ?: row.entry.name, style = Type.Title3, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            val times = programme?.let { "${com.alexdiab.stadiatv.util.Dates.time(it.startMillis)} – ${com.alexdiab.stadiatv.util.Dates.time(it.endMillis)}" }
            Text(listOfNotNull(row.entry.name, times, row.pick.stream.quality.label).joinToString(" · "), style = Type.Callout, color = Palette.AccessibleAccent, maxLines = 1)
        }
        val detail = programme?.let { listOfNotNull(it.subtitle, it.description).joinToString(" — ") }.orEmpty().replace(Spaces, " ").trim()
        if (detail.isNotBlank()) Text(detail, style = Type.Body, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else if (programme == null) Text(
            if (row.pick.guideId == null) "No schedule is available for this channel." else "Nothing is listed on right now.",
            style = Type.Body, color = Palette.TextTertiary,
        )
        val note = guideNote(row)
        val next = row.next
        val line = listOfNotNull(next?.let { "Next: ${it.title} · ${com.alexdiab.stadiatv.util.Dates.time(it.startMillis)}" }, note).joinToString("   ·   ")
        if (line.isNotEmpty()) Text(line, style = Type.Caption, color = Palette.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Says where a borrowed schedule came from, so a Network or Twin guide is never mistaken for the channel's own. */
internal fun guideNote(row: GuideRow): String? = when (row.pick.guideSource) {
    com.alexdiab.stadiatv.lineup.GuideSource.Network -> "Network schedule from another city; local shows may differ"
    com.alexdiab.stadiatv.lineup.GuideSource.Shifted -> "East-coast schedule, three hours later"
    com.alexdiab.stadiatv.lineup.GuideSource.Twin -> "Playing ${row.pick.channel.name.trim()} because it has a schedule"
    com.alexdiab.stadiatv.lineup.GuideSource.Alternate -> "Schedule from another feed of this channel"
    else -> null
}

private suspend fun awaitFrame() = kotlinx.coroutines.android.awaitFrame()

private val Spaces = Regex("\\s+")
