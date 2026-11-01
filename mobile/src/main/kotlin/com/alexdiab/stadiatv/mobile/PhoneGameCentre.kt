package com.alexdiab.stadiatv.mobile

import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.StreamAvailabilityStore
import com.alexdiab.stadiatv.sports.NativeGameCentres
import com.alexdiab.stadiatv.sports.NativeHockeyEvents
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@Composable
fun GameDetailScreen(initial: Match, model: MobileViewModel, companion: Boolean = false) {
    if (initial.league.path == "racing/f1") { FormulaGame(initial, model); return }
    if (initial.league.path == "golf/pga") { GolfGame(initial, model); return }
    val feed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val preferences by model.container.preferences.settings.collectAsStateWithLifecycle()
    val snapshots by model.games.collectAsStateWithLifecycle()
    val billing by model.entitlements.state.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val rawSnapshot = snapshots[initial.key] ?: PhoneGameSnapshot()
    val snapshot = rawSnapshot.copy(data = NativeGameCentres.overlay(rawSnapshot.data, rawSnapshot.nativeData))
    val lifecycle = LocalLifecycleOwner.current
    val footballNow by produceState(System.currentTimeMillis(), initial.key, lifecycle) {
        if (initial.league.path == "football/nfl") lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(10_000) }
        }
    }
    val scheduledMatch = feed.all.firstOrNull { it.key == initial.key } ?: initial
    val match = snapshot.data.liveMatch?.takeIf { native ->
        native.state.ordinal >= scheduledMatch.state.ordinal && (snapshot.data.providerStamp?.final == true || snapshot.data.football != null || snapshot.data.basketball != null || snapshot.data.soccer != null || connection.known && !connection.online || System.currentTimeMillis() - (snapshot.data.providerStamp?.requestedAt ?: 0) < 90_000)
    } ?: scheduledMatch
    val latestMatch by rememberUpdatedState(match)
    var selection by rememberSaveable(match.key) { mutableStateOf("Overview") }
    var revealed by rememberSaveable(match.key) { mutableStateOf(false) }
    val hidden = preferences.spoilerFreeMode && match.state == GameState.FINAL && !revealed
    val nativeShots = match.league.path in setOf("basketball/nba", "basketball/wnba", "hockey/nhl")
    val tabs = gameTabs(match.league.group, snapshot.data, nativeShots, match.nativeReference?.provider in setOf("EPL", "MLS"), match.nativeReference?.provider == "LaLiga")
    LaunchedEffect(match.key, tabs) { if (selection !in tabs) selection = "Overview" }
    LaunchedEffect(match.key, lifecycle, selection, connection) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var firstRefresh = true
            while (true) {
                val activeMatch = latestMatch
                val before = model.games.value[activeMatch.key]
                if (connection.known && !connection.online && before?.nativeData != null) break
                model.loadGame(activeMatch, force = firstRefresh && connection.online && (before?.nativeFailureCount ?: 0) > 0,
                    spatial = selection.startsWith("Shot"), insights = selection == "Team stats", tab = selection)
                firstRefresh = false
                if (selection in setOf("Live table", "League table")) model.loadSoccerTable(activeMatch)
                else if (selection == "Overview" && activeMatch.league.path in setOf("soccer/eng.1", "soccer/usa.1", "soccer/esp.1") && activeMatch.state == GameState.LIVE) model.loadSoccerTable(activeMatch, overview = true)
                model.container.streams.ensure(activeMatch, model.container.playlists.state.value.channels, { model.container.guide.linkerProgrammes() }, model.container.guide::guideIdFor, model.container.guide.state.value.revision)
                if (activeMatch.league.path in setOf("football/nfl", "basketball/nba", "basketball/wnba", "soccer/eng.1", "soccer/usa.1", "soccer/esp.1")) withTimeoutOrNull(if (activeMatch.nativeReference?.provider == "LaLiga") 31_000 else 16_000) { model.games.first { it[activeMatch.key]?.nativeRefreshing != true } }
                if (connection.known && !connection.online) break
                val refreshed = model.games.value[activeMatch.key]
                val interval = phoneGamePollDelay(refreshed?.nativeData?.liveMatch ?: latestMatch, System.currentTimeMillis(), refreshed?.nativeFailureCount ?: 0, refreshed?.nativeData) ?: break
                delay(interval)
            }
        }
    }
    PhoneGameCentreLayout(expanded = !companion, hasSecondary = !hidden, primary = {
        if (!companion) MatchScoreHero(match, hidden, model, phoneNflWeek(match, snapshot.data))
        else CompactMatchHeader(match, hidden)
        if (hidden) {
            EmptyState("Final score hidden", "Reveal this game's score without changing your spoiler preference.", "Reveal final score", { revealed = true })
        }
        if (!companion && match.state == GameState.LIVE && match.statusDetail !in setOf("Halftime", "Delayed", "Suspended"))
            snapshot.data.basketball?.let { BasketballHeaderContext(match, it) }
        if (!companion && match.state != GameState.FINAL) MatchWatchBar(match, model)
    }, secondary = {
      if (!hidden) Column(Modifier.fillMaxSize()) {
        UnderlineTabs(tabs, selection) { selection = it }
        if (snapshot.refreshing && snapshot.data.isEmpty) LinearProgressIndicator(Modifier.fillMaxWidth())
        val status = phoneMatchDataStatus(match, snapshot, selection, connection.known && !connection.online,
            phoneNflLiveDataDelayed(match, snapshot.data, footballNow) && snapshot.data.football?.savedSnapshot != true && (!connection.known || connection.online))
        MatchDataStatus(match.key, status) { retry ->
            if (retry.startsWith("baseball:")) model.retryBaseballExtras(match, retry.removePrefix("baseball:"))
            else model.loadGame(match, force = true, spatial = selection.startsWith("Shot"), insights = selection == "Team stats", tab = selection)
        }
        Box(Modifier.weight(1f)) {
            if (!billing.isPremium) ScreenList {
                if (match.league.path == "football/nfl") item("calendar") { NflGameCalendarLink(match, snapshot.data, model) }
                item("premium") { PhonePremiumGate("Game Centre", model) }
                item("related") { RelatedGameNews(match, model) }
            } else when (selection) {
                "Plays", "Events", "Timeline" -> GamePlays(match, snapshot.data, model)
                "At bats" -> BaseballAtBats(snapshot.data.baseball, match, model)
                "Lineups" -> SoccerLineups(match, snapshot.data.soccer, model, snapshot.data.providerStamp?.provider == "Premier League")
                "Commentary" -> SoccerCommentary(snapshot.data.soccer, match.key) { model.olderCommentary(match) }
                "Live table", "League table" -> SoccerLiveTable(match, snapshot, model)
                "Shots" -> SoccerShotsScreen(match, snapshot.data.soccer)
                "Box score" -> GameBoxScore(match, snapshot.data, model)
                "Team stats" -> if (snapshot.data.soccer != null) SoccerStatsScreen(match, snapshot.data, model) else ScreenList { if (snapshot.data.baseball != null) snapshot.data.lineScore?.let { line -> item("line") { BaseballLineScore(line) } }; item { ComparisonStats(match, snapshot.data.teamStats, snapshot.data.teamStatsSource) }; if (snapshot.data.winProbability.isNotEmpty()) item { ProbabilityChart(match, snapshot.data.winProbability, snapshot.data.winProbabilitySource, snapshot.data.baseballProbability) } else if (match.league.path == "baseball/mlb") item { EmptyState("Win probability unavailable", "Only values supplied by MLB or the summary provider are shown.") } }
                "Shot chart", "Shot map" -> ShotChart(match, snapshot.data)
                "Drives" -> ScreenList {
                    items(snapshot.data.drives.asReversed(), key = { it.id }) { drive -> PhoneCard {
                        Row { StatusPill(drive.team); Spacer(Modifier.weight(1f)); Text(drive.result, fontWeight = FontWeight.Bold) }
                        Text(drive.description, style = MaterialTheme.typography.bodyMedium)
                        Text(listOf("${drive.plays} plays", drive.yards.takeIf(String::isNotBlank)?.let { "$it yards" }, drive.duration.takeIf(String::isNotBlank)).filterNotNull().joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    } }
                }
                else -> GameOverview(match, snapshot, model) { selection = it }
            }
        }
      }
    })
}

@Composable
fun UnderlineTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    val state = rememberLazyListState()
    var viewportWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    LaunchedEffect(tabs, selected, viewportWidth, density.fontScale, density.density, direction) {
        val index = tabs.indexOf(selected)
        // Position on the NEXT measure, when changed font/viewport geometry is known. Do not key this
        // to scrolling itself: a user must still be free to browse the other tabs without snapping back.
        // https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState
        if (index >= 0 && viewportWidth > 0) state.requestScrollToItem(index)
    }
    Column(Modifier.onSizeChanged { viewportWidth = it.width }) {
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(tabs, key = { it }) { title ->
                Column(Modifier.width(IntrinsicSize.Max).semantics { this.selected = title == selected }.clickable(role = androidx.compose.ui.semantics.Role.Tab) { onSelect(title) }.heightIn(min = 48.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, Modifier.padding(top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected == title) FontWeight.Bold else FontWeight.Normal, color = if (selected == title) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected == title) MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun MatchDataStatus(gameKey: String, status: PhoneMatchDataStatus, retry: (String) -> Unit) {
    var open by rememberSaveable(gameKey) { mutableStateOf(false) }
    val empty = status.notices.isEmpty() && status.provenance.isEmpty() && status.freshness == null
    // A successful retry may remove the final notice. Keep its disclosure until explicit dismissal
    // so a later failure cannot unexpectedly reopen a remembered dialog.
    if (empty && !open) return
    val urgent = status.urgentNotice
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        if (urgent != null) Surface(color = PhoneDesign.colors.warning.container, shape = PhoneShape.control) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, null, tint = PhoneDesign.colors.warning.foreground, modifier = Modifier.size(18.dp))
                Text(urgent.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = PhoneDesign.colors.warning.foreground)
                urgent.retry?.let { action -> TextButton(onClick = { retry(action) }, Modifier.heightIn(min = 48.dp)) {
                    Text("Retry", color = PhoneDesign.colors.warning.foreground)
                } }
            }
        }
        TextButton(onClick = { open = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(when {
                    status.freshness != null -> status.freshness.label
                    status.notices.isNotEmpty() -> "Data status · ${status.notices.size} ${if (status.notices.size == 1) "notice" else "notices"}"
                    else -> "Data status"
                }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status.freshness?.time?.let { Text(snapshotTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("Details", style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Match data status") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (empty) Text("No additional data status is available.", style = MaterialTheme.typography.bodyMedium)
                status.freshness?.let { Text(listOfNotNull(it.label, it.time?.let(::snapshotTime)).joinToString(" · "), fontWeight = FontWeight.SemiBold) }
                status.provenance.forEach { Text(listOfNotNull(it.label, it.time?.let(::snapshotTime)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
                status.notices.forEach { notice -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(notice.resource, style = MaterialTheme.typography.labelLarge)
                    Text(notice.message, style = MaterialTheme.typography.bodyMedium)
                    notice.retry?.let { action -> TextButton(onClick = { retry(action) }, Modifier.heightIn(min = 48.dp)) { Text("Retry ${notice.resource.lowercase()}") } }
                } }
                if (status.notices.any { it.retry != null }) Text("Retry respects provider access and refresh limits. Previously accepted data remains available.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
}

@Composable
fun MatchScoreHero(match: Match, hidden: Boolean, model: MobileViewModel, week: com.alexdiab.stadiatv.sports.NflWeek? = phoneNflWeek(match)) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(match.league.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            StatusPill(if (hidden) "Score hidden" else match.state.label, match.state == GameState.LIVE)
            if (!hidden && match.state == GameState.FINAL) MatchActionsButton(match, model, week)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.25f || match.league.group == SportGroup.Basketball
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                match.displaySides.forEach { side ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MatchHeroTeam(side, match, model, Modifier.weight(1f), horizontal = true, hidden = hidden)
                        if (match.state != GameState.PRE) Text(if (hidden) "—" else side.score ?: "—", style = PhoneType.score)
                    }
                }
                if (match.state == GameState.PRE) Text(clockTime(match.startMillis), style = PhoneType.score)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MatchHeroTeam(match.displaySides.first(), match, model, Modifier.weight(1f), hidden = hidden)
                Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (hidden) "—" else if (match.state == GameState.PRE) clockTime(match.startMillis) else match.displayScore,
                        style = if (match.state == GameState.PRE) MaterialTheme.typography.headlineSmall else PhoneType.score,
                        textAlign = TextAlign.Center)
                    if (match.state == GameState.PRE) Text(dateLabel(match.startMillis), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                MatchHeroTeam(match.displaySides.last(), match, model, Modifier.weight(1f), hidden = hidden)
            }
        }
        if (!hidden) {
            Text(match.statusDetail.ifBlank { "${dateLabel(match.startMillis)} · ${clockTime(match.startMillis)}" },
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, color = if (match.state == GameState.LIVE) PhoneDesign.colors.live.foreground else MaterialTheme.colorScheme.onSurfaceVariant)
            if (match.state == GameState.LIVE && match.league.group != SportGroup.Basketball) match.live.period?.takeIf { it.isNotBlank() && it !in match.statusDetail }?.let {
                Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (match.state == GameState.LIVE && match.live.situation.isNotEmpty()) Text(match.live.situation.joinToString(" · "),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MatchHeroTeam(side: TeamSide, match: Match, model: MobileViewModel, modifier: Modifier, horizontal: Boolean = false, hidden: Boolean = false) {
    val teamModifier = modifier.clip(PhoneShape.panel).clickable(enabled = side.teamId != null, onClickLabel = "Open ${side.displayName}") {
        side.teamId?.let { model.open(PhonePage.TeamDetail(Team(it, side.displayName, side.shortName, side.abbreviation, side.logoUrl, match.league, side.colorHex))) }
    }.heightIn(min = 48.dp).padding(4.dp)
    val record = side.record?.takeIf { !hidden && (match.league.group != SportGroup.Basketball || match.state == GameState.PRE) }
    if (horizontal) Row(teamModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Logo(side.logoUrl, side.abbreviation, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(side.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            record?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } else Column(teamModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Logo(side.logoUrl, side.abbreviation, 52.dp)
        Text(side.shortName.ifBlank { side.displayName }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        record?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

@Composable
fun CompactMatchHeader(match: Match, hidden: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Logo(match.displaySides.first().logoUrl, match.displaySides.first().abbreviation, 30.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (hidden) "Scores hidden" else if (match.state == GameState.PRE) match.shortName else "${match.displaySides.first().abbreviation} ${match.displayScore} ${match.displaySides.last().abbreviation}", style = MaterialTheme.typography.titleMedium)
            Text(if (hidden) "Final score hidden" else match.statusDetail, style = MaterialTheme.typography.labelMedium, color = if (match.state == GameState.LIVE) PhoneDesign.colors.live.foreground else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Logo(match.displaySides.last().logoUrl, match.displaySides.last().abbreviation, 30.dp)
    }
}

@Composable
fun MatchWatchBar(match: Match, model: MobileViewModel) {
    if (match.state == GameState.FINAL) return // A completed game's old EPG match must not start today's live channel.
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(StreamAvailabilityStore.event(match), match.state, playlist.revision, guide.revision, lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Default) {
                model.container.streams.ensure(match, playlist.channels, { model.container.guide.linkerProgrammes() }, model.container.guide::guideIdFor, guide.revision)
            }
        }
    }
    val best = sources[match.key]?.takeIf { it == model.container.streams.sourcesFor(match) }?.confirmed?.firstOrNull()
    var choose by rememberSaveable(match.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val openViewing = {
                if (playlist.isEmpty) model.open(PhonePage.PlaylistForm())
                else {
                    val current = model.container.streams.sourcesFor(match).confirmed.firstOrNull()
                    if (current != null) model.play(current.channel, match.takeIf { it.state == GameState.LIVE }) else choose = true
                }
            }
            if (best != null || playlist.isEmpty) Button(onClick = openViewing, Modifier.weight(1f).heightIn(min = 48.dp), shape = PhoneShape.control) {
                Icon(if (playlist.isEmpty) Icons.Rounded.Add else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (playlist.isEmpty) "Add playlist" else if (match.state == GameState.LIVE) "Watch live" else "Watch channel")
            } else OutlinedButton(onClick = { choose = true }, Modifier.weight(1f).heightIn(min = 48.dp), shape = PhoneShape.control) {
                Icon(Icons.Rounded.LiveTv, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Choose stream")
            }
            if (best != null) FilledTonalIconButton(onClick = { choose = true }, Modifier.size(48.dp), shape = PhoneShape.control) {
                Icon(Icons.Rounded.Layers, "Choose stream source")
            }
            MatchActionsButton(match, model)
        }
        Text(when {
            playlist.isEmpty -> "Connect your channels to watch from this game."
            best == null -> "No current playlist listing confirms this game."
            match.state == GameState.PRE -> "Opens the channel's current broadcast. The game has not started."
            else -> "Listed in your playlist guide · ${best.channel.canonicalName ?: best.channel.name}"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp))
    }
    if (choose) SourceSheet(match, model, { choose = false })
}

@Composable
private fun MatchActionsButton(match: Match, model: MobileViewModel, week: com.alexdiab.stadiatv.sports.NflWeek? = phoneNflWeek(match)) {
        var menu by remember(match.key) { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Game actions") }
            val context = LocalContext.current
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Share game") }, onClick = { menu = false; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${match.name} · ${match.league.name}\n${dateLabel(match.startMillis)} ${clockTime(match.startMillis)}"), "Share game")) })
                DropdownMenuItem(text = { Text("Hide from Home & Following") }, onClick = { menu = false; model.library.update { it.copy(hiddenGameKeys = (it.hiddenGameKeys + match.key).toList().takeLast(1000).toSet()) }; model.message = "Game hidden. Restore hidden games in Settings."; model.back() })
                if (match.league.path == "football/nfl") DropdownMenuItem(text = { Text("Calendar & results") }, onClick = {
                    menu = false; model.open(PhonePage.LeagueHub(match.league, "Schedule", week))
                })
                DropdownMenuItem(text = { Text("Add to calendar") }, onClick = { menu = false; runCatching { context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, match.name).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, match.startMillis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, match.startMillis + 3 * 3_600_000L)) }.onFailure { model.message = "No calendar app is installed." } })
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(match: Match, model: MobileViewModel, close: () -> Unit) {
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxHeight(0.85f)) { ScreenList {
            item { Text("Ways to watch", style = MaterialTheme.typography.headlineMedium); Text(match.shortName, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val available = sources[match.key]?.takeIf { it == model.container.streams.sourcesFor(match) }?.sources.orEmpty()
            if (available.isEmpty()) item { EmptyState(if (playlists.isEmpty) "Connect your channels" else "No confirmed stream yet", "Watch is offered only when your playlist's guide identifies this game.", if (playlists.isEmpty) "Add playlist" else "Browse channels", { close(); if (playlists.isEmpty) model.open(PhonePage.PlaylistForm()) else model.openLive(PhoneLiveSection.Channels) }) }
            item { Text("Guide matches use your playlist's listings; the provider can still send the wrong video. Channel-name suggestions are unverified.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(available, key = { it.channel.key }) { source -> PhoneCard(onClick = {
                val current = model.container.streams.sourcesFor(match).sources.firstOrNull { it.channel.key == source.channel.key }
                if (current == null) model.message = "The stream list changed. Choose a current source."
                else { close(); model.play(current.channel, match.takeIf { current.isConfirmed && it.state == GameState.LIVE }) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Logo(source.channel.logoUrl, source.channel.name, 40.dp)
                    Column(Modifier.weight(1f)) { Text(source.channel.canonicalName ?: source.channel.name, fontWeight = FontWeight.SemiBold); Text(if (source.isConfirmed) "Listed in your playlist EPG" else if (source.tier == com.alexdiab.stadiatv.live.LinkTier.EventChannel) "Channel-name match · no EPG confirmation" else "Possible channel · programme unconfirmed", style = MaterialTheme.typography.labelMedium, color = if (source.isConfirmed) PhoneDesign.colors.success.foreground else MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Rounded.PlayCircle, "Play stream", tint = MaterialTheme.colorScheme.primary)
                }
                source.programmeTitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } }
        } }
    }
}

@Composable
private fun GameOverview(match: Match, snapshot: PhoneGameSnapshot, model: MobileViewModel, selectTab: (String) -> Unit) {
    val data = snapshot.data
    val timeline = when { data.hockey != null -> NativeHockeyEvents.scoringTimeline(data)
        phoneUsesNativeSoccerEvents(data) -> com.alexdiab.stadiatv.sports.NativeSoccerPresentation.keyEvents(data.plays)
        else -> scoringTimeline(data.plays, match.league.group == SportGroup.Basketball) }
    ScreenList {
        item("fantasy-roster") { NativeFantasyGameContext(match, model) }
        if (match.league.path == "football/nfl") item("nfl-calendar") { NflGameCalendarLink(match, data, model) }
        if (match.league.group == SportGroup.Basketball) item("basketball-calendar") {
            TextButton(onClick = { model.open(PhonePage.LeagueHub(match.league, "Schedule", calendarDate = match.startMillis)) }) { Text("Calendar & results") }
        }
        if (match.state == GameState.LIVE) data.baseball?.let { detail -> item("baseball-live") { BaseballSituation(detail, match, model, data.boxScore) } }
        if (match.state == GameState.PRE) data.baseball?.let { detail -> item("baseball-probables") { BaseballProbables(detail, match, model) } }
        if (match.state == GameState.LIVE && match.statusDetail !in setOf("Halftime", "Delayed", "Suspended")) data.football?.field?.let { field -> item("football-field") { FootballFieldView(match, field) } }
        if (match.state == GameState.LIVE) data.football?.currentDriveId?.let { id -> data.drives.firstOrNull { it.id == id } }?.let { drive ->
            item("current-drive") { PhoneCard {
                Text("Current ${drive.team} drive", style = MaterialTheme.typography.titleMedium)
                val description = listOfNotNull(drive.footballDrive?.playCount?.let { "$it plays" }, drive.yards.takeIf(String::isNotBlank)?.let { "$it yards" }, drive.duration.takeIf(String::isNotBlank)).joinToString(" · ")
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
            } }
        }
        if (match.state == GameState.LIVE) data.basketball?.let { detail -> item("basketball-state") { BasketballSituation(match, detail) } }
        data.soccer?.shootout?.takeIf { it.isNotEmpty() }?.let { kicks -> item("shootout") { SoccerShootout(match, kicks) } }
        if (data.lineScore != null || match.nativeReference?.provider == "NFL" && match.state != GameState.PRE) item("line") {
            val line = data.lineScore
            if (line != null && data.baseball != null) BaseballLineScore(line)
            else if (line != null && (data.football != null || data.basketball != null)) FootballQuarterScore(line)
            else if (line != null) StatTable(if (match.league.group == SportGroup.Baseball) "Inning by inning" else "Line score", listOf("Team") + line.periods + "T", listOf(listOf(line.awayLabel) + line.away + line.awayTotal, listOf(line.homeLabel) + line.home + line.homeTotal))
            else PhoneCard {
                Text("Quarter scores", style = MaterialTheme.typography.titleMedium)
                if (snapshot.nativeData == null && snapshot.error == null) { Text("Loading NFL period scores…", color = MaterialTheme.colorScheme.onSurfaceVariant); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else Text("Period scores weren't supplied by NFL.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (match.state == GameState.PRE) item("reminder") { GameReminderButton(match, model) }
        if (match.state == GameState.LIVE && match.live.situation.isNotEmpty()) item("situation") { PhoneCard { Text("LIVE SITUATION", style = MaterialTheme.typography.labelMedium, color = BannerLive); match.live.situation.forEach { Text(it, style = MaterialTheme.typography.titleMedium) } } }
        if (data.baseball != null) {
            val battingLeaders = baseballBattingLeaders(data.boxScore)
            if (battingLeaders.isNotEmpty()) item("mlb-leaders") { BaseballBattingLeaders(battingLeaders, match, model) }
        }
        item("scoring-title") { if (phoneUsesNativeSoccerEvents(data)) SectionTitle("Match events", "Full timeline") { selectTab("Timeline") }
            else SectionTitle(if (match.league.group == SportGroup.Basketball) "Lead changes & ties" else if (match.league.group in setOf(SportGroup.Hockey, SportGroup.Baseball)) "Scoring summary" else "Key events") }
        if (timeline.isEmpty()) item("no-scoring") { Text(if (match.state == GameState.PRE) "The story of this game starts at ${clockTime(match.startMillis)}." else if (data.soccer != null) "No goals or cards supplied yet." else "No scoring events supplied yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        if (data.baseball != null) items(data.baseball!!.atBats.filter { it.scoring }.asReversed(), key = { "at-bat-${it.id}" }) { BaseballAtBatCard(it, match, model) }
        else items(timeline, key = { "event-${it.id}" }) { TimelinePlay(it, match, model,
            if (data.basketball != null) com.alexdiab.stadiatv.sports.NativeBasketballPresentation.leadHeadline(it, match) else null) }
        data.hockey?.recapUrl?.let { url -> item("nhl-recap") { val context = LocalContext.current; TextButton(onClick = { openBrowser(context, url) }) { Icon(Icons.Rounded.PlayCircle, null); Spacer(Modifier.width(8.dp)); Text("Game recap") } } }
        if (data.hockey?.leaders?.isNotEmpty() == true) {
            item("nhl-leaders-title") { SectionTitle("Game leaders") }
            item("nhl-leaders") { HockeyLeaders(data.hockey!!.leaders, match, model) }
        }
        if (data.basketball?.gameLeaders?.isNotEmpty() == true) {
            item("basketball-leaders-title") { SectionTitle("Game leaders") }
            item("basketball-leaders") { BasketballLeaders(data.basketball!!.gameLeaders, match, model) }
        } else if (data.leaders.isNotEmpty()) {
            item("leaders-title") { SectionTitle(if (match.state == GameState.PRE) "Season leaders" else "Game leaders") }
            item("leaders") { LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(data.leaders.flatMap { leader -> listOfNotNull(leader.away?.let { Triple(leader.category, match.away.abbreviation, it) }, leader.home?.let { Triple(leader.category, match.home.abbreviation, it) }) }) { (category, team, person) ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) { Column(Modifier.width(148.dp).clickable { model.message = "${person.name}\n$team · $category\n${person.value}\n${person.detail}" }.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Logo(person.headshotUrl, person.name, 54.dp); Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        Text(person.value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("$team · $category", maxLines = 2, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                }
            } }
        }
        val keyStats = when {
            data.hockey != null -> com.alexdiab.stadiatv.sports.NativeHockeyPresentation.keyStats(data.teamStats)
            data.basketball != null -> com.alexdiab.stadiatv.sports.NativeBasketballPresentation.keyStats(data.teamStats)
            data.soccer != null -> com.alexdiab.stadiatv.sports.NativeSoccerPresentation.keyStats(data.teamStats)
            else -> data.teamStats.take(6)
        }
        if (keyStats.isNotEmpty()) item("stats") { Column {
            if (data.soccer != null) SectionTitle("Team stats", "View all stats") { selectTab("Team stats") }
            ComparisonStats(match, keyStats, data.teamStatsSource)
        } }
        data.soccer?.let { soccer -> item("soccer-lineups-status") { PhoneCard {
            Text("LINEUPS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (soccer.lineups.isEmpty()) Text(if (soccer.errors.containsKey("lineups")) "Lineups couldn't be checked." else "Lineups have not been announced yet.", style = MaterialTheme.typography.bodyMedium)
            soccer.lineups.sortedByDescending { it.home }.forEach { lineup -> Text("${if (lineup.home) match.home.shortName else match.away.shortName}${lineup.formation?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { selectTab("Lineups") }) { Text("View lineups") }
        } } }
        data.soccer?.takeIf { it.pressure.isNotEmpty() }?.let { soccer -> item("soccer-pressure") { SoccerPressureCard(match, soccer) } }
        if (data.soccer != null && match.league.path in setOf("soccer/eng.1", "soccer/usa.1", "soccer/esp.1")) item("soccer-table-preview") { PhoneCard {
            val table = snapshot.soccerTable
            SectionTitle(if (table?.provisional == true) "Live table · provisional" else "League table", "Full table") { selectTab(if (match.nativeReference?.provider == "LaLiga") "League table" else "Live table") }
            if (table != null) {
                Text("${match.league.name} · ${if (table.provisional) "Provisional" else "Published"} · ${snapshotTime(table.checkedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ids = setOf(match.home.teamId?.removePrefix("epl:"), match.away.teamId?.removePrefix("epl:"))
                val rows = table.rows.filter { it.id in ids }
                rows.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row.position?.toString() ?: "–", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
                    Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(row.points?.let { "$it pts" } ?: "–", style = MaterialTheme.typography.labelLarge)
                } }
                if (rows.isEmpty()) Text("Open the full provider table for published positions.", style = MaterialTheme.typography.bodyMedium)
            } else Text(if (snapshot.soccerTableLoading) "Loading the provider's live table…" else "Published and provisional standings are kept separate.", style = MaterialTheme.typography.bodyMedium)
            snapshot.soccerTableError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } }
        if (data.winProbability.isNotEmpty()) item("probability") { ProbabilityChart(match, data.winProbability, data.winProbabilitySource, data.baseballProbability) }
        if (!data.headline.isNullOrBlank()) item("headline") { Text(data.headline.orEmpty(), style = MaterialTheme.typography.titleLarge) }
        if (data.videos.isNotEmpty()) item("videos") { GameVideos(data.videos, model) }
        item("related-news") { RelatedGameNews(match, model) }
        item("info") { PhoneCard {
            Text("GAME INFO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val info = (listOfNotNull(match.venue?.let { "Venue" to it }, match.broadcasts.takeIf { it.isNotEmpty() }?.let { "Broadcast" to it.joinToString(" · ") }) + data.gameInfo).associate { it.first to it.second }.toList()
            info.forEach { (label, value) -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) } }
            data.soccer?.let { soccer ->
                Text("Kickoff · ${dateLabel(match.startMillis)} ${clockTime(match.startMillis)}", style = MaterialTheme.typography.bodyMedium)
                soccer.officials.forEach { official -> Text("${official.role} · ${official.name}", style = MaterialTheme.typography.bodyMedium) }
                soccer.lineups.sortedByDescending { it.home }.forEach { lineup -> lineup.manager?.let { manager ->
                    Text("${if (lineup.home) match.home.abbreviation else match.away.abbreviation} manager · $manager", style = MaterialTheme.typography.bodyMedium)
                } }
                soccer.surfaceUpdatedAt.forEach { (surface, at) -> Text("${surface.replaceFirstChar(Char::uppercase)} · ${snapshotTime(at)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            data.providerStamp?.let { stamp -> Text("${stamp.provider} · ${if (data.hockey?.savedSnapshot == true || data.baseball?.savedSnapshot == true || data.football?.savedSnapshot == true || data.basketball?.savedSnapshot == true || data.soccer?.savedSnapshot == true || System.currentTimeMillis() - stamp.requestedAt > 90_000 && !stamp.final) "Saved data" else "Checked"} ${snapshotTime(stamp.requestedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            (data.hockey?.surfaceUpdatedAt ?: data.baseball?.surfaceUpdatedAt ?: data.basketball?.surfaceUpdatedAt).orEmpty().forEach { (surface, at) -> Text("${mapOf("score" to "Score", "box" to "Box score", "landing" to "Overview", "overview" to "Overview", "boxscore" to "Box score", "linescore" to "Line score", "plays" to "Plays", "play-by-play" to "Plays", "right-rail" to "Team stats")[surface] ?: surface} · ${snapshotTime(at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            data.baseballExtras.checkedAt.forEach { (kind, at) -> Text("${kind.replaceFirstChar(Char::uppercase)} · ${if (kind in data.baseballExtras.saved) "Saved" else "Checked"} ${snapshotTime(at)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            data.football?.let { football ->
                if (football.summaryUpdatedAt > 0) Text("Score · ${snapshotTime(football.summaryUpdatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (football.detailsUpdatedAt > 0) Text("Plays & player stats · ${snapshotTime(football.detailsUpdatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snapshot.checkedAt != 0L && match.nativeReference == null) Text("ESPN summary · Checked ${clockTime(snapshot.checkedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}

@Composable
private fun NflGameCalendarLink(match: Match, data: GameCentre, model: MobileViewModel) {
    val week = phoneNflWeek(match, data)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        week?.let { Text(phoneNflWeekLabel(it), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { model.open(PhonePage.LeagueHub(match.league, "Schedule", week)) }) { Text("Calendar & results") }
    }
}

@Composable
fun ComparisonStats(match: Match, stats: List<StatComparison>, source: String? = null) {
    PhoneCard {
        Text("TEAM STATS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        source?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row { Text(match.displaySides.first().abbreviation, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text(match.displaySides.last().abbreviation, fontWeight = FontWeight.Bold) }
        if (stats.isEmpty()) Text("Team statistics will appear when supplied.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (stat in stats) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val left = if (match.homeFirst) stat.home else stat.away; val right = if (match.homeFirst) stat.away else stat.home
            Row(verticalAlignment = Alignment.CenterVertically) { Text(left, fontWeight = FontWeight.SemiBold); Text(stat.label, Modifier.weight(1f).padding(horizontal = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(right, fontWeight = FontWeight.SemiBold) }
            if (stat.awaySecondary != null || stat.homeSecondary != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text((if (match.homeFirst) stat.homeSecondary else stat.awaySecondary).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text((if (match.homeFirst) stat.awaySecondary else stat.homeSecondary).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stat.lowerIsBetter) Text("Lower is better", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            statBarValues(left, right)?.let { (away, home) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) { Box(Modifier.fillMaxWidth(away).fillMaxHeight().background(MaterialTheme.colorScheme.primary)) }
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) { Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(home).fillMaxHeight().background(BannerGreen)) }
                }
            }
        }
    }
}

@Composable
private fun GamePlays(match: Match, data: GameCentre, model: MobileViewModel) {
    if (phoneUsesNativeSoccerEvents(data)) { SoccerTimeline(match, data, model); return }
    if (data.hockey?.eventsLoaded == true) { HockeyPlays(match, data, model); return }
    if (data.football?.playsLoaded == true) { FootballPlays(match, data); return }
    if (data.basketball?.playsLoaded == true) { BasketballPlays(match, data); return }
    var period by rememberSaveable(match.key) { mutableStateOf("All periods") }
    var scoring by rememberSaveable(match.key) { mutableStateOf(false) }
    val periods = data.plays.map { it.period }.filter(String::isNotBlank).distinct().asReversed()
    val filtered = data.plays.filter { (period == "All periods" || it.period == period) && (!scoring || it.isScoring) }
    Column {
        data.playsSource?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { ChoiceRow(listOf("All periods") + periods, period) { period = it } }; FilterChip(scoring, { scoring = !scoring }, label = { Text("Scoring") }) }
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) item { EmptyState("No events yet", "The live feed will update here as plays become available.") }
            items(filtered, key = { it.id }) { TimelinePlay(it, match, model) }
        }
    }
}

@Composable
private fun TimelinePlay(play: Play, match: Match, model: MobileViewModel, headline: String? = null) {
    if (play.soccerEvent != null) { SoccerEventRow(play, match, model); return }
    if (play.hockeyEvent != null) { HockeyEventRow(play, match, model); return }
    if (play.footballEvent != null) { FootballPlayRow(play); return }
    val context = LocalContext.current
    val side = if (play.teamId == match.home.teamId) match.home else match.away
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.padding(top = 6.dp).size(8.dp).background(if (play.isScoring) BannerLive else MaterialTheme.colorScheme.primary, CircleShape)); Box(Modifier.width(1.dp).height(70.dp).background(MaterialTheme.colorScheme.outlineVariant)) }
        Column(Modifier.weight(1f).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${play.period} · ${play.clock}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (play.isScoring) { Logo(side.logoUrl, side.abbreviation, 18.dp); if (play.homeScore != null || play.awayScore != null) { Spacer(Modifier.width(6.dp)); Text(if (match.homeFirst) "${play.homeScore ?: "–"}–${play.awayScore ?: "–"}" else "${play.awayScore ?: "–"}–${play.homeScore ?: "–"}", fontWeight = FontWeight.Bold) } }
            }
            headline?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            if (play.type.isNotBlank()) Text(play.type, style = MaterialTheme.typography.labelLarge, color = if (play.isScoring) BannerLive else MaterialTheme.colorScheme.primary)
            Text(play.text, style = MaterialTheme.typography.bodyMedium)
            play.videoUrl?.let { url -> TextButton(onClick = { openBrowser(context, url) }) { Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Watch replay") } }
        }
    }
}

@Composable
private fun GameBoxScore(match: Match, data: GameCentre, model: MobileViewModel) {
    if (data.hockey != null) { HockeyBoxScore(match, data, model); return }
    if (data.baseball != null) { BaseballBoxScore(match, data, model); return }
    if (data.football != null) { FootballBoxScore(match, data, model); return }
    if (data.basketball != null) { BasketballBoxScore(match, data, model); return }
    val context = LocalContext.current
    var team by rememberSaveable(match.key) { mutableIntStateOf(0) }
    var group by rememberSaveable(match.key, team) { mutableIntStateOf(0) }
    val selected = data.boxScore.getOrNull(team)
    val selectedGroup = selected?.groups?.getOrNull(group)
    ScreenList {
        item { ChoiceRow(data.boxScore.map { it.abbreviation.ifBlank { it.teamName } }, selected?.abbreviation?.ifBlank { selected.teamName }.orEmpty()) { label -> team = data.boxScore.indexOfFirst { it.abbreviation.ifBlank { it.teamName } == label }.coerceAtLeast(0) } }
        if (selected == null) item { EmptyState("Box score unavailable", "Player statistics appear when the source publishes them.") }
        if (selected != null && selected.groups.size > 1) item { ChoiceRow(selected.groups.map { it.title }, selectedGroup?.title.orEmpty()) { label -> group = selected.groups.indexOfFirst { it.title == label }.coerceAtLeast(0) } }
        selectedGroup?.let { table -> item {
            PhoneCard {
                Text(table.title, style = MaterialTheme.typography.titleMedium)
                Row {
                    Column(Modifier.width(144.dp)) {
                        Text("PLAYER", Modifier.height(34.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        table.rows.forEach { row -> Row(Modifier.height(54.dp).clickable(enabled = row.athleteId != null || row.profileUrl != null) { val athleteId = row.athleteId; if (athleteId != null) model.open(PhonePage.PlayerDetail(match.league, Athlete(athleteId, row.name, position = row.detail, headshotUrl = row.headshot))) else row.profileUrl?.let { openBrowser(context, it) } }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Logo(row.headshot, row.name, 28.dp); Column { Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge); Text(if (row.didNotPlay) "DNP" else "${row.detail}${if (row.starter) " · Starter" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } }
                        if (table.totals.isNotEmpty()) Text("TOTAL", Modifier.height(44.dp).padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                    Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        Row { table.labels.forEach { Text(it, Modifier.width(62.dp).height(34.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                        table.rows.forEach { row -> Row { row.stats.forEach { Text(it, Modifier.width(62.dp).height(54.dp).padding(top = 18.dp), style = MaterialTheme.typography.labelLarge) } } }
                        if (table.totals.isNotEmpty()) Row { table.totals.forEach { Text(it, Modifier.width(62.dp).height(44.dp).padding(top = 12.dp), style = MaterialTheme.typography.labelLarge) } }
                    }
                }
                Text("Swipe stats horizontally · Player names stay in place", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
    }
}

@Composable
private fun ProbabilityChart(match: Match, points: List<Float>, source: String? = null, native: List<BaseballProbability> = emptyList()) {
    val color = MaterialTheme.colorScheme.primary
    val values = if (native.isNotEmpty()) native.map { it.homeProbability } else points
    if (values.isEmpty()) return
    PhoneCard {
        Text("${match.home.abbreviation} win probability · ${source ?: "ESPN"}", style = MaterialTheme.typography.titleMedium)
        Text("${(values.last() * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics { contentDescription = "${match.home.abbreviation} win probability, latest ${(values.last() * 100).toInt()} percent${native.lastOrNull()?.let { ", MLB at-bat index ${it.atBatIndex}" }.orEmpty()}" }) {
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
            val first = native.firstOrNull()?.atBatIndex ?: 0
            val last = native.lastOrNull()?.atBatIndex ?: (values.size - 1)
            val path = Path(); var endpoint = Offset.Zero
            values.forEachIndexed { i, value ->
                val index = native.getOrNull(i)?.atBatIndex ?: i
                val x = (index - first).toFloat() / (last - first).coerceAtLeast(1) * size.width
                endpoint = Offset(x, (1 - value.coerceIn(0f, 1f)) * size.height)
                if (i == 0) path.moveTo(endpoint.x, endpoint.y) else path.lineTo(endpoint.x, endpoint.y)
            }
            drawPath(path, color, style = Stroke(3.dp.toPx())); drawCircle(color, 4.dp.toPx(), endpoint)
        }
        if (native.isNotEmpty()) Text("MLB at-bat index ${native.first().atBatIndex}–${native.last().atBatIndex} · Only reported samples", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShotChart(match: Match, data: GameCentre) {
    if (match.league.group == SportGroup.Basketball) { BasketballShotChart(match, data); return }
    var side by rememberSaveable(match.key) { mutableStateOf("Both teams") }
    var period by rememberSaveable(match.key) { mutableStateOf("All periods") }
    var selected by remember { mutableStateOf<GameShot?>(null) }
    val hockey = match.league.group == SportGroup.Hockey
    val shots = data.shots.filter { (side == "Both teams" || it.home == (side == match.home.abbreviation)) && (period == "All periods" || it.period.toString() == period) }
    val primary = MaterialTheme.colorScheme.primary; val line = MaterialTheme.colorScheme.outlineVariant
    ScreenList {
        item { ChoiceRow(listOf("Both teams", match.away.abbreviation, match.home.abbreviation), side) { side = it } }
        item { ChoiceRow(listOf("All periods") + data.shots.map { it.period }.filter { it > 0 }.distinct().sorted().map(Int::toString), period) { period = it } }
        if (data.shotSource == null) item { EmptyState("Spatial feed unavailable", "Real locations are requested from ${if (hockey) "NHL" else "NBA"}. Missing coordinates are never estimated from a shot's distance. This network or event may not provide them.") }
        else item { PhoneCard {
            Text(if (hockey) "Shot map" else "Shot chart", style = MaterialTheme.typography.titleLarge)
            Text("${data.shotSource} · ${shots.size} attempts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fun position(shot: GameShot, width: Float, height: Float): Offset = if (hockey) Offset((shot.x + 100) / 200 * width, (42.5f - shot.y) / 85 * height) else Offset((shot.x + 25) / 50 * width, height - (shot.y + 5.25f) / 47 * height)
            Canvas(Modifier.fillMaxWidth().aspectRatio(if (hockey) 200f / 85 else 50f / 47).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).semantics { contentDescription = "${shots.size} reported shot locations. Goals or made shots are circles; misses are crosses. Details are listed below." }.pointerInput(shots) { detectTapGestures { point -> selected = shots.minByOrNull { (position(it, size.width.toFloat(), size.height.toFloat()) - point).getDistance() }?.takeIf { (position(it, size.width.toFloat(), size.height.toFloat()) - point).getDistance() < 24.dp.toPx() } } }) {
                val stroke = 1.5.dp.toPx()
                drawRect(line, style = Stroke(stroke))
                if (hockey) {
                    for (fraction in listOf(0.055f, 0.375f, 0.5f, 0.625f, 0.945f)) drawLine(if (fraction == 0.375f || fraction == 0.625f) Color(0xFF5A8DC7) else BannerLive.copy(alpha = 0.6f), Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), stroke)
                    drawCircle(line, size.height * 15 / 85, center, style = Stroke(stroke))
                    for (x in listOf(0.155f, 0.845f)) for (y in listOf(0.24f, 0.76f)) drawCircle(line, size.height * 15 / 85, Offset(size.width * x, size.height * y), style = Stroke(stroke))
                } else {
                    val hoop = Offset(size.width / 2, size.height * (1 - 5.25f / 47)); val unit = size.width / 50
                    drawCircle(line, unit * 0.75f, hoop, style = Stroke(stroke)); drawRect(line, Offset(size.width / 2 - 8 * unit, size.height - 19 * unit), Size(16 * unit, 19 * unit), style = Stroke(stroke))
                    drawArc(line, 180f, 180f, false, Offset(hoop.x - 23.75f * unit, hoop.y - 23.75f * unit), Size(47.5f * unit, 47.5f * unit), style = Stroke(stroke))
                }
                shots.forEach { shot -> val p = position(shot, size.width, size.height); val color = if (shot.home) BannerGreen else primary; if (p.y in 0f..size.height && p.x in 0f..size.width) {
                    if (shot.made) drawCircle(color, 4.dp.toPx(), p, style = Stroke(2.dp.toPx())) else { val r = 3.dp.toPx(); drawLine(color.copy(alpha = 0.6f), p - Offset(r, r), p + Offset(r, r), 1.dp.toPx()); drawLine(color.copy(alpha = 0.6f), p + Offset(-r, r), p + Offset(r, -r), 1.dp.toPx()) }
                } }
            }
            Text("${match.away.abbreviation}: blue · ${match.home.abbreviation}: green · ○ ${if (hockey) "goal" else "made"} · × other attempts", style = MaterialTheme.typography.labelMedium)
            if (!hockey) Text("Basket-relative half court. Attempts beyond this viewport are listed below, not relocated.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            selected?.let { Text("${it.player} · ${it.description}", style = MaterialTheme.typography.bodyMedium) }
        } }
        items(shots.asReversed().take(50), key = { it.id }) { shot -> Row(Modifier.fillMaxWidth().clickable { selected = shot }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (shot.made) "○" else "×", color = if (shot.home) BannerGreen else primary); Column { Text(shot.player.ifBlank { if (shot.home) match.home.abbreviation else match.away.abbreviation }, fontWeight = FontWeight.SemiBold); Text("P${shot.period} · ${shot.description}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
    }
}
