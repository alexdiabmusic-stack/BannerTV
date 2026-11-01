package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.NativeSoccerPresentation

/** Soccer uses halves and incident-specific rows, not the generic possession/play-by-play screen. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoccerTimeline(match: Match, data: GameCentre, model: MobileViewModel) {
    var filterName by rememberSaveable(match.key) { mutableStateOf(NativeSoccerPresentation.Filter.All.name) }
    val filter = NativeSoccerPresentation.Filter.entries.firstOrNull { it.name == filterName } ?: NativeSoccerPresentation.Filter.All
    val sections = remember(data.plays, filter) { NativeSoccerPresentation.sections(data.plays, filter) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                ChoiceRow(NativeSoccerPresentation.Filter.entries.map { it.label }, filter.label) { label ->
                    filterName = NativeSoccerPresentation.Filter.entries.first { it.label == label }.name
                }
                data.playsSource?.let { source -> Text(source, Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                data.soccer?.surfaceUpdatedAt?.get("events")?.let { at -> Text("Events · ${snapshotTime(at)}",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sections.isEmpty()) item("empty") {
                        EmptyState(if (filter == NativeSoccerPresentation.Filter.All) "No events yet" else "No ${filter.label.lowercase()} events",
                            if (match.state == GameState.PRE) "Match events will appear after kickoff."
                            else "Only incidents supplied by the match provider appear here.")
                    }
                    for (section in sections) {
                        stickyHeader(key = "half-${section.number}") {
                            Surface(color = MaterialTheme.colorScheme.background) {
                                Text(section.title, Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { heading() },
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(section.events, key = { it.id }) { SoccerEventRow(it, match, model) }
                    }
                }
            }
            if (wide) {
                VerticalDivider()
                Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("MATCH CONTEXT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${match.home.abbreviation} ${match.displayScore} ${match.away.abbreviation}", style = MaterialTheme.typography.titleLarge)
                    Text(match.statusDetail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val keyStats = NativeSoccerPresentation.keyStats(data.teamStats)
                    if (keyStats.isNotEmpty()) ComparisonStats(match, keyStats, data.teamStatsSource)
                }
            }
        }
    }
}

/** No merged accessibility label: scorer/assist and on/off links must remain separate TalkBack actions. */
@Composable
internal fun SoccerEventRow(play: Play, match: Match, model: MobileViewModel) {
    val event = play.soccerEvent
    val side = listOf(match.home, match.away).firstOrNull { it.teamId != null && it.teamId == play.teamId }
    val goal = event?.kind in setOf(SoccerEventKind.Goal, SoccerEventKind.OwnGoal, SoccerEventKind.PenaltyGoal)
    val card = event?.kind in setOf(SoccerEventKind.YellowCard, SoccerEventKind.SecondYellow, SoccerEventKind.RedCard)
    val substitution = event?.kind == SoccerEventKind.Substitution
    Surface(shape = RoundedCornerShape(if (goal) 16.dp else 10.dp),
        color = if (goal) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (goal) 14.dp else 6.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(play.clock.ifBlank { "–" }, Modifier.widthIn(min = 44.dp).padding(top = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card) Box(Modifier.width(10.dp).height(15.dp).background(
                        if (event?.kind == SoccerEventKind.RedCard || event?.kind == SoccerEventKind.SecondYellow) Color(0xFFEF5350) else Color(0xFFFFD54F), RoundedCornerShape(2.dp)))
                    Text(listOfNotNull(play.type.takeIf(String::isNotBlank), side?.abbreviation).joinToString(" · "),
                        style = if (goal) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                        color = if (goal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    substitution -> {
                        SoccerIncidentPlayer(event?.primary, "Coming on", match, model)
                        SoccerIncidentPlayer(event?.secondary, "Going off", match, model)
                    }
                    event?.primary != null -> {
                        SoccerIncidentPlayer(event.primary, if (goal) "Scorer" else null, match, model, emphasized = goal)
                        if (goal && event.secondary != null) SoccerIncidentPlayer(event.secondary, "Assist", match, model)
                        if (goal && event.secondAssist != null) SoccerIncidentPlayer(event.secondAssist, "Second assist", match, model)
                        if (event.kind == SoccerEventKind.Foul && event.secondary != null) SoccerIncidentPlayer(event.secondary, "Fouled player", match, model)
                    }
                    else -> Text(play.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SoccerIncidentPlayer(player: SportPlayer?, role: String?, match: Match, model: MobileViewModel, emphasized: Boolean = false) {
    if (player == null) return
    val person = phoneSoccerAthlete(player, nativeEpl = true)
    val label = listOfNotNull(role, player.name).joinToString(": ")
    if (person != null) TextButton(onClick = { model.open(PhonePage.PlayerDetail(match.league, person)) },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium)
    } else Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
}
