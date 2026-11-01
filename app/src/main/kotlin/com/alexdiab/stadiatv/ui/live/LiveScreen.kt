package com.alexdiab.stadiatv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.LiveBadge
import com.alexdiab.stadiatv.ui.components.MatchCard
import com.alexdiab.stadiatv.ui.components.MatchCardHeight
import com.alexdiab.stadiatv.ui.components.MatchCardWidth
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.Shelf
import com.alexdiab.stadiatv.ui.components.SkeletonShelf
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.StaleNote
import androidx.compose.material.icons.rounded.WifiOff
import kotlinx.coroutines.launch
import com.alexdiab.stadiatv.ui.components.icon
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

/** Every game in progress across all sports, with a sport filter down the side. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val feed by container.matchFeed.sections.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var selectedSport by rememberSaveable { mutableStateOf<String?>(null) }
    val open: (Match) -> Unit = { navigator.push(Route.MatchDetail(it)) }

    val sport = selectedSport?.let { name -> SportGroup.entries.firstOrNull { it.name == name } }
    val shown = remember(feed.liveNow, sport) { if (sport == null) feed.liveNow else feed.liveNow.filter { it.league.group == sport } }
    val sportCounts = remember(feed.liveNow) {
        feed.liveNow.groupingBy { it.league.group }.eachCount().entries.sortedBy { it.key.ordinal }
    }

    Row(
        Modifier
            .fillMaxSize()
            .padding(top = LocalTopInset.current - 12.dp, start = Spacing.ScreenH - 8.dp),
    ) {
        Sidebar(
            selected = sport,
            allCount = feed.liveNow.size,
            sports = sportCounts.map { it.key to it.value },
            startingSoon = feed.startingSoon.size,
            onSelect = { selectedSport = it?.name },
            modifier = Modifier.width(300.dp).fillMaxHeight().padding(bottom = 40.dp),
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                feed.isLoading && feed.liveNow.isEmpty() && feed.startingSoon.isEmpty() ->
                    Column(Modifier.padding(start = 24.dp, top = 12.dp)) {
                        Text("Scanning all sports…", style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 18.dp))
                        SkeletonShelf("Live", cardWidth = MatchCardWidth, cardHeight = MatchCardHeight, count = 3)
                    }
                // "Couldn't find out" is not "nothing is on": say which one it is.
                feed.error != null && feed.liveNow.isEmpty() && feed.startingSoon.isEmpty() -> EmptyState(
                    Icons.Rounded.WifiOff,
                    "Couldn't load live games",
                    subtitle = feed.error,
                    modifier = Modifier.align(Alignment.Center),
                    action = {
                        FocusPill(onClick = { scope.launch { container.matchFeed.load(force = true) } }) {
                            Text("Try Again", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 30.dp).align(Alignment.Center))
                        }
                    },
                )
                shown.isEmpty() && feed.startingSoon.isEmpty() -> EmptyState(
                    Icons.Rounded.SportsScore,
                    "Nothing live right now",
                    subtitle = "Check back soon. New games will appear here automatically.",
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> PagePivot(0.25f) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(MatchCardWidth),
                        contentPadding = PaddingValues(start = 24.dp, end = Spacing.ScreenH, top = 12.dp, bottom = Spacing.ScreenBottom + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                    ) {
                        if (feed.refreshFailed) item(span = { GridItemSpan(maxLineSpan) }, key = "stale") {
                            StaleNote(feed.updatedAt, retry = { scope.launch { container.matchFeed.load(force = true) } }, inset = 0.dp)
                        }
                        if (shown.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    LiveBadge()
                                    Text(
                                        if (shown.size == 1) "1 game live" else "${shown.size} games live",
                                        style = Type.Title3,
                                        color = Palette.TextPrimary,
                                    )
                                }
                            }
                            items(shown, key = { it.key }) { MatchCard(it, onClick = { open(it) }, modifier = Modifier.padding(vertical = 4.dp)) }
                        }
                        if (feed.startingSoon.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "soon") {
                                Column(Modifier.padding(top = 8.dp)) {
                                    Text("Starting Soon", style = Type.Title3, color = Palette.Starting, modifier = Modifier.padding(bottom = 6.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(vertical = 24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                                        modifier = Modifier.focusRestorer(),
                                    ) {
                                        items(feed.startingSoon.take(10), key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    selected: SportGroup?,
    allCount: Int,
    sports: List<Pair<SportGroup, Int>>,
    startingSoon: Int,
    onSelect: (SportGroup?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(Palette.Surface, RoundedCornerShape(Shapes.Xl))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Xl))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("SPORTS", style = Type.Overline, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
        SidebarRow(Icons.Rounded.SportsScore, "All Sports", allCount, selected == null) { onSelect(null) }
        sports.forEach { (group, count) -> SidebarRow(group.icon, group.label, count, selected == group) { onSelect(group) } }
        Spacer(Modifier.weight(1f))
        if (startingSoon > 0) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.AccessTime, null, tint = Palette.Starting, modifier = Modifier.size(22.dp))
                Column {
                    Text("STARTING SOON", style = Type.Overline, color = Palette.TextSecondary)
                    Text("$startingSoon games", style = Type.Caption, color = Palette.TextTertiary)
                }
            }
        }
    }
}

@Composable
private fun SidebarRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Int, selected: Boolean, onFocused: () -> Unit) {
    FocusCard(
        onClick = onFocused,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(Shapes.Md),
        focusedScale = 1.03f,
        background = if (selected) Brush.horizontalGradient(listOf(Palette.Accent.copy(alpha = 0.9f), Palette.ActionFill.copy(alpha = 0.9f)))
        else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)),
        restingBorder = Color.Transparent,
        onFocusChange = { if (it) onFocused() },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = if (selected) Color.White else Palette.AccessibleAccent, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = if (selected) Type.BodyStrong else Type.Body, color = if (selected) Color.White else Palette.TextPrimary, maxLines = 1)
                if (count > 0) Text("$count live", style = Type.Caption, color = if (selected) Color.White.copy(alpha = 0.75f) else Palette.Live)
            }
        }
    }
}
