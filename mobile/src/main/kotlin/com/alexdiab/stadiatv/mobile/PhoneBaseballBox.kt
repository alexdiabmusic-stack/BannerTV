package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
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

/** iOS MLBBoxScoreView's readable player cards and adaptive grids, not a tiny all-column phone table. */
@Composable
fun BaseballBoxScore(match: Match, data: GameCentre, model: MobileViewModel) {
    var team by rememberSaveable(match.key) { mutableStateOf(match.away.abbreviation) }
    val selected = data.boxScore.firstOrNull { it.abbreviation == team } ?: data.boxScore.firstOrNull()
    ScreenList(sectionSpacing = 12.dp) {
        data.lineScore?.let { line -> item("line") { BaseballLineScore(line) } }
        item("team") { SegmentedChoices(data.boxScore.map { it.abbreviation }, selected?.abbreviation.orEmpty()) { team = it } }
        if (selected == null) item("empty") { EmptyState("Box score unavailable", "MLB player statistics will appear when published.") }
        selected?.groups?.forEach { group ->
            item("group:${group.title}") { SectionTitle(group.title) }
            items(group.rows, key = { "${group.title}:${it.athleteId ?: it.name}" }) { row ->
                PhoneCard {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = row.athleteId != null) {
                        row.athleteId?.let { model.open(PhonePage.PlayerDetail(match.league, Athlete(it, row.name, position = row.detail, headshotUrl = row.headshot))) }
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Logo(row.headshot, row.name, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleSmall)
                            if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (row.athleteId != null) Icon(Icons.Rounded.ChevronRight, "Player details")
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = (maxWidth.value / (54f * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 6)
                        val stats = group.labels.zip(row.stats)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            stats.chunked(columns).forEach { cells -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                cells.forEach { (label, value) -> Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$label: ${if (value == "–") "not reported" else value}" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                } }
                                repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
                            } }
                        }
                    }
                }
            }
        }
    }
}
