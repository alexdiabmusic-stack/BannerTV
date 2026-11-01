@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.data.FavoriteTeam

@Composable
fun PhoneTeamRoster(team: Team, model: MobileViewModel) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val feed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val snapshots by model.teamRosters.state.collectAsStateWithLifecycle()
    val query = team.league.path to team.id
    val identity = remember(team) { FavoriteTeam.from(team) }
    val snapshot = snapshots[query] ?: PhoneSnapshot()
    var section by rememberSaveable(team.league.path, team.id) { mutableStateOf("Roster") }
    val nativeMls = team.league.path == "soccer/usa.1" && team.id.startsWith("mls:")
    LaunchedEffect(query) { if (!nativeMls) model.teamRosters.refresh(query) }
    val groups = snapshot.value.orEmpty()
    ScreenList(sectionSpacing = 10.dp) {
        item("team") { PhoneCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Logo(team.logoUrl, team.abbreviation, 56.dp)
                Column(Modifier.weight(1f)) { Text(team.name, style = MaterialTheme.typography.titleLarge); Text("${team.league.name} · Source: ${team.id.substringBefore(':').uppercase().takeIf { ':' in team.id } ?: "ESPN"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            OutlinedButton(onClick = { model.container.preferences.toggleFavorite(team) }) { Icon(if (settings.isFavorite(team)) Icons.Rounded.Star else Icons.Rounded.StarBorder, null); Spacer(Modifier.width(8.dp)); Text(if (settings.isFavorite(team)) "Following" else "Follow team") }
        } }
        item("sections") { ChoiceRow(listOf("Roster", "Games"), section) { section = it } }
        if (section == "Games") {
            val games = feed.all.filter { it.key !in library.hiddenGameKeys && it.league == team.league && listOf(it.home, it.away).any { side -> identity.matches(team.league, side) } }
            if (games.isEmpty()) item("empty-games") { EmptyState("No games returned", "No games for this team are in the loaded schedule. Refresh Home or Following to update coverage.") }
            items(games, key = { it.key }) { GameCard(it, model) }
        } else {
            if (nativeMls) item("unavailable-roster") { EmptyState("Season roster unavailable", "MLS's verified feed supplies announced players in each match's Lineups tab, but no season-roster endpoint. Open Games to choose a match.") }
            else {
            item("status") { ProfileLoadStatus(snapshot, "Roster") { model.teamRosters.refresh(query, true) } }
            if (snapshot.loading && groups.isEmpty()) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            phoneRosterEmptyMessage(snapshot)?.let { description -> item("empty") { EmptyState("No roster published", description) } }
            groups.forEachIndexed { index, group ->
                if (groups.size > 1) stickyHeader(key = "group:$index") {
                    Text(group.title.uppercase(), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(group.athletes, key = { "$index:${it.id}" }) { person -> PhoneCard(onClick = { model.open(PhonePage.PlayerDetail(team.league, person)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Logo(person.headshotUrl, person.name, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(person.name, style = MaterialTheme.typography.titleSmall)
                            val detail = listOfNotNull(person.jersey.takeIf(String::isNotBlank)?.let { "#$it" }, person.position.takeIf(String::isNotBlank), person.height)
                            if (detail.isNotEmpty()) Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (person.injured) Icon(Icons.Rounded.MedicalServices, "Injured", tint = BannerLive, modifier = Modifier.size(18.dp))
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                } }
            }
            }
        }
    }
}

@Composable
fun PhonePlayerProfile(league: League, athlete: Athlete, model: MobileViewModel) {
    val snapshots by model.playerProfiles.state.collectAsStateWithLifecycle()
    val query = league.path to athlete
    val snapshot = snapshots[query] ?: PhoneSnapshot()
    LaunchedEffect(query) { model.playerProfiles.refresh(query) }
    val overview = snapshot.value
    val person = phoneProfileAthlete(athlete, overview?.athlete)
    val facts = phonePlayerFacts(person, overview)
    ScreenList {
        item("header") { PhoneCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Logo(person.headshotUrl, person.name, 76.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(person.name, style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(person.jersey.takeIf(String::isNotBlank)?.let { "#$it" }, person.positionName ?: person.position.takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    phonePlayerSource(league, person)?.let { source -> Text("${league.shortName} · Source: $source", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (person.injured) Text("Injured", style = MaterialTheme.typography.labelMedium, color = BannerLive)
                }
            }
        } }
        item("status") { ProfileLoadStatus(snapshot, "Profile") { model.playerProfiles.refresh(query, true) } }
        if (facts.isNotEmpty()) item("bio") { PhoneCard { ProfileFactsGrid(facts) } }
        if (snapshot.loading && overview == null) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (overview != null) {
            val label = overview.statlineLabel ?: overview.bio["Season"]
            if (overview.headlineStats.isNotEmpty()) item("headline") { PhoneCard {
                Text(label?.let(::phoneSeasonLabel)?.let { "$it · Reported statistics" } ?: "Reported statistics", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                ProfileFactsGrid(overview.headlineStats)
            } }
            if (overview.stats.isNotEmpty()) {
                item("stats-title") { SectionTitle(phonePlayerStatsTitle(label)) }
                item("stats") { PhoneCard {
                    overview.stats.entries.forEachIndexed { index, (name, value) ->
                        if (index > 0) HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, Modifier.widthIn(max = 130.dp), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                } }
            }
            if (overview.news.isNotEmpty()) item("news-title") { SectionTitle("Latest news") }
            items(overview.news, key = { it.id }) { StoryCard(it, model) }
            if (overview.stats.isEmpty() && overview.news.isEmpty()) item("no-stats") { EmptyState("No additional statistics", "The provider has not published a statistical profile for this player.") }
        }
    }
}

@Composable
private fun ProfileFactsGrid(facts: Map<String, String>) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints {
        val columns = (maxWidth.value / (140 * scale)).toInt().coerceIn(1, 3)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            facts.entries.toList().chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            } }
        }
    }
}

@Composable
private fun <T> ProfileLoadStatus(snapshot: PhoneSnapshot<T>, name: String, refresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(when { snapshot.loading -> "Updating ${name.lowercase()}…"; snapshot.failed && snapshot.value != null -> "Refresh unavailable · saved ${name.lowercase()} retained${snapshot.checkedAt.takeIf { it > 0 }?.let { " · ${snapshotTime(it)}" }.orEmpty()}"; snapshot.failed -> "$name unavailable"; snapshot.checkedAt > 0 -> "Checked ${snapshotTime(snapshot.checkedAt)}"; else -> name }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = refresh, enabled = !snapshot.loading) { Text(if (snapshot.failed) "Retry" else "Refresh") }
    }
}
