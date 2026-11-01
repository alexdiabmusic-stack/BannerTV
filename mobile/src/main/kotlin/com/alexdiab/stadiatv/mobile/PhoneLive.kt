@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.ui.livetv.GuideFilter
import com.alexdiab.stadiatv.ui.livetv.GuideLogic
import com.alexdiab.stadiatv.ui.livetv.GuideOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min

@Composable
fun LiveScreen(model: MobileViewModel) {
    val section = model.liveSection
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 20.dp)) { SegmentedChoices(PhoneLiveSection.entries.map { it.label }, section.label) { label -> model.selectLiveSection(PhoneLiveSection.entries.first { it.label == label }) } }
        if (playlist.isEmpty && section != PhoneLiveSection.Sports) {
            EmptyState("Bring your live TV", "Add your provider's playlist to browse channels and the programme guide.", "Add playlist", { model.open(PhonePage.PlaylistForm()) })
        } else when (section) {
            PhoneLiveSection.Guide -> PhoneGuide(model)
            PhoneLiveSection.Sports -> LiveSports(model)
            else -> ChannelsScreen(model)
        }
    }
}

@Composable
fun ChannelsScreen(model: MobileViewModel, onSelect: ((Channel) -> Unit)? = null) {
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    val lineup by model.container.lineup.lineup.collectAsStateWithLifecycle()
    val prefs by model.container.lineup.prefs.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    val base = remember(lineup.revision, playlists.revision, prefs) {
        if (lineup.entries.isEmpty()) playlists.channels else lineup.entries.map { entry -> entry.pick(prefs).channel.copy(canonicalName = entry.name) }
    }
    val custom = library.channelGroups.firstOrNull { it.name == category }
    val visible = remember(base, query, category, settings.favoriteChannelIds, library.channelGroups) {
        groupedChannels(base, custom, model).filter { channel -> (query.isBlank() || (channel.canonicalName ?: channel.name).contains(query, true) || channel.group.contains(query, true)) &&
            when (category) { "Favorites" -> channel.key in settings.favoriteChannelIds; "Sports" -> channel.group.contains("sport", true) || Regex("TSN|Sportsnet|ESPN|FOX Sport|beIN", RegexOption.IGNORE_CASE).containsMatchIn(channel.name); else -> true } }
    }
    ScreenList {
        item("search") { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search channels") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true) }
        item("filter") { ChoiceRow(listOf("All", "Favorites", "Sports") + library.channelGroups.map { it.name }, category) { category = it } }
        item("groups") { TextButton(onClick = { model.open(PhonePage.ChannelGroups) }) { Text("Manage channel groups") } }
        if (onSelect == null && base.size > 1) item("multi") { TextButton(onClick = { model.stopPlayer(); model.open(PhonePage.Multiview) }) { Icon(Icons.Rounded.GridView, null); Spacer(Modifier.width(8.dp)); Text("Watch multiple channels") } }
        if (visible.isEmpty()) item("empty") { EmptyState(if (playlists.loading.isNotEmpty()) "Loading channels…" else "No channels found", "Try another filter or refresh your playlist in Settings.") }
        items(visible, key = { it.key }) { channel -> ChannelRow(channel, model, onClick = { if (onSelect != null) onSelect(channel) else model.play(channel) }) }
    }
}

@Composable
private fun LiveSports(model: MobileViewModel) {
    val rawFeed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val feed = remember(rawFeed, library.hiddenGameKeys) { rawFeed.visibleGames(library.hiddenGameKeys) }
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val filter = if (model.allLiveSports) "All games" else "For you"
    val games = if (filter == "For you") feed.liveNow.filter(settings::inScope) else feed.liveNow
    val soon = if (filter == "For you") feed.startingSoonForYou else feed.startingSoon
    PhoneAdaptiveLazyFeed(primary = {
        item { ChoiceRow(listOf("For you", "All games"), filter) { model.selectAllLiveSports(it == "All games") } }
        if (phoneFeedUnavailable(feed)) item { AssistChip(onClick = { model.refresh() }, label = { Text(phoneFeedFailureLabel(feed)) }, leadingIcon = { Icon(Icons.Rounded.CloudOff, null) }) }
        if (games.isEmpty()) item {
            when {
                phoneFeedUnavailable(feed) -> EmptyState("Live scores unavailable", "Current games could not be checked. Reconnect or retry.", "Retry", { model.refresh() })
                feed.isLoading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking live scores…", style = MaterialTheme.typography.bodyMedium) }
                filter == "For you" && feed.liveNow.isNotEmpty() -> EmptyState("No followed games live", "There is live action in other leagues.", "See all games", { model.selectAllLiveSports(true) })
                else -> EmptyState("Nothing live right now", "Upcoming games are listed below. Watch buttons appear when your provider's guide confirms a stream.")
            }
        }
        items(games, key = { it.key }) { CinematicEventRow(it, model) }
    }, secondary = {
        if (soon.isNotEmpty()) item { SectionTitle("Starting soon") }
        items(soon.take(15), key = { "soon-${it.key}" }) { CinematicEventRow(it, model) }
    })
}

data class GuideCell(val programme: Programme, val offsetMinutes: Float, val durationMinutes: Float)

/** Shared by the grid and its regression tests: clip cells to the viewport without distorting programme duration. */
fun guideCells(programmes: List<Programme>, fromMillis: Long, toMillis: Long): List<GuideCell> = programmes
    .asSequence().filter { it.endMillis > fromMillis && it.startMillis < toMillis && it.endMillis > it.startMillis }
    .map { p -> GuideCell(p, (max(p.startMillis, fromMillis) - fromMillis) / 60_000f, (min(p.endMillis, toMillis) - max(p.startMillis, fromMillis)) / 60_000f) }
    .sortedBy { it.offsetMinutes }.toList()

@Composable
fun PhoneGuide(model: MobileViewModel, onSelect: ((Channel) -> Unit)? = null, onReplay: ((Channel, Programme) -> Unit)? = null) {
    val library by model.library.state.collectAsStateWithLifecycle()
    val nativeLibrary by model.nativeFantasy.state.collectAsStateWithLifecycle()
    val rawFantasyIndex by model.nativeFantasyContexts.collectAsStateWithLifecycle()
    var customGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val lineup by model.container.lineup.lineup.collectAsStateWithLifecycle()
    val prefs by model.container.lineup.prefs.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableIntStateOf(0) }
    var window by rememberSaveable { mutableIntStateOf(0) }
    var anchor by remember { mutableIntStateOf(0) }
    var filterName by rememberSaveable { mutableStateOf(GuideFilter.All.name) }
    val filter = GuideFilter.entries.firstOrNull { it.name == filterName } ?: GuideFilter.All
    var detail by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val fantasyIndex = if (phoneShowFantasyGuide(library.fantasyDisplay, settings.spoilerFreeMode))
        phoneNativeFantasyIndex(rawFantasyIndex, nativeLibrary, now).let { index -> index.copy(contexts = index.contexts.filter { it.match.key !in library.hiddenGameKeys }) }
        else com.alexdiab.stadiatv.data.fantasy.NativeFantasyEventIndex()
    val rowHeight = 84.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val initialStart = remember(day, window, anchor) {
        val calendar = Calendar.getInstance()
        if (day == 0) { calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) / 30 * 30); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0) }
        else { calendar.add(Calendar.DATE, day); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0) }
        calendar.timeInMillis + window * 6 * 3_600_000L
    }
    val end = initialStart + 6 * 3_600_000L
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val channels = remember(lineup.revision, playlist.revision, prefs, settings, query, filter, guide.revision, now / 60_000L, library.channelGroups, customGroup) {
        val base = if (lineup.entries.isEmpty()) playlist.channels.filter { query.isBlank() || it.name.contains(query, true) }
        else GuideLogic.build(lineup, settings, prefs, filter, GuideOptions(), query, model.container.guide::nowAndNext, now)
            .rows.map { row -> row.pick.channel.copy(canonicalName = row.entry.name) }
        groupedChannels(base, library.channelGroups.firstOrNull { it.id == customGroup }, model)
    }
    PhoneInlineDetailLayout(enabled = onSelect == null, primary = { inline ->
      Column(Modifier.fillMaxSize()) {
        if (library.channelGroups.isNotEmpty()) Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(listOf("All channels") + library.channelGroups.map { it.name }, library.channelGroups.firstOrNull { it.id == customGroup }?.name ?: "All channels") { label -> customGroup = library.channelGroups.firstOrNull { it.name == label }?.id } }
        Box(Modifier.padding(horizontal = 20.dp)) { ChoiceRow(listOf(GuideFilter.All, GuideFilter.Favorites, GuideFilter.Sports, GuideFilter.News, GuideFilter.Movies, GuideFilter.Kids).map { it.label }, filter.label) { label -> filterName = GuideFilter.entries.first { it.label == label }.name } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = window > -28, onClick = { window-- }) { Icon(Icons.Rounded.ChevronLeft, "Previous six hours") }
            Text("${dateLabel(initialStart)} · ${clockTime(initialStart)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { day = 0; window = 0; anchor++; scope.launch { scroll.scrollTo(0) } }) { Text("Now") }
            IconButton(enabled = window < 28, onClick = { window++ }) { Icon(Icons.Rounded.ChevronRight, "Next six hours") }
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), label = { Text("Find channel in guide") }, singleLine = true)
        if (guide.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().height(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(Modifier.width(112.dp).fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) { Text("CHANNEL", style = MaterialTheme.typography.labelMedium) }
            Row(Modifier.weight(1f).clipToBounds().horizontalScroll(scroll)) {
                repeat(12) { index -> Box(Modifier.width(90.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) { Text(clockTime(initialStart + index * 30 * 60_000L), style = MaterialTheme.typography.labelMedium) } }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (channels.isEmpty()) item { EmptyState("No guide channels", "Connect a playlist or try a different search.") }
            items(channels, key = { it.key }) { channel ->
                val programmes = remember(channel, guide.revision, initialStart) { model.container.guide.programmesFor(channel) }
                val guideBinding = remember(channel, guide.revision) { model.container.guide.guideIdFor(channel) }
                val cells = remember(programmes, initialStart) { guideCells(programmes, initialStart, end) }
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    Column(Modifier.width(112.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).clickable { if (onSelect == null) model.play(channel) else onSelect(channel) }.padding(8.dp), verticalArrangement = Arrangement.Center) {
                        Logo(channel.logoUrl, channel.canonicalName ?: channel.name, 24.dp)
                        Text(channel.canonicalName ?: channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.weight(1f).clipToBounds().horizontalScroll(scroll)) {
                        Box(Modifier.width(1080.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                            if (cells.isEmpty()) Text("No programme information", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp))
                            cells.forEach { cell ->
                                val active = cell.programme.isOn(now)
                                val fantasyCount = fantasyProgrammeCount(PhoneFantasyGuideEvidence(channel.key, guideBinding, cell.programme, fantasyIndex), model.fantasyGuideCache)
                                Surface(
                                    modifier = Modifier.offset(x = (cell.offsetMinutes * 3).dp).width((cell.durationMinutes * 3).dp).fillMaxHeight().padding(2.dp).clickable { detail = channel to cell.programme },
                                    shape = RoundedCornerShape(8.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                ) {
                                    Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(cell.programme.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            // Keep the badge next to the leading clock. Long programme cells
                                            // can be wider than the viewport, so a weighted spacer hides it.
                                            Text(clockTime(cell.programme.startMillis), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                            if (fantasyCount > 0) Row(Modifier.semantics { contentDescription = if (fantasyCount == 1) "1 fantasy roster player's team fixture" else "$fantasyCount fantasy roster players' team fixture" }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                val badgeColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                                Icon(Icons.Rounded.Star, null, Modifier.size(14.dp), tint = badgeColor)
                                                Text(fantasyCount.toString(), style = MaterialTheme.typography.labelSmall, color = badgeColor)
                                            }
                                        }
                                    }
                                }
                            }
                            if (now in initialStart until end) Box(Modifier.offset(x = ((now - initialStart) / 60_000f * 3).dp).width(2.dp).fillMaxHeight().background(BannerLive))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
      }
      if (!inline) detail?.let { (channel, programme) ->
        ModalBottomSheet(onDismissRequest = { detail = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Box(Modifier.fillMaxHeight(.85f)) {
                GuideProgrammeDetail(channel, programme, fantasyIndex, now, model, onSelect, onReplay) { detail = null }
            }
        }
      }
    }, secondary = {
        detail?.let { (channel, programme) ->
            GuideProgrammeDetail(channel, programme, fantasyIndex, now, model, onSelect, onReplay) { detail = null }
        } ?: EmptyState("Select a programme", "Choose a listing to see its description and watch options beside the guide.")
    })
}

@Composable
private fun GuideProgrammeDetail(
    channel: Channel, programme: Programme, fantasyIndex: com.alexdiab.stadiatv.data.fantasy.NativeFantasyEventIndex,
    now: Long, model: MobileViewModel, onSelect: ((Channel) -> Unit)?, onReplay: ((Channel, Programme) -> Unit)?, dismiss: () -> Unit,
) {
    val evidence = PhoneFantasyGuideEvidence(channel.key, model.container.guide.guideIdFor(channel), programme, fantasyIndex)
    val rostered = fantasyProgrammePlayers(evidence, model.fantasyGuideCache)
    key(channel.key, programme.startMillis, programme.endMillis, programme.title) {
      ScreenList {
                item("listing") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.canonicalName ?: channel.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = dismiss) { Icon(Icons.Rounded.Close, "Close programme details") }
                    }
                    val status = phoneProgrammeStatus(programme, now)
                    StatusPill(status.label, status == PhoneProgrammeStatus.OnNow)
                    Text(programme.title, style = MaterialTheme.typography.headlineMedium)
                    Text("${clockTime(programme.startMillis)} – ${clockTime(programme.endMillis)} · ${dateLabel(programme.startMillis)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rostered.isNotEmpty()) {
                    item("fantasy-title") { SectionTitle("Your fantasy roster"); Text("This playlist listing names the team fixture, not individual participation or points.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(rostered, key = { "fantasy-${it.entry.id}" }) { person -> NativeFantasyPlayerIdentity(person.entry.player, fantasyIndex.owner!!.sport, model) }
                }
                programme.description?.let { item("description") { Text(it) } }
                if (programme.endMillis <= now) item("replay") {
                    val replay = model.replayAvailability(channel, programme)
                    if (replay.available) Button(onClick = {
                        if (onReplay != null) { dismiss(); onReplay(channel, programme) }
                        else if (model.playReplay(channel, programme)) dismiss()
                    }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Replay, null); Text("Watch replay") }
                    Text(replay.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item("watch") {
                    if (!programme.isOn(now)) Text("Watch channel opens its current broadcast, not this listing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { dismiss(); if (onSelect == null) model.play(channel) else onSelect(channel) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PlayArrow, null); Text(if (programme.isOn(now)) "Watch now" else "Watch channel") }
                }
      }
    }
}
