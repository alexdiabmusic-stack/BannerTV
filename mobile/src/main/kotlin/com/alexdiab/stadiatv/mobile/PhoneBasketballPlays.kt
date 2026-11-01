@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.NativeBasketballDuration
import kotlinx.coroutines.launch

/** BasketballPlayByPlayView/PlayRow: pinned quarters, stable reading window and source-priority rows. */
@Composable
fun BasketballPlays(match: Match, data: GameCentre) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var window by remember(match.key) { mutableStateOf(PlayTimelineWindow()) }
    var selectedPeriod by rememberSaveable(match.key) { mutableIntStateOf(0) }
    var scoring by rememberSaveable(match.key) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    val atEdge by remember(threshold) { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < threshold } }
    LaunchedEffect(data.plays, atEdge) { window = window.update(data.plays, atEdge) }
    val regulation = data.basketball?.regulationPeriods ?: 4
    val periods = data.plays.map { it.periodNumber }.filter { it > 0 }.distinct().sorted()
    val period = selectedPeriod.takeIf { it in periods } ?: 0
    fun includes(play: Play) = (period == 0 || play.periodNumber == period) && (!scoring || play.isScoring)
    val displayed = window.displayed.filter(::includes)
    val pending = data.plays.count { it.id in window.deferredIDs && includes(it) }
    Column {
        Text(listOfNotNull(data.playsSource, data.basketball?.surfaceUpdatedAt?.get("plays")?.let(::snapshotTime)).joinToString(" · "),
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            val labels = listOf("All periods") + periods.map { NativeBasketballDuration.period(it, regulation) }
            Box(Modifier.weight(1f)) { ChoiceRow(labels, if (period == 0) labels.first() else NativeBasketballDuration.period(period, regulation)) { label ->
                selectedPeriod = periods.firstOrNull { NativeBasketballDuration.period(it, regulation) == label } ?: 0
                window = window.update(data.plays, true); scope.launch { list.scrollToItem(0) }
            } }
            FilterChip(scoring, { scoring = !scoring; window = window.update(data.plays, true); scope.launch { list.scrollToItem(0) } }, label = { Text("Scoring") })
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (displayed.isEmpty()) item("empty") { EmptyState("No matching plays", if (data.plays.isEmpty()) "Play-by-play appears when the native feed supplies it." else "Choose another period or play filter.") }
                displayed.groupBy { it.periodNumber }.forEach { (number, events) ->
                    stickyHeader(key = "period:$number") {
                        Text(NativeBasketballDuration.period(number, regulation), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp),
                            style = MaterialTheme.typography.titleSmall)
                    }
                    items(events, key = { it.id }) { BasketballPlayRow(it) }
                }
            }
            if (!atEdge) FilledTonalButton(onClick = { window = window.update(data.plays, true); scope.launch { list.scrollToItem(0) } }, Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                Text(if (pending > 0) "$pending new plays · Jump to latest" else if (match.state == GameState.FINAL) "Jump to latest" else "Jump to live")
            }
        }
    }
}

@Composable
private fun BasketballPlayRow(play: Play) {
    val event = play.basketballEvent
    val priority = event?.priority ?: BasketballPlayPriority.Compact
    val prominent = priority == BasketballPlayPriority.High
    Surface(shape = MaterialTheme.shapes.medium, color = if (prominent) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent) {
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 12.dp,
            vertical = when (priority) { BasketballPlayPriority.High -> 12.dp; BasketballPlayPriority.Medium -> 8.dp; else -> 4.dp }), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(event?.title ?: play.text, Modifier.weight(1f), style = if (prominent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (priority != BasketballPlayPriority.Compact) FontWeight.SemiBold else FontWeight.Normal)
                if (play.clock.isNotBlank()) Text(play.clock, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            event?.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
