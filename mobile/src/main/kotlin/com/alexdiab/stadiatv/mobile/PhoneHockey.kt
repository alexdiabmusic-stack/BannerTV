package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import kotlinx.coroutines.launch

@Composable
fun HockeyPlays(match: Match, data: GameCentre, model: MobileViewModel) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable(match.key) { mutableStateOf(HockeyPlayFilter.All) }
    var window by remember(match.key) { mutableStateOf(HockeyTimelineWindow()) }
    val atEdge by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(data.plays, atEdge, filter) { window = window.update(data.plays, atEdge) }
    val filtered = window.displayed.filter(filter::includes)
    Column {
        ChoiceRow(HockeyPlayFilter.entries.map { it.label }, filter.label) { selected ->
            filter = HockeyPlayFilter.entries.first { it.label == selected }; window = window.update(data.plays, true)
        }
        Text(data.playsSource ?: "NHL play-by-play", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filtered.isEmpty()) item { EmptyState("No matching plays", if (data.plays.isEmpty()) "Play-by-play will appear when supplied by NHL." else "Choose a different event filter.") }
                filtered.groupBy { it.period }.forEach { (period, events) ->
                    item("period-$period") { SectionTitle(period) }
                    items(events, key = { it.id }) { HockeyEventRow(it, match, model) }
                }
            }
            if (window.deferredIDs.isNotEmpty()) FilledTonalButton(onClick = {
                window = window.update(data.plays, true); scope.launch { list.scrollToItem(0) }
            }, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text("${window.deferredIDs.size} new plays · Jump to live") }
        }
    }
}

@Composable
fun HockeyEventRow(play: Play, match: Match, model: MobileViewModel) {
    val event = play.hockeyEvent ?: return
    val primary = event.primary
    val context = LocalContext.current
    var expanded by rememberSaveable(play.id) { mutableStateOf(false) }
    val team = listOf(match.away, match.home).firstOrNull { it.teamId != null && it.teamId == play.teamId }
    val prominent = event.kind in setOf("goal", "penalty")
    val accent = if (event.kind == "goal") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(16.dp), color = if (prominent) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(if (prominent) 16.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(play.type.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent)
                team?.let { if (event.kind == "goal") Logo(it.logoUrl, it.abbreviation, 22.dp); Text(it.abbreviation, style = MaterialTheme.typography.labelMedium) }
                Spacer(Modifier.weight(1f))
                Text(event.shootoutRound?.let { "Round $it" } ?: play.clock, style = MaterialTheme.typography.labelMedium)
                if (event.periodLabel != "Shootout" && play.awayScore != null && play.homeScore != null && event.kind == "goal") {
                    Text("${play.awayScore}–${play.homeScore}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = "${match.away.displayName} ${play.awayScore}, ${match.home.displayName} ${play.homeScore}" })
                }
            }
            Text(event.periodLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (event.kind == "goal" && primary?.headshot != null) Logo(primary.headshot, primary.name, 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(event.title, style = if (prominent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
                    event.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            event.seasonGoals?.let { Text("Season goal $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val totals = event.assists.mapNotNull { player -> event.assistSeasonTotals[player.id]?.let { "${player.name} ($it)" } }
            if (totals.isNotEmpty()) Text("Season assists · ${totals.joinToString(" · ")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            play.videoUrl?.let { url -> TextButton(onClick = { openBrowser(context, url) }, Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Watch goal")
            } }
            if (expanded) {
                (listOfNotNull(event.primary) + event.assists).distinctBy { it.id }.filter { it.name != "Unknown player" }.forEach { player ->
                    if (player.id.matches(Regex("[0-9]+"))) TextButton(onClick = { model.open(PhonePage.PlayerDetail(match.league, Athlete("nhl:${player.id}", player.name, headshotUrl = player.headshot))) }, Modifier.heightIn(min = 48.dp)) { Text("Player details · ${player.name}") }
                    TextButton(onClick = { openBrowser(context, player.profileUrl) }, Modifier.heightIn(min = 48.dp)) { Text("View ${player.name} on NHL") }
                }
                event.location?.let { HockeyEventLocation(it) }
            }
            if (event.location != null || event.primary != null || event.assists.isNotEmpty()) TextButton(onClick = { expanded = !expanded }, Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide details" else "Event details") }
        }
    }
}

@Composable
private fun HockeyEventLocation(location: HockeyLocation) {
    val ice = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outline
    val red = BannerLive
    val blue = MaterialTheme.colorScheme.primary
    val puck = MaterialTheme.colorScheme.onSurface
    val orientation = if (location.normalizedOrientation) "Home team defends left" else "Feed orientation"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(200f / 85).semantics { contentDescription = "Rink location, x ${location.x}, y ${location.y}. $orientation" }) {
            val stroke = 1.dp.toPx()
            drawRoundRect(ice, cornerRadius = CornerRadius(size.height * .3f))
            drawRoundRect(outline, cornerRadius = CornerRadius(size.height * .3f), style = Stroke(stroke))
            drawLine(red, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke)
            for (x in listOf(.375f, .625f)) drawLine(blue, Offset(size.width * x, 0f), Offset(size.width * x, size.height), stroke)
            drawCircle(blue, size.height * .175f, center, style = Stroke(stroke))
            drawCircle(puck, 5.dp.toPx(), Offset((location.x + 100) / 200 * size.width, (42.5f - location.y) / 85 * size.height))
        }
        Text(orientation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
