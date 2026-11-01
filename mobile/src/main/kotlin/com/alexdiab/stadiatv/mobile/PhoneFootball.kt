package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.NativeFootballPlays
import kotlinx.coroutines.launch

@Composable
fun FootballPlays(match: Match, data: GameCentre) {
    var filter by rememberSaveable(match.key) { mutableStateOf(FootballPlayFilter.All) }
    var expanded by rememberSaveable(match.key) { mutableStateOf(emptyList<String>()) }
    var window by remember(match.key) { mutableStateOf(PlayTimelineWindow()) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atEdge by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 } }
    LaunchedEffect(data.plays, atEdge) { window = window.update(data.plays, atEdge) }
    LaunchedEffect(data.football?.currentDriveId) { data.football?.currentDriveId?.let { expanded = (expanded + it).distinct() } }
    val filtered = window.displayed.filter(filter::includes)
    val sequences = data.drives.mapNotNull { it.footballDrive?.sequence }.toSet()
    Column {
        ChoiceRow(FootballPlayFilter.entries.map { it.label }, filter.label) { selected ->
            filter = FootballPlayFilter.entries.first { it.label == selected }; window = window.update(data.plays, true)
            if (filter != FootballPlayFilter.All) expanded = (expanded + data.drives.filter { drive -> data.plays.any { it.footballEvent?.driveSequence == drive.footballDrive?.sequence && filter.includes(it) } }.map { it.id }).distinct()
        }
        Text(data.playsSource ?: "NFL Shield play-by-play", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (filtered.isEmpty()) item { EmptyState("No matching plays", if (data.plays.isEmpty()) "Plays will appear when supplied by NFL." else "Choose a different play filter.") }
                data.drives.asReversed().forEach { drive ->
                    val sequence = drive.footballDrive?.sequence
                    val plays = filtered.filter { sequence != null && it.footballEvent?.driveSequence == sequence }
                    if (plays.isNotEmpty()) {
                        item(drive.id) { FootballDriveCard(drive, drive.id in expanded) { expanded = if (drive.id in expanded) expanded - drive.id else expanded + drive.id } }
                        if (drive.id in expanded) items(plays, key = { it.id }) { FootballPlayRow(it) }
                    }
                }
                val unassigned = filtered.filter { it.footballEvent?.driveSequence !in sequences }
                if (unassigned.isNotEmpty()) {
                    item("unassigned") { SectionTitle("Other plays") }
                    items(unassigned, key = { it.id }) { FootballPlayRow(it) }
                }
            }
            if (window.deferredIDs.isNotEmpty()) FilledTonalButton(onClick = { window = window.update(data.plays, true); scope.launch { list.scrollToItem(0) } }, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text("${window.deferredIDs.size} new plays · Jump to live") }
        }
    }
}

@Composable
private fun FootballDriveCard(drive: GameDrive, expanded: Boolean, toggle: () -> Unit) {
    PhoneCard(onClick = toggle) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("${drive.team} DRIVE".trim(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide drive plays" else "Show drive plays") }
        Text(listOfNotNull(drive.footballDrive?.startQuarter?.let(NativeFootballPlays::quarter)?.takeIf(String::isNotBlank), drive.footballDrive?.playCount?.let { "$it plays" }, drive.yards.takeIf(String::isNotBlank)?.let { "$it yards" }, drive.duration.takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (drive.result.isNotBlank()) Text(drive.result.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (drive.footballDrive?.scoring == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (drive.description.isNotBlank()) Text(drive.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FootballPlayRow(play: Play) {
    val event = play.footballEvent ?: return
    var expanded by rememberSaveable(play.id) { mutableStateOf(false) }
    PhoneCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(when { play.isScoring -> Icons.Rounded.SportsFootball; event.turnover -> Icons.Rounded.UTurnLeft; event.penalty -> Icons.Rounded.Flag; else -> Icons.Rounded.Circle }, null, Modifier.size(18.dp), tint = if (play.isScoring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(play.type.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(listOf(play.period, play.clock).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
        }
        event.downDistance?.let { Text(listOfNotNull(it, event.field).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(play.text, style = if (play.isScoring) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (event.firstDown) Text("FIRST DOWN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (event.turnover) Text("TURNOVER", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = BannerLive)
            if (event.penalty && play.type != "Penalty") Text("PENALTY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            event.yards?.let { Text("Reported gain: $it yards", style = MaterialTheme.typography.bodySmall) }
            Text("Provider play type: ${event.rawType}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            event.participants.distinctBy { it.id to it.name }.forEach { player -> Text(player.name, style = MaterialTheme.typography.bodyMedium) }
            if (event.participants.isNotEmpty()) Text("Player names supplied by NFL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (event.participants.isNotEmpty() || event.yards != null) TextButton(onClick = { expanded = !expanded }, Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Hide play details" else "Play details") }
    }
}
