package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.NativeSoccerPresentation

/** Shared iOS soccer hierarchy: curated key comparisons, optional advanced metrics, reported player rows. */
@Composable
internal fun SoccerStatsScreen(match: Match, data: GameCentre, model: MobileViewModel) {
    var advanced by rememberSaveable(match.key) { mutableStateOf(false) }
    var home by rememberSaveable(match.key) { mutableStateOf(true) }
    val keyStats = NativeSoccerPresentation.keyStats(data.teamStats)
    val rest = data.teamStats.filter { it !in keyStats }
    ScreenList {
        item("key") { ComparisonStats(match, keyStats, data.teamStatsSource) }
        data.soccer?.surfaceUpdatedAt?.get("statistics")?.let { at -> item("time") {
            Text("Team statistics · ${snapshotTime(at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (rest.isNotEmpty()) {
            item("advanced-toggle") { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Show fewer team stats" else "Show more team stats") } }
            if (advanced) item("advanced") { ComparisonStats(match, rest, data.teamStatsSource) }
        }
        val players = data.soccer?.playerStats.orEmpty()
        if (players.isNotEmpty()) {
            item("players-title") { SectionTitle("Player match statistics") }
            data.soccer?.surfaceUpdatedAt?.get("playerStats")?.let { at -> item("players-time") { Text("${data.providerStamp?.provider} · ${snapshotTime(at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item("side") {
                val choices = listOf("Home · ${match.home.abbreviation}", "Away · ${match.away.abbreviation}")
                ChoiceRow(choices, if (home) choices[0] else choices[1]) { home = it == choices[0] }
            }
            items(players.filter { it.home == home }, key = { it.player.id }) { SoccerPlayerStatistics(it, match, model) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoccerPlayerStatistics(row: SoccerPlayerMatchStats, match: Match, model: MobileViewModel) {
    var expanded by rememberSaveable(match.key, row.player.id) { mutableStateOf(false) }
    val headline = listOf("Normalized Player Minutes", "Goals", "Assists", "Shots At Goal Sum", "Shots On Target", "Passes Successful Sum")
    PhoneCard {
        val person = phoneSoccerAthlete(row.player, nativeEpl = false)
        if (person != null) TextButton(onClick = { model.open(PhonePage.PlayerDetail(match.league, person)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.heightIn(min = 48.dp)) {
            Text(row.player.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        } else Text(row.player.name, style = MaterialTheme.typography.titleSmall)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (if (expanded) row.stats.toList() else headline.mapNotNull { key -> row.stats[key]?.let { key to it } }).forEach { (label, value) ->
                Column(Modifier.widthIn(min = 88.dp, max = 150.dp)) {
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(when (label) { "Normalized Player Minutes" -> "Minutes"; "Shots At Goal Sum" -> "Shots"; "Passes Successful Sum" -> "Completed passes"; else -> label },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Show key player stats" else "Show all reported player stats") }
    }
}
