package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*

internal fun footballPlayerStatLine(labels: List<String>, values: List<String>): String = labels.zip(values)
    .joinToString(" · ") { (label, value) -> "$value $label" }

/** NFLBoxScoreView: all reported categories, full player names and wrapping stat summaries. */
@Composable
fun FootballBoxScore(match: Match, data: GameCentre, model: MobileViewModel) {
    var selectedTeam by rememberSaveable(match.key) { mutableStateOf(match.away.abbreviation) }
    val team = data.boxScore.firstOrNull { it.abbreviation == selectedTeam }
    ScreenList(sectionSpacing = 12.dp) {
        data.lineScore?.let { line -> item("quarters") { FootballQuarterScore(line) } }
        item("team") { SegmentedChoices(listOf(match.away.abbreviation, match.home.abbreviation), selectedTeam) { selectedTeam = it } }
        item("source") { Text("NFL · Player stats derived from accepted play-by-play${data.football?.detailsUpdatedAt?.takeIf { it > 0 }?.let { " · ${snapshotTime(it)}" }.orEmpty()}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (team == null || team.groups.all { it.rows.isEmpty() }) item("empty") { EmptyState("No player statistics yet", "Only statistics published in the NFL play-by-play are shown.") }
        team?.groups?.forEach { group ->
            item("category:${group.title}") { SectionTitle(group.title) }
            items(group.rows, key = { "${group.title}:${it.athleteId ?: it.name}" }) { row ->
                PhoneCard {
                    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = row.athleteId != null) {
                        row.athleteId?.let { model.open(PhonePage.PlayerDetail(match.league, Athlete(it, row.name, position = row.detail, headshotUrl = row.headshot))) }
                    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (row.athleteId != null) Icon(Icons.Rounded.ChevronRight, "Player details")
                        }
                        Text(footballPlayerStatLine(group.labels, row.stats), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Team and total stay pinned while quarter/overtime columns scroll at large text sizes. */
@Composable
fun FootballQuarterScore(line: LineScore) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val width = (42f * scale).dp; val height = (34f * scale).dp
    PhoneCard {
        Text("QUARTER SCORES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width((56f * scale.coerceAtMost(1.3f)).dp)) {
                listOf("TEAM", line.awayLabel, line.homeLabel).forEach { value -> Box(Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(value, style = MaterialTheme.typography.labelLarge)
                } }
            }
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row { line.periods.forEach { period -> Box(Modifier.width(width).height(height), contentAlignment = Alignment.Center) { Text(period, style = MaterialTheme.typography.labelMedium) } } }
                for ((team, values) in listOf(line.awayLabel to line.away, line.homeLabel to line.home)) Row {
                    line.periods.forEachIndexed { index, period ->
                        val value = values.getOrElse(index) { "–" }
                        Box(Modifier.width(width).height(height).semantics { contentDescription = "$team, $period, ${value.takeIf { it != "–" } ?: "not reported"}" }, contentAlignment = Alignment.Center) {
                            Text(value, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            VerticalDivider(Modifier.height(height * 3))
            Column(Modifier.width(width)) { listOf("T", line.awayTotal, line.homeTotal).forEach { value -> Box(Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            } } }
        }
        Text("Swipe quarters · Team and total stay visible", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
