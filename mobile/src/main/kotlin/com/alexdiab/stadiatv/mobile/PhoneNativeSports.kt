package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.*
import kotlinx.coroutines.launch

@Composable
fun BaseballSituation(game: BaseballDetail, match: Match, model: MobileViewModel, boxes: List<TeamBox> = emptyList()) {
    PhoneCard {
        Overline(listOfNotNull(game.half.takeIf(String::isNotBlank), game.inning?.toString()).joinToString(" "))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val baseColor = MaterialTheme.colorScheme.primary
            val empty = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.size(110.dp).semantics { contentDescription = game.baseDescription }) {
                val p = listOf(Offset(size.width * .78f, size.height * .5f), Offset(size.width * .5f, size.height * .22f), Offset(size.width * .22f, size.height * .5f), Offset(size.width * .5f, size.height * .78f))
                val path = Path().apply { moveTo(p[0].x, p[0].y); p.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(path, empty, style = Stroke(2.dp.toPx()))
                p.forEachIndexed { index, point ->
                    val s = 8.dp.toPx(); val diamond = Path().apply { moveTo(point.x, point.y - s); lineTo(point.x + s, point.y); lineTo(point.x, point.y + s); lineTo(point.x - s, point.y); close() }
                    drawPath(diamond, if (index < 3 && game.bases.getOrNull(index) != null) baseColor else empty)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (game.betweenInnings) "Between innings" else "${game.balls ?: "–"} balls · ${game.strikes ?: "–"} strikes", style = MaterialTheme.typography.titleMedium)
                Text("${game.outs ?: "–"} outs", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(game.baseDescription, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!game.betweenInnings) {
            game.batter?.let { BaseballPlayerMatchup("At bat", it, match, model); baseballMatchupLine(it, boxes, false)?.let { line -> Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            game.pitcher?.let { BaseballPlayerMatchup("Pitching", it, match, model); baseballMatchupLine(it, boxes, true)?.let { line -> Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
fun BaseballProbables(game: BaseballDetail, match: Match, model: MobileViewModel) {
    if (game.probableAway == null && game.probableHome == null) return
    PhoneCard {
        Text("Probable pitchers", style = MaterialTheme.typography.titleMedium)
        game.probableAway?.let { BaseballPlayerMatchup(match.away.abbreviation, it, match, model) }
        game.probableHome?.let { BaseballPlayerMatchup(match.home.abbreviation, it, match, model) }
    }
}

@Composable
private fun BaseballPlayerMatchup(label: String, player: SportPlayer, match: Match, model: MobileViewModel) {
    val id = "mlb:${player.id}"
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
        model.open(PhonePage.PlayerDetail(match.league, Athlete(id, player.name, player.number, player.position)))
    }.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(player.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Icon(Icons.Rounded.ChevronRight, "Player details", Modifier.size(18.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun BaseballAtBats(game: BaseballDetail?, match: Match, model: MobileViewModel) {
    var inning by rememberSaveable { mutableStateOf("All innings") }
    var scoring by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val threshold = with(LocalDensity.current) { 60.dp.roundToPx() }
    val atEdge by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < threshold } }
    var window by remember(match.key) { mutableStateOf(BaseballTimelineWindow()) }
    LaunchedEffect(game?.atBats, atEdge) { window = window.update(game?.atBats.orEmpty(), atEdge) }
    val rows = window.displayed.asReversed()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            ChoiceRow(listOf("All innings") + rows.map { "${it.inning}" }.distinct().asReversed(), inning) { inning = it; scope.launch { list.scrollToItem(0) } }
            FilterChip(scoring, { scoring = !scoring; scope.launch { list.scrollToItem(0) } }, label = { Text("Scoring only") })
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val filtered = rows.filter { (inning == "All innings" || inning == "${it.inning}") && (!scoring || it.scoring) }
                if (filtered.isEmpty()) item("empty") { EmptyState(if (rows.isEmpty()) "No at bats available" else "No matching at bats", if (match.state == GameState.PRE) "Play-by-play will appear when the game begins." else "Only reported MLB plate appearances are shown. Try All innings or turn off Scoring only.") }
                filtered.groupBy { "${it.half} ${it.inning}" }.forEach { (label, plays) ->
                    stickyHeader(key = "inning-$label") { Surface(color = MaterialTheme.colorScheme.background) { Text(label.uppercase(), Modifier.fillMaxWidth().padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge) } }
                    items(plays, key = { it.id }) { BaseballAtBatCard(it, match, model) }
                }
            }
            if (!atEdge) Button(onClick = {
                window = window.update(game?.atBats.orEmpty(), true)
                scope.launch { list.scrollToItem(0) }
            }, Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Icon(Icons.Rounded.ArrowUpward, null); Spacer(Modifier.width(8.dp))
                Text(if (window.deferredIds.isNotEmpty()) "${window.deferredIds.size} new at bats" else if (match.state == GameState.LIVE) "Jump to live" else "Jump to latest")
            }
        }
    }
}

@Composable
fun BaseballAtBatCard(atBat: BaseballAtBat, match: Match, model: MobileViewModel) {
    var expanded by rememberSaveable(match.key, atBat.id) { mutableStateOf(false) }
    PhoneCard {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Overline("${atBat.half} ${atBat.inning}")
                Text(if (atBat.complete) atBat.title else "At bat", color = if (atBat.scoring) BannerLive else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
            }
            IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Collapse pitch details" else "Expand pitch details") }
        }
        atBat.batter?.let { BaseballPlayerMatchup("Batter", it, match, model) }
        Text(atBat.description.ifBlank { "At bat in progress" }, style = MaterialTheme.typography.bodyMedium)
        if (atBat.scoring) atBat.runners.filter { it.scored }.forEach { runner -> Text("${runner.player?.name ?: "Runner"} scores", style = MaterialTheme.typography.bodyMedium) }
        baseballContactLine(atBat)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(listOfNotNull("${atBat.pitches.size} ${if (atBat.pitches.size == 1) "pitch" else "pitches"}", (atBat.events.size - atBat.pitches.size).takeIf { it > 0 }?.let { "$it ${if (it == 1) "action" else "actions"}" }, atBat.rbi?.takeIf { it > 0 }?.let { "$it RBI" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (atBat.awayScore != null && atBat.homeScore != null) Text("${match.away.abbreviation} ${atBat.awayScore} – ${atBat.homeScore} ${match.home.abbreviation}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        if (expanded) {
            atBat.pitcher?.let { BaseballPlayerMatchup("Pitcher", it, match, model) }
            PitchChart(atBat.pitches)
            atBat.events.forEach { event ->
                val pitch = event.pitch
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (pitch == null) "•" else pitch.number?.toString() ?: "–", Modifier.width(24.dp), fontWeight = FontWeight.Bold, color = if (pitch?.inPlay == true) BannerGreen else if (pitch?.strike == true) BannerLive else MaterialTheme.colorScheme.primary)
                    Column {
                        Text(pitch?.type?.takeIf(String::isNotBlank) ?: event.description.ifBlank { event.type.replaceFirstChar(Char::uppercase) }, style = MaterialTheme.typography.labelLarge)
                        val details = listOfNotNull(pitch?.speed?.let { "$it mph" }, pitch?.spin?.let { "$it rpm" }, event.description.takeIf { pitch != null && it.isNotBlank() },
                            if (event.balls != null && event.strikes != null) "${event.balls}–${event.strikes} count" else null, event.outs?.let { "$it outs" })
                        if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            atBat.runners.filter { !it.scored }.forEach { runner ->
                val movement = if (runner.isOut) "out${runner.outBase?.let { " at $it" }.orEmpty()}" else runner.end?.let { "to $it" }
                if (movement != null) Text(listOfNotNull("${runner.player?.name ?: "Runner"} $movement", runner.reason).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BaseballBattingLeaders(leaders: List<BaseballBattingLeader>, match: Match, model: MobileViewModel) {
    PhoneCard {
        Text("Batting leaders", style = MaterialTheme.typography.titleMedium)
        leaders.forEach { leader ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { model.open(PhonePage.PlayerDetail(match.league, leader.player)) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Logo(leader.player.headshotUrl, leader.player.name, 42.dp)
                Column(Modifier.weight(1f)) { Text(leader.player.name, style = MaterialTheme.typography.titleSmall); Text("${leader.team} · ${leader.line}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Rounded.ChevronRight, "Player details")
            }
        }
    }
}

@Composable
private fun PitchChart(pitches: List<BaseballPitch>) {
    val located = pitches.filter { it.location != null }
    if (located.isEmpty()) { Text("Pitch locations were not supplied.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    val outline = MaterialTheme.colorScheme.outline; val textColor = android.graphics.Color.WHITE
    Canvas(Modifier.fillMaxWidth().height(220.dp).background(Color(0xFF10201B), RoundedCornerShape(12.dp)).semantics { contentDescription = "Strike zone with ${located.size} measured pitch locations. Numbered pitch descriptions follow." }) {
        fun point(x: Float, z: Float) = Offset((x + 2.5f) / 5f * size.width, (5 - z) / 5f * size.height)
        val zone = located.last().location!!; val left = point(-.708f, zone.top); val right = point(.708f, zone.bottom)
        drawRect(outline, left, Size(right.x - left.x, right.y - left.y), style = Stroke(2.dp.toPx()))
        for (fraction in listOf(1f / 3, 2f / 3)) { val x = left.x + (right.x - left.x) * fraction; val y = left.y + (right.y - left.y) * fraction; drawLine(outline.copy(alpha = .35f), Offset(x, left.y), Offset(x, right.y)); drawLine(outline.copy(alpha = .35f), Offset(left.x, y), Offset(right.x, y)) }
        located.forEach { pitch ->
            val location = pitch.location!!
            if (location.x !in -2.5f..2.5f || location.z !in 0f..5f) return@forEach
            val p = point(location.x, location.z)
            drawCircle(if (pitch.inPlay) BannerGreen else if (pitch.strike) BannerLive else Color(0xFF438CF8), 11.dp.toPx(), p)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textAlign = android.graphics.Paint.Align.CENTER; textSize = 11.dp.toPx(); isFakeBoldText = true }
            drawContext.canvas.nativeCanvas.drawText(pitch.number?.toString() ?: "·", p.x, p.y + 4.dp.toPx(), paint)
        }
    }
    Text("Measured plate position · batter's view · balls outside the viewport remain in the list", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FootballFieldView(match: Match, field: FootballField) {
    PhoneCard {
        val possession = when { field.possessionTeamId == null -> null; field.possessionTeamId == match.home.teamId -> match.home; field.possessionTeamId == match.away.teamId -> match.away; else -> null }
        Overline("Possession${(possession?.abbreviation ?: field.possessionAbbreviation)?.let { " · $it" }.orEmpty()}")
        Text(listOfNotNull(field.down?.let { listOf("1st", "2nd", "3rd", "4th").getOrNull(it - 1) }, if (field.goalToGo) "& GOAL" else field.yardsToFirst?.let { "& $it" }, field.yardLine.takeIf(String::isNotBlank)).joinToString(" "), style = MaterialTheme.typography.titleLarge)
        val yard = field.yardsFromOwnGoal
        if (yard != null) Canvas(Modifier.fillMaxWidth().height(130.dp).background(Color(0xFF16412D), RoundedCornerShape(10.dp)).semantics { contentDescription = "Ball ${yard.toInt()} yards from the possession team's own goal line. ${field.yardsToFirst ?: "Unknown"} yards to first down." }) {
            val margin = size.width * .08f; val width = size.width - 2 * margin
            for (n in 0..10) { val x = margin + width * n / 10; drawLine(Color.White.copy(alpha = .55f), Offset(x, 10.dp.toPx()), Offset(x, size.height - 10.dp.toPx()), if (n % 5 == 0) 2.dp.toPx() else 1.dp.toPx()) }
            val line = margin + width * yard / 100
            drawLine(Color(0xFF64A5FF), Offset(line, 0f), Offset(line, size.height), 3.dp.toPx())
            field.yardsToFirst?.let { distance -> val target = margin + width * (yard + distance).coerceIn(0f, 100f) / 100; drawLine(Color(0xFFFFD36E), Offset(target, 0f), Offset(target, size.height), 2.dp.toPx()) }
            drawCircle(Color.White, 5.dp.toPx(), Offset(line, size.height / 2))
        } else Text("Exact field position was not supplied.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Blue: line of scrimmage · Gold: first down · direction of possession →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SoccerLineups(match: Match, detail: SoccerDetail?, model: MobileViewModel, nativeEpl: Boolean) {
    SoccerLineupsScreen(match, detail, model, nativeEpl)
}

@Composable
fun SoccerCommentary(detail: SoccerDetail?, gameKey: String, loadOlder: () -> Unit) {
    SoccerCommentaryFeed(detail, gameKey, loadOlder)
}

@Composable
fun SoccerLiveTable(match: Match, snapshot: PhoneGameSnapshot, model: MobileViewModel) {
    ScreenList {
        if (match.league.path in setOf("soccer/eng.1", "soccer/usa.1", "soccer/esp.1") && (match.league.path != "soccer/esp.1" || match.nativeReference?.provider == "LaLiga")) {
            val table = snapshot.soccerTable
            item {
                SectionTitle(if (table?.provisional == true) "Live table · provisional" else "${match.league.shortName} table")
                Text("Published by ${match.league.name}, not calculated from the event feed. Live positions can change; the provider's published points are retained.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(table?.let { "${if (match.league.path == "soccer/usa.1") "${it.season}" else "${it.season}/${(it.season + 1).toString().takeLast(2)}"} · ${if (it.provisional) "Live" else "Published"} · Checked ${snapshotTime(it.checkedAt)}" } ?: "Native table not loaded", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); TextButton(onClick = { model.loadSoccerTable(match, true) }, enabled = !snapshot.soccerTableLoading) { Text("Refresh") } }
                if (snapshot.soccerTableLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                snapshot.soccerTableError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (table != null) item {
                for (group in table.groups.ifEmpty { listOf(SoccerTableGroup(if (table.provisional) "Provisional standings" else "Published standings", table.rows)) })
                    StatTable(group.label, listOf("Club", "P", "W", "D", "L", "GF", "GA", "Pts"), group.rows.map { row -> listOf("${row.position?.toString() ?: "–"}. ${row.name}", row.played?.toString() ?: "–", row.won?.toString() ?: "–", row.drawn?.toString() ?: "–", row.lost?.toString() ?: "–", row.goalsFor?.toString() ?: "–", row.goalsAgainst?.toString() ?: "–", row.points?.toString() ?: "–") })
                table.rows.filter { it.deduction != null }.forEach { Text("${it.name} · reported deduction ${it.deduction} points", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (table.rows.isEmpty()) EmptyState("No published rows", "The provider returned no standings for this season.")
            } else if (!snapshot.soccerTableLoading) item { EmptyState("Native table unavailable", "Use Refresh to retry. Match events, statistics and lineups remain independent.") }
        } else {
        item { Text("Provider standings", style = MaterialTheme.typography.titleLarge); Text("Published positions and points. Provisional results are not calculated from an incomplete event feed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { AsyncSection(match.league.path, { model.container.sports.standings(match.league) }) { groups -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { groups.forEach { group -> StatTable(group.name, listOf("Team", "Record"), group.rows.mapIndexed { index, row -> listOf("${index + 1}. ${row.teamName}", row.record) }) } } } }
        }
    }
}

@Composable
fun SoccerShootout(match: Match, kicks: List<ShootoutKick>) {
    PhoneCard { Overline("Penalty shootout"); for (home in listOf(false, true)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { Text(if (home) match.home.abbreviation else match.away.abbreviation, Modifier.width(48.dp), fontWeight = FontWeight.Bold); kicks.filter { it.home == home }.sortedBy { it.order }.forEach { kick -> Icon(if (kick.scored == true) Icons.Rounded.CheckCircle else if (kick.scored == false) Icons.Rounded.Cancel else Icons.Rounded.RadioButtonUnchecked, "${kick.player}: ${if (kick.scored == true) "scored" else if (kick.scored == false) "missed" else "pending"}", Modifier.size(22.dp), tint = if (kick.scored == true) BannerGreen else if (kick.scored == false) BannerLive else MaterialTheme.colorScheme.outline) } } }
}

@Composable
fun BasketballHeaderContext(match: Match, detail: BasketballDetail) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((index, entry) in listOf(Triple(match.away, detail.awayTimeouts, detail.awayBonus), Triple(match.home, detail.homeTimeouts, detail.homeBonus)).withIndex()) {
            if (index == 1) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                match.broadcasts.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.widthIn(max = 140.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                entry.second?.takeIf { it in 0..10 }?.let { remaining -> Text("${entry.first.abbreviation} · $remaining TO", style = MaterialTheme.typography.labelSmall) }
                if (entry.third == true) Text("${entry.first.abbreviation} BONUS", style = MaterialTheme.typography.labelSmall, color = BannerGreen)
            }
        }
    }
}

@Composable
fun BasketballSituation(match: Match, detail: BasketballDetail) {
    PhoneCard {
        Overline("Court status")
        for ((side, timeouts, bonus) in listOf(Triple(match.away, detail.awayTimeouts, detail.awayBonus), Triple(match.home, detail.homeTimeouts, detail.homeBonus))) Row { Text(side.abbreviation, Modifier.weight(1f), fontWeight = FontWeight.Bold); timeouts?.let { Text("$it timeouts", style = MaterialTheme.typography.labelMedium) }; if (bonus == true) { Spacer(Modifier.width(12.dp)); Text("BONUS", color = BannerLive, style = MaterialTheme.typography.labelMedium) } }
        if (detail.onCourt.isNotEmpty()) { Text("On court", style = MaterialTheme.typography.labelLarge); Text(detail.onCourt.joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun GameVideos(videos: List<GameVideo>, model: MobileViewModel) {
    var selected by remember { mutableStateOf<GameVideo?>(null) }
    PhoneCard { Overline("Highlights & replays"); videos.forEach { video -> TextButton(onClick = { model.controller.player.pause(); PhoneAudioService.pauseActive(); selected = video }) { Icon(Icons.Rounded.PlayCircle, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(video.title, Modifier.weight(1f)) } } }
    selected?.let { PhoneHighlightPlayer(it) { selected = null } }
}

@Composable
fun RelatedGameNews(match: Match, model: MobileViewModel) {
    AsyncSection(match.key, {
        val names = listOf(match.away.shortName, match.home.shortName, match.away.displayName, match.home.displayName).map { it.lowercase() }.filter { it.length >= 3 }
        model.container.news.forLeague(match.league, 50).filter { article -> names.any { name -> "${article.headline} ${article.description}".lowercase().contains(name) } }.take(4)
    }) { stories -> if (stories.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { SectionTitle("Around this matchup"); stories.forEach { StoryCard(it, model) } } }
}
