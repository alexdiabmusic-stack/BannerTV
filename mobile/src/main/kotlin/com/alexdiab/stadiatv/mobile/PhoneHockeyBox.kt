package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*

@Composable
fun HockeyLeaders(leaders: List<HockeyLeader>, match: Match, model: MobileViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(leaders, key = { "${it.team}:${it.role}:${it.player.id}" }) { leader ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.width(174.dp).clickable { model.open(PhonePage.PlayerDetail(match.league, leader.player)) }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Logo(leader.player.headshotUrl, leader.player.name, 54.dp)
                    Text(leader.player.name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(leader.line, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(leader.team, leader.role).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** iOS NHLBoxScoreView uses disclosures, not a tiny all-column table for every player. */
@Composable
fun HockeyBoxScore(match: Match, data: GameCentre, model: MobileViewModel) {
    val hockey = data.hockey ?: return
    var selectedTeam by rememberSaveable(match.key) { mutableStateOf(match.away.abbreviation) }
    val players = hockey.players.filter { it.team == selectedTeam }
    ScreenList(sectionSpacing = 12.dp) {
        item("stats") { ComparisonStats(match, data.teamStats, data.teamStatsSource ?: "NHL") }
        item("team") { SegmentedChoices(listOf(match.away.abbreviation, match.home.abbreviation), selectedTeam) { selectedTeam = it } }
        for (group in listOf("Forwards", "Defensemen", "Goalies")) {
            val rows = players.filter { it.group == group }
            if (rows.isNotEmpty()) item("group-$group") { SectionTitle(group) }
            items(rows, key = { it.player.id }) { row -> HockeyPlayerDisclosure(row, match, model) }
        }
        if (players.isEmpty()) item("empty") { EmptyState("Player statistics unavailable", "NHL player statistics will appear when supplied by the box score.") }
    }
}

@Composable
private fun HockeyPlayerDisclosure(row: HockeyPlayerStats, match: Match, model: MobileViewModel) {
    var expanded by rememberSaveable(match.key, row.player.id) { mutableStateOf(false) }
    val keys = if (row.group == "Goalies") setOf("saves", "shotsAgainst", "goalsAgainst", "toi") else setOf("goals", "assists", "points", "sog", "toi")
    PhoneCard {
        Row(Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Logo(row.player.headshotUrl, row.player.name, 38.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.player.name, style = MaterialTheme.typography.titleSmall)
                Text(row.stats.filter { it.key in keys }.joinToString(" · ") { "${it.label} ${it.value}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse player statistics" else "Expand player statistics") }
        }
        if (expanded) {
            HockeyStatGrid(row.stats)
            TextButton(onClick = { model.open(PhonePage.PlayerDetail(match.league, row.player)) }, Modifier.heightIn(min = 48.dp)) { Text("Player details") }
        }
    }
}

@Composable
private fun HockeyStatGrid(stats: List<HockeyStat>) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth.value / (100 * scale)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (row in stats.chunked(columns)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (stat in row) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stat.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stat.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
