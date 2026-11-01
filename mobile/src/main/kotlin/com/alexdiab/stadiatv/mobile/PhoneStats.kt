package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*

@Composable
fun PhoneStats(model: MobileViewModel, initialLeague: String? = null) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val billing by model.entitlements.state.collectAsStateWithLifecycle()
    val snapshots by model.statsTeams.state.collectAsStateWithLifecycle()
    val leagues = PhoneStatsSection.leaguesForVisit(settings.followedLeagues, initialLeague)
    var leaguePath by rememberSaveable(initialLeague) { mutableStateOf(initialLeague) }
    val league = PhoneStatsSection.selectedLeague(leaguePath, leagues)
    LaunchedEffect(leagues.map { it.path }) { leaguePath = league.path }
    var sectionName by rememberSaveable(league.path) { mutableStateOf(PhoneStatsSection.Overview.name) }
    val available = PhoneStatsSection.available(league)
    val section = available.firstOrNull { it.name == sectionName } ?: PhoneStatsSection.Overview
    val isTeamSport = league.group !in setOf(SportGroup.Racing, SportGroup.Golf, SportGroup.Tennis)
    LaunchedEffect(league.path) { if (isTeamSport) model.statsTeams.refresh(league.path) }
    val snapshot = snapshots[league.path] ?: PhoneSnapshot()
    val teams = snapshot.value.orEmpty()
    val favourites = settings.favoriteTeams.filter { it.leaguePath == league.path }
    val favouriteTeams = favourites.map { saved -> teams.firstOrNull(saved::matches)
        ?: Team(saved.teamId, saved.name, saved.name, saved.abbreviation, saved.logoUrl, league) }.distinctBy { it.id }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(leagues.map { it.shortName }, league.shortName) { name -> leaguePath = leagues.first { it.shortName == name }.path } }
        Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { ChoiceRow(available.map { it.label }, section.label) { label -> sectionName = available.first { it.label == label }.name } }
        Box(Modifier.weight(1f)) {
            if (section.requiresPremium && !billing.isPremium) PhonePremiumGate(section.label, model) else when (section) {
                PhoneStatsSection.Overview -> ScreenList {
                    item("header") { PhoneCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Rounded.Insights, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(league.name, style = MaterialTheme.typography.titleLarge)
                                Text(when (league.group) {
                                    SportGroup.Golf -> "Tournament field, leaders and event context"
                                    SportGroup.Racing -> "Race field, championship results and leaderboards"
                                    else -> "${snapshot.value?.size?.let { "$it ${if (it == 1) "team" else "teams"}" } ?: "Team directory"} · ${favourites.size} ${if (favourites.size == 1) "favourite" else "favourites"}"
                                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (isTeamSport) StatsDirectoryStatus(snapshot) { model.statsTeams.refresh(league.path, force = true) }
                    } }
                    if (favouriteTeams.isNotEmpty()) {
                        item("favourites-title") { SectionTitle("Favourites") }
                        items(favouriteTeams, key = { "favourite:${it.id}" }) { StatsTeamCard(it, model) }
                    }
                    item("actions") {
                        BoxWithConstraints {
                            val columns = (maxWidth.value / (150f * LocalDensity.current.fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 3)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                available.filter { it != PhoneStatsSection.Overview }.chunked(columns).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { action -> PhoneCard(Modifier.weight(1f), onClick = { if (action.requiresPremium && !billing.isPremium) model.open(PhonePage.Premium) else sectionName = action.name }) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Icon(action.icon, null, tint = MaterialTheme.colorScheme.primary)
                                                if (action.requiresPremium && !billing.isPremium) Icon(Icons.Rounded.Lock, "Premium", modifier = Modifier.size(18.dp))
                                            }
                                            Text(action.label, style = MaterialTheme.typography.titleSmall)
                                            Text(action.detail(league), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        } }
                                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                    item("format") { StatsFormat(league) }
                }
                PhoneStatsSection.Teams -> ScreenList {
                    item("status") { StatsDirectoryStatus(snapshot) { model.statsTeams.refresh(league.path, force = true) } }
                    if (snapshot.loading && teams.isEmpty()) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (teams.isEmpty() && !snapshot.loading) item("empty") { EmptyState("Teams unavailable", "The team directory hasn't loaded for ${league.name}. You can retry without losing saved follows.", "Retry", { model.statsTeams.refresh(league.path, force = true) }) }
                    if (favouriteTeams.isNotEmpty()) {
                        item("favourites-title") { SectionTitle("Favourites") }
                        items(favouriteTeams, key = { "favourite:${it.id}" }) { StatsTeamCard(it, model) }
                    }
                    if (teams.isNotEmpty()) item("all-title") { SectionTitle("All teams") }
                    items(teams, key = { "all:${it.id}" }) { StatsTeamCard(it, model) }
                }
                PhoneStatsSection.Field -> ScreenList {
                    item("format") { StatsFormat(league) }
                    item("field") { AsyncSection(league.path to "stats-field", { model.container.sports.field(league) }) { racers ->
                        if (racers.isEmpty()) EmptyState("Field unavailable", "Published entrants will appear here when the provider makes them available.")
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { racers.forEach { racer -> PhoneCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(racer.rank.ifBlank { "–" }, style = MaterialTheme.typography.titleMedium)
                                Column(Modifier.weight(1f)) { Text(racer.athlete.name, style = MaterialTheme.typography.titleSmall); if (racer.manufacturer.isNotBlank()) Text(racer.manufacturer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Text(racer.score, style = MaterialTheme.typography.labelLarge)
                            }
                        } } }
                    } }
                    item("results") { OutlinedButton(onClick = { model.open(PhonePage.LeagueHub(league, if (league.group == SportGroup.Racing) "Standings" else "Scores")) }, Modifier.fillMaxWidth()) { Text(if (league.group == SportGroup.Racing) "Open championship results" else "Open tournaments & scorecards") } }
                }
                else -> key(league.path, section) { LeagueScreen(league, model,
                    if (league.group == SportGroup.Racing && section == PhoneStatsSection.Leaders) "Standings" else section.label, showNavigation = false) }
            }
        }
    }
}

@Composable
private fun StatsTeamCard(team: Team, model: MobileViewModel) {
    PhoneCard(onClick = { model.open(PhonePage.TeamDetail(team)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(team.logoUrl, team.abbreviation, 38.dp)
            Column(Modifier.weight(1f)) { Text(team.name, style = MaterialTheme.typography.titleSmall); Text(team.abbreviation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
}

@Composable
private fun StatsDirectoryStatus(snapshot: PhoneSnapshot<List<Team>>, refresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(when { snapshot.loading -> "Updating directory…"; snapshot.failed && snapshot.value != null -> "Refresh unavailable · saved directory retained"; snapshot.failed -> "Directory unavailable"; snapshot.checkedAt > 0 -> "Directory checked ${clockTime(snapshot.checkedAt)}"; else -> "Team directory" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = refresh, enabled = !snapshot.loading) { Text(if (snapshot.failed) "Retry" else "Refresh") }
    }
}

@Composable
private fun StatsFormat(league: League) {
    PhoneCard {
        Text("${league.group.label} format", style = MaterialTheme.typography.titleMedium)
        Text(when (league.group) {
            SportGroup.Racing -> "Drivers, constructors and race entrants replace team rosters. Open the championship for driver and constructor points, or a session for timing and classification."
            SportGroup.Golf -> "Tournament fields and leaderboards replace team rosters. Open a tournament for scorecards, rounds, tee times, courses and FedExCup results supplied by PGA TOUR."
            SportGroup.Tennis -> "Draws, player rankings and match results replace team rosters. Only published competition statistics are shown."
            SportGroup.Soccer -> "League tables lead to club rosters and player profiles. Competition leaders are shown only when published."
            SportGroup.Hockey -> "NHL standings emphasize points and division record. Native rosters open NHL player profiles and reported season statistics; other reports are labelled by their actual source."
            SportGroup.Baseball -> "Division standings emphasize record, games back and streak. Rosters link to player profiles; published leaderboards cover batting and pitching."
            SportGroup.Basketball -> "Conference standings, season leaders, team rosters and player profiles provide league-wide context. Injury reports show published availability only."
            SportGroup.Football -> "Conference and division tables, offense/defense/special-team rosters and league-wide leaders complement weekly game coverage."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val PhoneStatsSection.icon: ImageVector get() = when (this) {
    PhoneStatsSection.Overview -> Icons.Rounded.Dashboard
    PhoneStatsSection.Standings -> Icons.Rounded.FormatListNumbered
    PhoneStatsSection.Leaders -> Icons.Rounded.BarChart
    PhoneStatsSection.Teams -> Icons.Rounded.Groups
    PhoneStatsSection.Injuries -> Icons.Rounded.MedicalServices
    PhoneStatsSection.Field -> Icons.Rounded.Flag
}
