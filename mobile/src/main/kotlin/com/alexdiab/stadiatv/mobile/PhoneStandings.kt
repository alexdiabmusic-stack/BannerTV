package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*

@Composable
fun PhoneStandingsScreen(league: League, model: MobileViewModel) {
    val snapshots by model.followingTables.state.collectAsStateWithLifecycle()
    val billing by model.entitlements.state.collectAsStateWithLifecycle()
    if (!billing.isPremium) { PhonePremiumGate("Full standings", model); return }
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    var division by rememberSaveable(league.path) { mutableStateOf(false) }
    LaunchedEffect(league.path) { model.followingTables.refresh(league.path) }
    val snapshot = snapshots[league.path] ?: PhoneSnapshot()
    val groups = snapshot.value.orEmpty()
    val source = groups.mapNotNull { it.source }.distinct().joinToString(" / ")
    ScreenList {
        item("status") { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(source.takeIf(String::isNotBlank), when { snapshot.loading -> "Updating standings…"; snapshot.failed && groups.isNotEmpty() -> "Refresh unavailable · last good standings retained"; snapshot.failed -> "Standings unavailable"; snapshot.checkedAt > 0 -> "Checked ${clockTime(snapshot.checkedAt)}"; else -> "Standings" }).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.followingTables.refresh(league.path, true) }, enabled = !snapshot.loading) { Text(if (snapshot.failed) "Retry" else "Refresh") }
        } }
        if (snapshot.loading && groups.isEmpty()) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!snapshot.loading && groups.isEmpty()) item("empty") { EmptyState("Standings unavailable", "No current season table was returned for ${league.name}. Only published standings are shown.", "Retry", { model.followingTables.refresh(league.path, true) }) }
        if (PhoneStandingColumn.hasGroupingChoice(groups)) item("grouping") { ChoiceRow(listOf("Conference", "Division"), if (division) "Division" else "Conference") { division = it == "Division" } }
        if (groups.isNotEmpty()) item("hint") { Text("Swipe tables horizontally to see every column. Reported ranks are used where supplied; otherwise provider order is retained.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(PhoneStandingColumn.displayed(groups, division), key = { it.name }) { group ->
            val columns = PhoneStandingColumn.columns(league)
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val cell = (52f * fontScale).dp
            val teamWidth = (160f * fontScale.coerceAtMost(1.3f)).dp
            val rowHeight = (72f * fontScale).dp
            PhoneCard {
                Text(group.name.uppercase(), style = MaterialTheme.typography.titleSmall)
                Text("${group.rows.size} teams", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth()) {
                    // Keep the team identity visible when inspecting points/streak or later columns.
                    Column(Modifier.width(teamWidth + 28.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(32.dp)) {
                            Text("#", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall)
                            Text("Team", Modifier.width(teamWidth), style = MaterialTheme.typography.labelSmall)
                        }
                        group.rows.forEachIndexed { index, row ->
                            val rank = PhoneStandingColumn.rank(group, row, index)
                            val team = Team(row.teamId, row.teamName, row.teamName, row.abbreviation, row.logoUrl, league)
                            val followed = settings.isFavorite(team)
                            HorizontalDivider()
                            Row(Modifier.background(if (followed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                                .clickable { model.open(PhonePage.TeamDetail(team)) }.height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                                Text(rank, Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = if (followed || rank == "1") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.width(teamWidth).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Logo(row.logoUrl, row.abbreviation, 28.dp)
                                    Column(Modifier.weight(1f)) { Text(row.teamName, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (followed) FontWeight.Bold else FontWeight.Medium); Text(row.abbreviation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) { columns.forEach { col -> Text(col.header(league), Modifier.width(cell).semantics { contentDescription = col.title(league) }, style = MaterialTheme.typography.labelSmall) } }
                        group.rows.forEach { row ->
                            val team = Team(row.teamId, row.teamName, row.teamName, row.abbreviation, row.logoUrl, league)
                            val followed = settings.isFavorite(team)
                            HorizontalDivider(Modifier.width(cell * columns.size))
                            Row(Modifier.height(rowHeight).background(if (followed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                                .clickable { model.open(PhonePage.TeamDetail(team)) }, verticalAlignment = Alignment.CenterVertically) {
                                columns.forEach { col ->
                                    val value = col.value(row, league)
                                    val color = when { col == PhoneStandingColumn.Streak && value.startsWith("W", true) -> Color(0xFF3DBE80); col == PhoneStandingColumn.Streak && value.startsWith("L", true) -> BannerLive; else -> MaterialTheme.colorScheme.onSurface }
                                    Text(value, Modifier.width(cell).semantics { contentDescription = "${col.title(league)}: $value" }, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = if (col in setOf(PhoneStandingColumn.Points, PhoneStandingColumn.Percentage)) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
