package com.alexdiab.stadiatv.mobile

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*


@Composable
fun StatTable(title: String, labels: List<String>, rows: List<List<String>>) {
    PhoneCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { labels.forEachIndexed { index, label -> Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(if (index == 0) 150.dp else 64.dp).padding(vertical = 8.dp)) } }
            rows.take(100).forEach { row ->
                HorizontalDivider()
                Row { row.forEachIndexed { index, value -> Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(if (index == 0) 150.dp else 64.dp).padding(vertical = 10.dp, horizontal = 2.dp)) } }
            }
        }
    }
}

@Composable
fun LeagueScreen(league: League, model: MobileViewModel, initialSection: String = "Scores", showNavigation: Boolean = true, nflWeek: com.alexdiab.stadiatv.sports.NflWeek? = null, calendarDate: Long? = null) {
    // Keep the calendar range above the section branches, so visiting Teams/Standings cannot reset it.
    var calendarDay by rememberSaveable(league.path, calendarDate) { mutableLongStateOf(com.alexdiab.stadiatv.util.Dates.startOfDay(calendarDate ?: System.currentTimeMillis())) }
    if (league.path == "racing/f1") { FormulaCalendar(model, initialSection); return }
    if (league.path == "golf/pga") { GolfLeague(model, if (initialSection == "Standings") "FedExCup" else "This week"); return }
    var section by rememberSaveable(league.path, initialSection) { mutableStateOf(initialSection.takeIf { it in listOf("Scores", "Schedule", "Standings", "Teams", "Leaders", "Injuries", "Field") } ?: "Scores") }
    if (section == "Standings") {
        Column(Modifier.fillMaxSize()) {
            if (showNavigation) Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(listOf("Scores", "Schedule", "Standings", "Teams", "Leaders", "Injuries"), section) { section = it } }
            Box(Modifier.weight(1f)) { PhoneStandingsScreen(league, model) }
        }
        return
    }
    if (section == "Injuries") {
        Column(Modifier.fillMaxSize()) {
            if (showNavigation) Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(listOf("Scores", "Schedule", "Standings", "Teams", "Leaders", "Injuries"), section) { section = it } }
            Box(Modifier.weight(1f)) { PhoneInjuriesScreen(league, model) }
        }
        return
    }
    val fields = league.group == SportGroup.Racing || league.group == SportGroup.Golf
    if (league.path == "football/nfl" && section == "Schedule") {
        Column(Modifier.fillMaxSize()) {
            if (showNavigation) Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(listOf("Scores", "Schedule", "Standings", "Teams", "Leaders", "Injuries"), section) { section = it } }
            Box(Modifier.weight(1f)) { NflCalendarScreen(model, nflWeek) }
        }
        return
    }
    if (section == "Schedule") {
        Column(Modifier.fillMaxSize()) {
            if (showNavigation) Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(listOf("Scores", "Schedule", "Standings", "Teams", "Leaders", "Injuries"), section) { section = it } }
            Box(Modifier.weight(1f)) { PhoneLeagueSchedule(league, model, calendarDay) { calendarDay = it } }
        }
        return
    }
    ScreenList {
        if (showNavigation) item { ChoiceRow(listOf("Scores", "Schedule", "Standings", "Teams", "Leaders", "Injuries") + if (fields) listOf("Field") else emptyList(), section) { section = it } }
        when (section) {
            "Scores" -> item {
                AsyncSection(league.path to section, { model.container.sports.scoreboard(league) }) { matches ->
                    if (matches.isEmpty()) EmptyState("No games scheduled", "There are no announced games for this period.")
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { matches.forEach { GameCard(it, model) } }
                }
            }
            "Teams" -> item {
                AsyncSection(league.path to section, { model.container.sports.teams(league) }) { teams ->
                    if (teams.isEmpty()) EmptyState("No teams listed", "Team information is not available for this league.")
                    teams.forEach { team -> ListItem(headlineContent = { Text(team.name) }, leadingContent = { Logo(team.logoUrl, team.abbreviation, 36.dp) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clickable { model.open(PhonePage.TeamDetail(team)) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) }
                }
            }
            "Leaders" -> item {
                AsyncSection(league.path to section, { model.container.sports.leaders(league) }) { boards ->
                    if (boards.isEmpty()) EmptyState("No leaders yet", "Leaders will appear when season statistics are available.")
                    boards.forEach { board -> SectionTitle(board.title); board.rows.forEach { leader -> ListItem(headlineContent = { Text("${leader.rank}. ${leader.athlete.name}") }, supportingContent = { Text(leader.team) }, trailingContent = { Text(leader.value) }, modifier = Modifier.clickable { model.open(PhonePage.PlayerDetail(league, leader.athlete)) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) } }
                }
            }
            else -> item {
                AsyncSection(league.path to section, { model.container.sports.field(league) }) { racers ->
                    if (racers.isEmpty()) EmptyState("Field unavailable", "Entrants will appear when announced.")
                    racers.forEach { racer -> ListItem(headlineContent = { Text("${racer.rank}  ${racer.athlete.name}") }, supportingContent = { Text(racer.manufacturer) }, trailingContent = { Text(racer.score) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)) }
                }
            }
        }
    }
}

@Composable
fun TeamScreen(team: Team, model: MobileViewModel) {
    PhoneTeamRoster(team, model)
}

@Composable
fun AthleteScreen(league: League, athlete: Athlete, model: MobileViewModel) {
    PhonePlayerProfile(league, athlete, model)
}
