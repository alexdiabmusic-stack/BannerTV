package com.alexdiab.stadiatv.mobile

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import java.util.Calendar
import java.util.TimeZone

data class PhoneLeagueScheduleQuery(val leaguePath: String, val first: Long, val days: Int = 7) {
    init { require(days in 1..14) }
    val endExclusive: Long get() = Dates.addDays(first, days)
}

internal fun phoneCalendarDay(year: Int, month: Int, day: Int, zone: TimeZone = TimeZone.getDefault()): Long? = runCatching {
    if (year !in 1900..2200 || month !in 0..11 || day !in 1..31) return@runCatching null
    val value = Calendar.getInstance(zone).apply { clear(); isLenient = false; set(year, month, day, 12, 0, 0) }.timeInMillis
    Calendar.getInstance(zone).apply { timeInMillis = value; set(Calendar.HOUR_OF_DAY, 0) }.timeInMillis
}.getOrNull()

/** Bounded date-range browsing replaces the fixed seven-day-only league list; results retain original identities. */
@Composable
fun PhoneLeagueSchedule(league: League, model: MobileViewModel, selected: Long, selectDate: (Long) -> Unit) {
    val query = PhoneLeagueScheduleQuery(league.path, selected)
    val cache by model.leagueSchedules.state.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val snapshot = cache[query] ?: PhoneSnapshot()
    val context = LocalContext.current
    val offline = connection.known && !connection.online
    LaunchedEffect(query, connection) {
        if (!offline) model.leagueSchedules.refresh(query, force = model.leagueSchedules.state.value[query]?.failed == true)
    }
    ScreenList {
        item("date") { PhoneCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectDate(Dates.addDays(selected, -7)) }) { Icon(Icons.Rounded.ChevronLeft, "Previous seven days") }
                Text("${Dates.format("MMM d", selected)} – ${Dates.format("MMM d, yyyy", Dates.addDays(selected, 6))}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { selectDate(Dates.addDays(selected, 7)) }) { Icon(Icons.Rounded.ChevronRight, "Next seven days") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { selectDate(Dates.startOfDay(System.currentTimeMillis())) }) { Text("Today") }
                TextButton(onClick = {
                    val calendar = Calendar.getInstance().apply { timeInMillis = selected }
                    DatePickerDialog(context, { _, year, month, day -> phoneCalendarDay(year, month, day)?.let(selectDate) },
                        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
                }) { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose date") }
            }
            Text("Times are local. Only announced provider results are shown; historical coverage varies by league.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item("status") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                offline && snapshot.value == null -> "Schedule unavailable · Offline"
                offline -> "Offline schedule · ${snapshot.checkedAt.takeIf { it > 0 }?.let(::snapshotTime).orEmpty()}"
                snapshot.loading -> "Checking schedule…"
                snapshot.failed && snapshot.value != null -> "Refresh unavailable · Saved ${snapshot.checkedAt.takeIf { it > 0 }?.let(::snapshotTime).orEmpty()}"
                snapshot.failed -> "Schedule unavailable"
                snapshot.checkedAt > 0 -> "Checked ${snapshotTime(snapshot.checkedAt)}"
                else -> "Schedule"
            }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { model.leagueSchedules.refresh(query, true) }, enabled = !offline && !snapshot.loading) { Text(if (snapshot.failed) "Retry" else "Refresh") }
        } }
        if (snapshot.loading && snapshot.value == null) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (snapshot.value == null && (offline || snapshot.failed)) item("unavailable") { EmptyState("Schedule unavailable", "This date range couldn't be checked. Retrying requires a working connection and provider support for the selected dates.") }
        if (snapshot.value?.isEmpty() == true && !snapshot.failed) item("empty") { EmptyState("No announced games", "The provider returned no games in this seven-day range. Choose another date to browse results or upcoming games.") }
        snapshot.value.orEmpty().groupBy { Dates.startOfDay(it.startMillis) }.forEach { (day, games) ->
            item("date:$day") { SectionTitle(Dates.format("EEEE · MMM d, yyyy", day)) }
            items(games, key = { it.key }) { match -> GameCard(match, model) }
        }
    }
}
