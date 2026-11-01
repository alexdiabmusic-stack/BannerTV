package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*

internal fun basketballBoxSections(rows: List<BoxRow>): List<Pair<String, List<BoxRow>>> = listOf(
    "Starters" to rows.filter { it.starter && !it.didNotPlay },
    "Bench" to rows.filter { !it.starter && !it.didNotPlay },
    "Did not play" to rows.filter { it.didNotPlay },
).filter { it.second.isNotEmpty() }

/** BasketballBoxScoreView's full-name, adaptive 12-stat cards and separate starter/bench/DNP sections. */
@Composable
fun BasketballBoxScore(match: Match, data: GameCentre, model: MobileViewModel) {
    var team by rememberSaveable(match.key) { mutableStateOf(match.away.abbreviation) }
    val selected = data.boxScore.firstOrNull { TeamIdentity.sameAbbreviation(match.league.path, it.abbreviation, team) }
    val group = selected?.groups?.firstOrNull()
    val sections = basketballBoxSections(group?.rows.orEmpty())
    val onCourt = data.basketball?.onCourt.orEmpty().map { it.id }.toSet()
    ScreenList(sectionSpacing = 12.dp) {
        data.lineScore?.let { line -> item("quarters") { FootballQuarterScore(line) } }
        item("team") { SegmentedChoices(listOf(match.away.abbreviation, match.home.abbreviation), team) { team = it } }
        item("source") { Text("${data.providerStamp?.provider.orEmpty()} · ${data.basketball?.surfaceUpdatedAt?.get("box")?.let(::snapshotTime) ?: "Player statistics not yet reported"}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (sections.isEmpty()) item("empty") { EmptyState("Player statistics unavailable", "Statistics for $team appear when the native feed publishes them.") }
        sections.forEach { (title, rows) ->
            item("section:$title") { SectionTitle(title) }
            items(rows, key = { "$title:${it.athleteId ?: it.name}" }) { row ->
                PhoneCard {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = row.athleteId != null) {
                        row.athleteId?.let { model.open(PhonePage.PlayerDetail(match.league, Athlete(it, row.name, position = row.detail.substringBefore(" · "), headshotUrl = row.headshot))) }
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Logo(row.headshot, row.name, 40.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.name, style = MaterialTheme.typography.titleSmall)
                            if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (row.athleteId in onCourt && match.state == GameState.LIVE) Text("On court", style = MaterialTheme.typography.labelSmall, color = BannerGreen)
                        }
                        if (row.athleteId != null) Icon(Icons.Rounded.ChevronRight, "Player details")
                    }
                    if (!row.didNotPlay) BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = (maxWidth.value / (54f * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 6)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            group?.labels.orEmpty().zip(row.stats).chunked(columns).forEach { cells ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    cells.forEach { (label, value) -> Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                                        contentDescription = "$label: ${value.takeUnless { it == "–" } ?: "not reported"}"
                                    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(value, style = MaterialTheme.typography.bodyLarge)
                                    } }
                                    repeat(columns - cells.size) { Spacer(Modifier.weight(1f)) }
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
fun BasketballLeaders(leaders: List<BasketballLeader>, match: Match, model: MobileViewModel) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(leaders, key = { "${it.team}:${it.player.id}" }) { leader ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.width((220 * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)).dp).clickable {
                    model.open(PhonePage.PlayerDetail(match.league, leader.player))
                }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Logo(leader.player.headshotUrl, leader.player.name, 52.dp)
                    Text(leader.player.name, style = MaterialTheme.typography.titleSmall)
                    Text(leader.team, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(leader.statLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
