package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.sports.NflSeasonType
import com.alexdiab.stadiatv.sports.NflWeekChoice
import com.alexdiab.stadiatv.sports.NflWeek
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun NflCalendarScreen(model: MobileViewModel, initialWeek: NflWeek? = null) {
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val seed = initialWeek?.takeIf { it.valid }
    var season by rememberSaveable(seed?.key) { mutableIntStateOf(seed?.season ?: currentYear) }
    var type by rememberSaveable(seed?.key) { mutableStateOf(seed?.type ?: NflSeasonType.REG) }
    var week by rememberSaveable(seed?.key) { mutableIntStateOf(seed?.number ?: 0) }
    var ready by rememberSaveable(seed?.key) { mutableStateOf(seed != null) }
    var initialRevision by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var initialError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf(emptyList<NflWeekChoice>()) }
    var games by remember { mutableStateOf(emptyList<Match>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(initialRevision) {
        if (!ready) {
            initialError = null
            try {
                val current = withContext(Dispatchers.IO) { model.container.nfl.weekFor(System.currentTimeMillis()) }
                season = current.season; type = current.type; week = current.number; ready = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { initialError = "The current NFL week couldn't be resolved. Retry, or choose a season manually." }
        }
    }
    LaunchedEffect(ready, season, type, week, revision) {
        if (!ready) return@LaunchedEffect
        loading = true; error = null; choices = emptyList(); games = emptyList()
        try {
            val options = withContext(Dispatchers.IO) { model.container.nfl.weeks(season, type, force = revision > 0) }
            choices = options
            val selected = options.firstOrNull { it.week.number == week } ?: options.firstOrNull()
            if (selected != null && selected.week.number != week) { week = selected.week.number; return@LaunchedEffect }
            if (selected != null) games = withContext(Dispatchers.IO) { model.container.nfl.schedule(selected.week, force = revision > 0) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "The native NFL schedule couldn't load. Retry respects the provider's cooldown." }
        finally { loading = false }
    }
    ScreenList {
        item { SectionTitle("NFL calendar & results"); Text("NFL schedule · season, stage and week", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!ready) {
            item {
                if (initialError == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Resolving the NFL season and week…") }
                else { Text(initialError.orEmpty()); Row { TextButton(onClick = { initialRevision++ }) { Text("Retry") }; TextButton(onClick = { ready = true }) { Text("Browse manually") } } }
            }
        } else {
            item {
                NflCalendarPicker("Season", "$season", ((minOf(currentYear - 10, season))..maxOf(currentYear, season)).reversed().map(Int::toString)) { season = it.toInt(); week = 0 }
                NflCalendarPicker("Stage", type.label, NflSeasonType.entries.map { it.label }) { selected -> type = NflSeasonType.entries.first { it.label == selected }; week = 0 }
                if (choices.isNotEmpty()) NflCalendarPicker("Week", choices.firstOrNull { it.week.number == week }?.label ?: "Select week", choices.map { it.label }) { selected -> week = choices.first { it.label == selected }.week.number }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("${type.label} · $season", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); TextButton(onClick = { revision++ }, enabled = !loading) { Text("Refresh") } }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton(onClick = { revision++ }) { Text("Retry") } }
                choices.firstOrNull { it.week.number == week }?.byeTeams?.takeIf { it.isNotEmpty() }?.let { Text("Bye: ${it.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (!loading && error == null && games.isEmpty()) item { EmptyState("No games available", if (choices.isEmpty()) "NFL hasn't published weeks for this season and stage." else "No games were supplied for the selected week.") }
            items(games, key = { it.key }) { match ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nflScheduleDate(match.startMillis), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GameCard(match, model)
                }
            }
        }
    }
}

@Composable
private fun NflCalendarPicker(label: String, value: String, values: List<String>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(value, Modifier.weight(1f)); Icon(Icons.Rounded.ArrowDropDown, "Choose $label") }
            DropdownMenu(expanded, { expanded = false }) { values.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; choose(option) }) } }
        }
    }
}
