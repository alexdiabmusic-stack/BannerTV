@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.pga.*
import kotlinx.coroutines.*
import java.util.Calendar

@Composable
fun GolfLeague(model: MobileViewModel, initialSection: String = "This week") {
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection.takeIf { it in listOf("This week", "Season", "FedExCup") } ?: "This week") }
    var year by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    Column {
        UnderlineTabs(listOf("This week", "Season", "FedExCup"), section) { section = it }
        ScreenList {
            when (section) {
                "This week" -> item { AsyncSection("pga-current", { model.container.pga.tournaments(model.container.pga.currentIds()) }) { tournaments ->
                    if (tournaments.isEmpty()) EmptyState("No featured tournament", "The tour hasn't listed a current tournament.")
                    tournaments.forEach { t -> PhoneCard(onClick = { model.open(PhonePage.Golf(t.id)) }) { Logo(t.logo, "PGA", 48.dp); Text(t.name, style = MaterialTheme.typography.titleLarge); Text(listOfNotNull(t.displayDate, t.location, t.statusDisplay).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant); StatusPill(t.status, t.live) } }
                } }
                "Season" -> {
                    item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { year-- }, enabled = year > 1980) { Icon(Icons.Rounded.ChevronLeft, "Previous golf season") }; Text("$year season", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge); IconButton(onClick = { year++ }, enabled = year < Calendar.getInstance().get(Calendar.YEAR) + 1) { Icon(Icons.Rounded.ChevronRight, "Next golf season") } } }
                    item { AsyncSection(year, { model.container.pga.season(year) }) { entries -> entries.forEach { entry -> PhoneCard(onClick = { model.open(PhonePage.Golf(entry.id)) }) { Text(entry.name, style = MaterialTheme.typography.titleMedium); Text(listOfNotNull(entry.date, entry.course, entry.location).filter(String::isNotBlank).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant); entry.champion?.let { Text("Champion · $it", style = MaterialTheme.typography.labelLarge) }; Text(listOfNotNull(entry.purse?.let { "Purse $it" }, entry.points?.let { "$it points" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium) } }; if (entries.isEmpty()) EmptyState("Season schedule unavailable", "No schedule has been published for this season.") } }
                }
                else -> item { GolfCup(model, year) }
            }
        }
    }
}

@Composable
fun GolfGame(match: Match, model: MobileViewModel) {
    AsyncSection(match.key, { model.container.pga.resolve(match) }) { id -> if (id == null) EmptyState("Tournament not identified", "Choose a tournament from the PGA Tour calendar. ESPN ids are never reused as PGA ids.", "PGA Tour", { model.open(PhonePage.LeagueHub(match.league)) }) else GolfCentre(id, model) }
}

@Composable
fun GolfCentre(id: String, model: MobileViewModel) {
    var tournament by remember(id) { mutableStateOf<GolfTournament?>(null) }
    var board by remember(id) { mutableStateOf(emptyList<GolfRow>()) }
    var metadataError by remember(id) { mutableStateOf<String?>(null) }
    var boardError by remember(id) { mutableStateOf<String?>(null) }
    var checked by remember(id) { mutableLongStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable(id) { mutableStateOf("Overview") }
    var player by remember(id) { mutableStateOf<GolfRow?>(null) }
    val prefs by model.library.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(id, lifecycle, retry) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                launch(Dispatchers.IO) {
                    try { tournament = model.container.pga.tournaments(listOf(id)).firstOrNull { it.id == id }; metadataError = if (tournament == null) "Tournament metadata hasn't been published." else null }
                    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { metadataError = "Tournament metadata is unavailable. Try again shortly." }
                }
                launch(Dispatchers.IO) {
                    do {
                        try { val incoming = model.container.pga.leaderboard(id); board = PgaMapper.merge(board, incoming); checked = System.currentTimeMillis(); boardError = null }
                        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { boardError = "Leaderboard refresh failed. The last received scores remain visible." }
                        val status = tournament?.status
                        if (status in setOf("Final", "Cancelled")) break
                        delay(if (status == "Live") 30000 else if (status == "Suspended") 60000 else 300000)
                    } while (isActive)
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Logo(tournament?.logo, "PGA", 52.dp)
                Column(Modifier.weight(1f)) { Text("PGA TOUR", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(tournament?.name ?: "Tournament", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            }
            tournament?.let { t -> Text(listOfNotNull(t.displayDate, t.location).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant); Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatusPill(t.status, t.live); Text(listOfNotNull(t.roundDisplay, t.statusDisplay).distinct().joinToString(" · "), style = MaterialTheme.typography.labelLarge) }; if (t.courses.size > 1) Text("Multi-course event · ${t.courses.joinToString { it.name }}", style = MaterialTheme.typography.labelMedium) }
            val error = metadataError ?: boardError
            if (error != null) Row(verticalAlignment = Alignment.CenterVertically) { Text(error, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); TextButton(onClick = { retry++ }) { Text("Retry") } }
            if (checked > 0) Text("PGA TOUR · checked ${clockTime(checked)}${if (boardError != null) " · stale" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        tournament?.let { MatchWatchBar(model.container.pga.match(it), model) }
        UnderlineTabs(listOf("Overview", "Leaderboard", "Following", "Course", "Tee times", "FedExCup", "Coverage"), tab) { tab = it }
        when (tab) {
            "Leaderboard", "Following" -> ScreenList {
                val rows = if (tab == "Following") board.filter { it.player.id in prefs.followedGolfers } else board
                item { Text("POS     PLAYER                         TOTAL   TODAY   THRU", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.player.id }) { row -> GolfLeaderboardRow(row, row.player.id in prefs.followedGolfers, { model.library.update { pref -> pref.copy(followedGolfers = if (row.player.id in pref.followedGolfers) pref.followedGolfers - row.player.id else pref.followedGolfers + row.player.id) } }) { player = row } }
                if (rows.isEmpty()) item { EmptyState(if (tab == "Following") "Follow golfers" else "Leaderboard unavailable", if (tab == "Following") "Tap the star beside golfers on the leaderboard to follow their rounds." else "Published leaderboard scores will appear here.") }
            }
            "Course" -> ScreenList { item { AsyncSection(id to "courses", { model.container.pga.courses(id).ifEmpty { tournament?.courses.orEmpty() } }) { courses -> courses.forEach { course -> GolfCoursePanel(id, course, model) }; if (courses.isEmpty()) EmptyState("Course unavailable", "The tour has not published course details.") } } }
            "Tee times" -> GolfTeeTimes(id, model)
            "FedExCup" -> ScreenList { item { GolfCup(model, tournament?.year ?: Calendar.getInstance().get(Calendar.YEAR)) } }
            "Coverage" -> ScreenList { item { AsyncSection(id to "coverage", { model.container.pga.coverage(id) }) { windows ->
                if (windows.isEmpty()) EmptyState("Coverage unavailable", "No broadcast windows have been published. Connect your own playlist to watch supported channels.")
                windows.forEach { window -> PhoneCard { Text(window["streamTitle"].stringOrNull() ?: window["channelTitle"].string("Broadcast"), fontWeight = FontWeight.Bold); Text(window["channelTitle"].string()); Text(listOfNotNull(golfTime(window["startTime"]), golfTime(window["endTime"])).joinToString(" – "), style = MaterialTheme.typography.labelMedium); Text("Broadcast information only · playback uses your playlist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } } }
            else -> ScreenList {
                item { SectionTitle("Leaders") }
                items(board.take(5), key = { it.player.id }) { row -> GolfLeaderboardRow(row, row.player.id in prefs.followedGolfers, null) { player = row } }
                if (board.isEmpty()) item { EmptyState("Waiting for scores", "No published leaderboard has been received.") }
                tournament?.let { t -> item { PhoneCard { Text("Tournament", style = MaterialTheme.typography.titleMedium); t.format?.let { Text("Format · ${it.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}") }; Text(t.courses.joinToString { it.name }); t.site?.let { site -> val context = LocalContext.current; TextButton(onClick = { openBrowser(context, site) }) { Text("Tournament website") } } } } }
                item { AsyncSection(id to "weather", { model.container.pga.weather(id) }) { weather -> GolfWeatherPanel(tournament?.weather, weather) } }
            }
        }
    }
    player?.let { selected -> ModalBottomSheet(onDismissRequest = { player = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { Box(Modifier.fillMaxHeight(.92f)) { key(selected.player.id) { GolfPlayerPanel(id, selected, model) } } } }
}

@Composable
private fun GolfLeaderboardRow(row: GolfRow, followed: Boolean, toggle: (() -> Unit)?, click: () -> Unit) {
    PhoneCard(onClick = click) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.position ?: "–", Modifier.width(30.dp), fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) { Text(row.player.name, fontWeight = FontWeight.SemiBold); Text(listOfNotNull(row.player.country, row.status.takeIf(String::isNotBlank), row.movementLabel).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(golfScore(row.total), Modifier.width(36.dp), color = golfScoreColour(row.total), fontWeight = FontWeight.Bold)
            Text(golfScore(row.today), Modifier.width(32.dp), style = MaterialTheme.typography.labelLarge)
            Text(row.thru ?: "–", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium)
            if (toggle != null) IconButton(onClick = toggle, Modifier.size(48.dp)) { Icon(if (followed) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (followed) "Unfollow ${row.player.name}" else "Follow ${row.player.name}") }
        }
        if (row.rounds.isNotEmpty()) Text(row.rounds.mapIndexed { i, value -> "R${i + 1} $value" }.joinToString("   "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GolfTeeTimes(id: String, model: MobileViewModel) {
    var selected by rememberSaveable(id) { mutableStateOf<Int?>(null) }
    ScreenList { item { AsyncSection(id to "tees", { model.container.pga.teeTimes(id) }) { groups ->
        val rounds = groups.map { it.round }.distinct().sorted(); val round = selected ?: rounds.firstOrNull()
        ChoiceRow(rounds.map { "R$it" }, "R$round") { selected = it.removePrefix("R").toIntOrNull() }
        groups.filter { it.round == round }.forEach { group -> PhoneCard { Text(listOfNotNull(group.teeTime?.let { "${dateLabel(it)} · ${clockTime(it)}" }, group.startingTee?.let { "Tee $it" }, "Group ${group.group}").joinToString(" · "), style = MaterialTheme.typography.labelLarge); group.players.forEach { Text(it.name) } } }
        if (groups.isEmpty()) EmptyState("Tee times unavailable", "The tour has not published player groups for this tournament.")
    } } }
}

@Composable
private fun GolfCoursePanel(id: String, course: GolfCourse, model: MobileViewModel) {
    var selected by remember(course.id) { mutableStateOf<Int?>(null) }
    PhoneCard {
        Text(course.name, style = MaterialTheme.typography.titleLarge)
        Text(listOfNotNull(course.par?.let { "Par $it" }, course.yards?.let { "$it yards" }, "Host course".takeIf { course.host }).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        course.holes.forEach { hole -> Row(Modifier.fillMaxWidth().clickable { selected = hole.number }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { Text("${hole.number}", Modifier.width(30.dp), fontWeight = FontWeight.Bold); Text("Par ${hole.par ?: "–"} · ${hole.yards ?: "–"} yards", Modifier.weight(1f)); Text(hole.average?.let { "%.2f".format(it) } ?: "–"); Icon(Icons.Rounded.ChevronRight, "Hole ${hole.number} details") } }
        if (course.holes.isEmpty()) Text("Hole scoring statistics unavailable", style = MaterialTheme.typography.labelMedium)
    }
    selected?.let { hole -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Hole $hole") }, text = { AsyncSection(Triple(id, course.id, hole), { model.container.pga.hole(id, course.id, hole) }) { detail -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Par ${detail["holeInfo"]["par"].string("–")} · ${detail["holeInfo"]["yards"].string("–")} yards"); detail["holeInfo"]["aboutThisHole"].stringOrNull()?.let { Text(it) }
        listOf("eagles", "birdies", "pars", "bogeys", "doubleBogeys").forEach { key -> detail["statsSummary"][key].stringOrNull()?.let { Text("${key.replaceFirstChar { it.uppercase() }} · $it") } }
        (detail["tourcastURLWeb"].stringOrNull() ?: detail["tourcastURL"].stringOrNull())?.let { url -> val context = LocalContext.current; TextButton(onClick = { openBrowser(context, url) }) { Text("Open official TOURCAST") } }
    } } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Done") } }) }
}

@Composable
private fun GolfPlayerPanel(id: String, row: GolfRow, model: MobileViewModel) {
    var tab by rememberSaveable { mutableStateOf("Scorecard") }
    var round by rememberSaveable { mutableIntStateOf(row.round ?: 1) }
    Column {
        Column(Modifier.padding(horizontal = 18.dp)) { Text(row.player.name, style = MaterialTheme.typography.headlineSmall); Text("${row.position ?: "–"} · ${golfScore(row.total)} · ${row.status}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        UnderlineTabs(listOf("Scorecard", "Shots"), tab) { tab = it }
        ScreenList {
            if (tab == "Scorecard") item { AsyncSection(id to row.player.id, { model.container.pga.scorecard(id, row.player.id) }) { rounds ->
                if (rounds.isEmpty()) EmptyState("Scorecard unavailable", "No hole-by-hole scores have been published for this golfer.")
                rounds.forEach { score -> PhoneCard {
                    Text("Round ${score.round} · ${score.course.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                    Text("${score.total ?: "–"} strokes · ${golfScore(score.scoreToPar)}")
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row { Text("Hole", Modifier.width(48.dp)); score.holes.forEach { Text("${it.hole}", Modifier.width(36.dp)) } }
                        Row { Text("Par", Modifier.width(48.dp)); score.holes.forEach { Text("${it.par ?: "–"}", Modifier.width(36.dp)) } }
                        Row { Text("Score", Modifier.width(48.dp)); score.holes.forEach { Text(it.strokes?.takeIf { n -> n > 0 }?.toString() ?: "–", Modifier.width(36.dp), fontWeight = FontWeight.Bold, color = when { it.delta == null -> MaterialTheme.colorScheme.onSurfaceVariant; it.delta!! < 0 -> BannerLive; it.delta!! > 0 -> Color(0xff83adff); else -> MaterialTheme.colorScheme.onSurface }) } }
                    }
                    val order = score.holes.sortedBy { it.sequence ?: it.hole }
                    if (order.firstOrNull()?.hole != score.holes.firstOrNull()?.hole) Text("Play order: ${order.joinToString { it.hole.toString() }}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { round = score.round; tab = "Shots" }) { Text("Shot details · R${score.round}") }
                } }
            } } else {
                item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = { round-- }, enabled = round > 1) { Icon(Icons.Rounded.ChevronLeft, "Previous golf round") }; Text("Round $round", Modifier.weight(1f)); IconButton(onClick = { round++ }, enabled = round < (row.round ?: row.rounds.size.coerceAtLeast(1))) { Icon(Icons.Rounded.ChevronRight, "Next golf round") } } }
                item { AsyncSection(Triple(id, row.player.id, round), { model.container.pga.shots(id, row.player.id, round) }) { holes ->
                    if (holes.isEmpty()) EmptyState("Shot data unavailable", "Shot details are loaded only for this golfer and selected round.")
                    holes.forEach { hole -> PhoneCard { Text("Hole ${hole.hole} · Par ${hole.par ?: "–"} · ${hole.score ?: "–"}", style = MaterialTheme.typography.titleMedium); GolfShotTrace(hole.strokes); hole.strokes.forEach { shot -> HorizontalDivider(); Text("${shot.stroke}. ${shot.description ?: listOfNotNull(shot.from, shot.to).joinToString(" → ")}", fontWeight = FontWeight.SemiBold); Text(listOfNotNull(shot.distance?.let { "Distance $it" }, shot.remaining?.let { "Remaining $it" }, shot.type).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
                } }
            }
        }
    }
}

@Composable
private fun GolfShotTrace(shots: List<GolfShot>) {
    val useBottom = shots.any { it.bottomFrom != null || it.bottomTo != null }
    val pairs = shots.mapNotNull { shot -> val from = if (useBottom) shot.bottomFrom else shot.leftFrom; val to = if (useBottom) shot.bottomTo else shot.leftTo; if (from == null || to == null) null else from to to }
    if (pairs.isEmpty()) { Text("Shot coordinates unavailable", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val points = pairs.flatMap { listOf(it.first, it.second) }; val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }; val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val colour = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(200.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        val scale = minOf((size.width - 48) / (maxX - minX).coerceAtLeast(.001), (size.height - 48) / (maxY - minY).coerceAtLeast(.001))
        fun xy(p: GolfPoint) = Offset((24 + (p.x - minX) * scale).toFloat(), (size.height - 24 - (p.y - minY) * scale).toFloat())
        pairs.forEach { (from, to) -> drawLine(colour.copy(alpha = .6f), xy(from), xy(to), strokeWidth = 3f); drawCircle(colour, 5f, xy(to)) }
    }
    Text("Schematic shot trace · provider coordinate space, not a geographic course map. Distances retain the provider's units.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GolfCup(model: MobileViewModel, year: Int) {
    AsyncSection(year to "golf-cup", { model.container.pga.cup(year) }) { rows ->
        StatTable("FedExCup · $year", listOf("Golfer", "Official", "Projected", "Points"), rows.map { row -> listOf(row["displayName"].stringOrNull() ?: listOf(row["firstName"].string(), row["lastName"].string()).joinToString(" "), row["rankingData"]["official"].string("–"), row["rankingData"]["projected"].string("–"), row["pointData"]["projected"].stringOrNull() ?: row["pointData"]["official"].string("–")) })
        if (rows.isEmpty()) EmptyState("Cup standings unavailable", "No official or projected standings have been published.")
    }
}
@Composable
private fun GolfWeatherPanel(current: kotlinx.serialization.json.JsonElement?, forecast: kotlinx.serialization.json.JsonElement?) {
    if (current == null && forecast == null) return
    PhoneCard {
        Text("Weather", style = MaterialTheme.typography.titleMedium)
        current?.let { Text(listOfNotNull(it["tempC"].stringOrNull()?.let { v -> "$v °C" }, it["condition"].stringOrNull(), it["windSpeedKPH"].stringOrNull()?.let { v -> "Wind $v km/h" }).joinToString(" · ")) }
        (forecast["hourly"].array.take(4) + forecast["daily"].array.take(3)).forEach { reading -> Text(listOfNotNull(reading["title"].stringOrNull(), reading["condition"].stringOrNull(), (reading["temperature"]["tempC"] ?: reading["temperature"]["maxTempC"]).stringOrNull()?.let { "$it °C" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium) }
    }
}
private fun golfTime(raw: kotlinx.serialization.json.JsonElement?): String? = raw.stringOrNull()?.let { text -> val time = text.toDoubleOrNull()?.takeIf { it > 0 }?.toLong() ?: com.alexdiab.stadiatv.sports.f1.F1Wire.utc(text); time?.let { "${dateLabel(it)} ${clockTime(it)}" } }
private fun golfScore(raw: String?): String = when (raw?.trim()) { "0", "+0", "-0" -> "E"; null, "" -> "–"; else -> raw }
@Composable private fun golfScoreColour(raw: String?): Color = if ((raw?.toIntOrNull() ?: 0) < 0) BannerLive else MaterialTheme.colorScheme.onSurface
