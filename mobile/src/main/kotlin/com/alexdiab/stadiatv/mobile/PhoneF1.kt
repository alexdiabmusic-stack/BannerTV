package com.alexdiab.stadiatv.mobile

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.f1.*
import kotlinx.coroutines.*
import java.util.Calendar

@Composable
fun FormulaCalendar(model: MobileViewModel, initialSection: String = "Scores") {
    var season by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var tab by rememberSaveable(initialSection) { mutableStateOf(if (initialSection == "Standings") "Championship" else "Calendar") }
    Column {
        UnderlineTabs(listOf("Calendar", "Championship"), tab) { tab = it }
        ScreenList {
            if (tab == "Calendar") {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { season-- }, enabled = season > 1950) { Icon(Icons.Rounded.ChevronLeft, "Previous F1 season") }
                    Text("$season season", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { season++ }, enabled = season < Calendar.getInstance().get(Calendar.YEAR) + 1) { Icon(Icons.Rounded.ChevronRight, "Next F1 season") }
                } }
                item { AsyncSection(season, { model.container.f1.meetings(season) }) { meetings ->
                    if (meetings.isEmpty()) EmptyState("Calendar unavailable", "The official calendar has not announced sessions for this season.")
                    meetings.forEach { meeting -> PhoneCard {
                        Text(meeting.name, style = MaterialTheme.typography.titleLarge)
                        Text(listOf(meeting.circuit, meeting.country).filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        meeting.sessions.forEach { session -> Row(Modifier.fillMaxWidth().clickable { model.open(PhonePage.Game(model.container.f1.match(session))) }.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(session.name, fontWeight = FontWeight.SemiBold); Text("${dateLabel(session.start)} · ${clockTime(session.start)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Icon(Icons.Rounded.ChevronRight, "Open ${session.name}")
                        } }
                    } }
                } }
            } else item { AsyncSection("f1-standings", { model.container.f1.standings() }) { groups -> groups.forEach { group -> StatTable(group.name, listOf("Name", "Points", "Wins"), group.rows.map { listOf(it.teamName, it.values["points"].orEmpty(), it.values["wins"].orEmpty()) }) } } }
        }
    }
}

@Composable
fun FormulaGame(match: Match, model: MobileViewModel) {
    AsyncSection(match.key, { model.container.f1.resolve(match) }) { session ->
        if (session == null) EmptyState("Session not identified", "Choose the official session from the F1 calendar. Different sessions are never combined.", "F1 calendar", { model.open(PhonePage.LeagueHub(match.league)) })
        else FormulaRaceCentre(session, model)
    }
}

@Composable
private fun FormulaRaceCentre(session: F1Session, model: MobileViewModel) {
    val lifecycle = LocalLifecycleOwner.current
    val billing by model.entitlements.state.collectAsStateWithLifecycle()
    var view by remember(session.id) { mutableStateOf(F1View(session)) }
    var tab by rememberSaveable(session.id) { mutableStateOf("Timing") }
    var selected by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session.id, lifecycle, retry) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { withContext(Dispatchers.IO) { model.container.f1.watch(session).collect { view = it } } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { view = view.copy(connection = "failed", error = "F1 timing couldn't load. Saved timing remains visible.") }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val state = view.state
    val sessionMatch = model.container.f1.match(session, state)
    PhoneGameCentreLayout(expanded = true, hasSecondary = true, primary = {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text("Formula 1", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); StatusPill(if (view.isLive(now)) "LIVE" else if (view.cached && state != null) "SAVED" else if (state?.completed == true) "FINAL" else view.connection.replaceFirstChar { it.uppercase() }, view.isLive(now)) }
            Text(session.meeting, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${session.name} · ${session.circuit}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state?.lap?.let { "Lap $it${state.totalLaps?.let { total -> "/$total" }.orEmpty()}" } ?: "${dateLabel(session.start)} · ${clockTime(session.start)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                state?.seconds(now)?.let { Text("${it / 3600}:${(it / 60 % 60).toString().padStart(2, '0')}:${(it % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.titleMedium) }
                val context = LocalContext.current
                IconButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, "${session.meeting} · ${session.name}").putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, session.start).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, session.start + 7200000)) }.onFailure { model.message = "No calendar app is installed." } }) { Icon(Icons.Rounded.Event, "Add F1 session to calendar") }
            }
            state?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(trackColour(it.trackCode), CircleShape)); Text(it.trackStatus, style = MaterialTheme.typography.labelLarge); Text(it.status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        view.error?.let { error -> Surface(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), shape = PhoneShape.control, color = PhoneDesign.colors.warning.container) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = PhoneDesign.colors.warning.foreground)
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = PhoneDesign.colors.warning.foreground)
                TextButton(onClick = { retry++ }, Modifier.heightIn(min = 48.dp)) { Text("Reconnect", color = PhoneDesign.colors.warning.foreground) }
            }
        } }
        if (sessionMatch.state != GameState.FINAL) MatchWatchBar(sessionMatch, model)
    }, secondary = {
      Column(Modifier.fillMaxSize()) {
        UnderlineTabs(listOf("Timing", "Track", "Strategy", "Race control", "Radio", "Weather"), tab) { tab = it }
        if (!billing.isPremium) ScreenList {
            item("premium") { PhonePremiumGate("Race Centre", model) }
            item("related") { RelatedGameNews(sessionMatch, model) }
        }
        else if (state == null) EmptyState(if (session.start > now) "Session has not started" else "Waiting for official timing", "Timing appears only when the feed identifies this exact session. Archived results are loaded for past races.", "Retry", { retry++ })
        else when (tab) {
            "Timing" -> ScreenList {
                item { Text(if (session.qualifying) "Position · best lap · gap in this qualifying phase" else "Position · gap to leader · interval", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.drivers, key = { it.number }) { driver -> FormulaDriverRow(driver, session.qualifying) { selected = driver.number } }
                if (state.drivers.isEmpty()) item { EmptyState("Timing unavailable", "No driver timing has been published for this session.") }
            }
            "Track" -> ScreenList { item { FormulaTrack(state, view.fast, selected) { selected = it } }; item { Text("Observed position trails, not a fabricated circuit outline. Position feed is available only during supported live sessions.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            "Strategy" -> ScreenList {
                items(state.drivers, key = { it.number }) { driver -> PhoneCard(onClick = { selected = driver.number }) {
                    Row { Text(driver.tla, Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(driver.stops?.let { "$it stops" } ?: "Pit-stop count unavailable", style = MaterialTheme.typography.labelMedium) }
                    if (driver.stints.isEmpty()) Text("Tyre stints unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { driver.stints.forEach { stint -> Column(Modifier.weight((stint.completedLaps ?: 1).coerceAtLeast(1).toFloat()).background(tyreColour(stint.compound).copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(6.dp)) { Text(stint.compound.take(1), color = tyreColour(stint.compound), fontWeight = FontWeight.Bold); Text(stint.completedLaps?.let { "${it}L" } ?: "–", style = MaterialTheme.typography.labelSmall) } } }
                    driver.stints.forEach { stint -> Text(listOf(stint.compound.lowercase().replaceFirstChar { it.uppercase() }, stint.startLap?.let { "from lap $it" }, stint.laps?.let { "$it tyre laps" }, stint.fresh?.let { if (it) "new" else "used" }).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    state.pitStops.filter { it.driver == driver.number }.forEach { stop -> Text("Stop ${stop.number ?: "–"} · Lap ${stop.lap ?: "–"} · ${stop.duration ?: "–"}s · lane ${stop.laneTime ?: "–"}s", style = MaterialTheme.typography.labelMedium) }
                } }
            }
            "Race control" -> ScreenList {
                if (state.messages.isEmpty()) item { EmptyState("No race-control messages", "Published flags, investigations and penalties appear here.") }
                items(state.messages.asReversed(), key = { it.id }) { message -> PhoneCard { Row { StatusPill(message.flag ?: message.category); Spacer(Modifier.weight(1f)); message.lap?.let { Text("Lap $it", style = MaterialTheme.typography.labelMedium) } }; Text(message.text); Text(listOf(message.time?.let(::clockTime), message.driver?.let { "Car $it" }).filterNotNull().joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            "Radio" -> FormulaRadio(state)
            "Weather" -> ScreenList { item { state.weather?.let { weather -> StatTable("Track weather", listOf("Measure", "Reading"), listOf("Air" to weather.air?.let { "$it °C" }, "Track" to weather.track?.let { "$it °C" }, "Humidity" to weather.humidity?.let { "$it%" }, "Pressure" to weather.pressure?.let { "$it mbar" }, "Wind" to weather.windKph?.let { "%.1f km/h".format(it) }, "Wind direction" to weather.windDirection?.let { "$it°" }, "Rain" to weather.raining?.let { if (it) "Yes" else "No" }).mapNotNull { (key, value) -> value?.let { listOf(key, it) } }) } ?: EmptyState("Weather unavailable", "No weather data has been published for this session.") } }
        }
      }
    })
    state?.drivers?.firstOrNull { billing.isPremium && it.number == selected }?.let { driver -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(driver.name) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Logo(driver.headshot, driver.tla, 56.dp); Text(driver.team); Text("Position ${driver.position ?: "–"} · ${driver.laps ?: "–"} laps"); Text("Last ${driver.lastLap ?: "–"} · Best ${driver.bestLap ?: "–"}")
        driver.sectors.forEachIndexed { index, sector -> Text("S${index + 1}  ${sector.time ?: "–"}", color = performanceColour(sector.performance)) }
        driver.qualifyingTimes.forEachIndexed { index, time -> Text("Q${index + 1}  $time") }
        driver.speeds.forEach { (point, speed) -> Text("$point  $speed km/h") }
        view.fast.telemetry[driver.number]?.let { telemetry -> Text("Speed ${telemetry.speed?.toInt() ?: "–"} km/h · Gear ${telemetry.gear ?: "–"}"); Text("Throttle ${telemetry.throttle?.toInt() ?: "–"}% · Brake ${telemetry.brake?.toInt() ?: "–"}%"); Text("RPM ${telemetry.rpm?.toInt() ?: "–"}"); telemetry.aero?.let { Text("Active aero · raw state $it", style = MaterialTheme.typography.labelMedium) } }
        driver.classification?.let { Text(it) }
    } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Done") } }) }
}

@Composable
private fun FormulaDriverRow(driver: F1Driver, qualifying: Boolean, click: () -> Unit) {
    PhoneCard(onClick = click) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(driver.position?.toString() ?: "–", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
            Box(Modifier.width(3.dp).height(36.dp).background(driver.colour?.let { runCatching { Color(android.graphics.Color.parseColor("#${it.trimStart('#')}")) }.getOrNull() } ?: MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f)) { Text(driver.name, fontWeight = FontWeight.SemiBold); Text(driver.team, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(horizontalAlignment = Alignment.End) { Text(if (qualifying) driver.bestLap ?: "–" else driver.gap ?: "–", fontWeight = FontWeight.Bold); Text(if (qualifying) driver.gap ?: "–" else driver.interval ?: "–", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { driver.sectors.forEachIndexed { index, sector -> Column(Modifier.weight(1f)) { Text("S${index + 1} ${sector.time ?: "–"}", color = performanceColour(sector.performance), style = MaterialTheme.typography.labelSmall); Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { sector.segments.take(32).forEach { Box(Modifier.weight(1f).height(3.dp).background(performanceColour(it))) } } } } }
        val status = listOfNotNull("IN PIT".takeIf { driver.inPit }, "PIT OUT".takeIf { driver.pitOut }, "RETIRED".takeIf { driver.retired }, "STOPPED".takeIf { driver.stopped }, "OUT".takeIf { driver.knockedOut }, driver.classification).joinToString(" · ")
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FormulaTrack(state: F1State, fast: F1Fast, selected: String?, select: (String) -> Unit) {
    val points = fast.positions.filterValues { it.x != 0.0 || it.y != 0.0 }
    if (points.isEmpty()) { EmptyState("Live track positions unavailable", "Timing remains available in the Timing tab."); return }
    val all = fast.trails.values.flatten().filter { it.x != 0.0 || it.y != 0.0 } + points.values
    val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }; val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
    BoxWithConstraints(Modifier.fillMaxWidth().height(360.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))) {
        val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
        val inset = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
        val scale = minOf((width - 2 * inset) / (maxX - minX).coerceAtLeast(1.0), (height - 2 * inset) / (maxY - minY).coerceAtLeast(1.0))
        fun xy(p: F1Position) = Offset(((width - (maxX - minX) * scale) / 2 + (p.x - minX) * scale).toFloat(), ((height - (maxY - minY) * scale) / 2 + (maxY - p.y) * scale).toFloat())
        val trailColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .25f)
        Canvas(Modifier.fillMaxSize()) { fast.trails.values.forEach { values -> val path = Path(); values.filter { it.x != 0.0 || it.y != 0.0 }.forEachIndexed { index, point -> val p = xy(point); if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }; drawPath(path, trailColour, style = Stroke(3f)) } }
        val density = androidx.compose.ui.platform.LocalDensity.current
        state.drivers.forEach { driver -> points[driver.number]?.let { point -> val xy = xy(point); TextButton(onClick = { select(driver.number) }, Modifier.offset(x = with(density) { xy.x.toDp() } - 24.dp, y = with(density) { xy.y.toDp() } - 24.dp).sizeIn(minWidth = 48.dp, minHeight = 48.dp), colors = ButtonDefaults.textButtonColors(containerColor = if (selected == driver.number) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)) { Text(driver.tla, style = MaterialTheme.typography.labelSmall) } } }
    }
}

@Composable
private fun FormulaRadio(state: F1State) {
    var playing by remember(state.identity) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); player.release() }
    }
    ScreenList {
        if (state.radio.isEmpty()) item { EmptyState("Team radio unavailable", "Only published radio captures are listed here.") }
        items(state.radio.asReversed(), key = { it.id }) { capture -> PhoneCard {
            val name = state.drivers.firstOrNull { it.number == capture.driver }?.name ?: capture.driver?.let { "Car $it" } ?: "Team radio"
            Text(name, fontWeight = FontWeight.Bold)
            capture.time?.let { Text(clockTime(it), style = MaterialTheme.typography.labelMedium) }
            Button(onClick = { if (playing == capture.id && player.isPlaying) { player.pause(); playing = null } else { playing = capture.id; player.setMediaItem(MediaItem.fromUri(requireNotNull(capture.url))); player.prepare(); player.play() } }, enabled = capture.url != null) { Icon(if (playing == capture.id) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null); Text(if (playing == capture.id) "Pause" else "Play radio") }
        } }
    }
}
private fun trackColour(code: String?) = when (code) { "1" -> BannerGreen; "5" -> BannerLive; "2", "4", "6", "7" -> Color(0xffffca28); else -> Color.Gray }
private fun tyreColour(compound: String) = when (compound) { "SOFT" -> Color(0xffff5252); "MEDIUM" -> Color(0xffffd54f); "HARD" -> Color(0xffdbe2ec); "INTERMEDIATE" -> BannerGreen; "WET" -> Color(0xff42a5f5); else -> Color.Gray }
private fun performanceColour(performance: String) = when (performance) { "overallBest" -> Color(0xffcf8fff); "personalBest" -> BannerGreen; "unknown" -> Color.Gray; else -> Color(0xffc4cbd6) }
