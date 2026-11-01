package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(model: MobileViewModel) {
    val rawFeed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val feed = remember(rawFeed, library.hiddenGameKeys) { rawFeed.visibleGames(library.hiddenGameKeys) }
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    val history by model.container.watchHistory.history.collectAsStateWithLifecycle()
    val fantasy by model.container.fantasy.state.collectAsStateWithLifecycle()
    val localFantasy by model.nativeFantasy.state.collectAsStateWithLifecycle()
    val fantasyIndex by model.nativeFantasyContexts.collectAsStateWithLifecycle()
    var sport by rememberSaveable { mutableStateOf("For you") }
    var schedule by rememberSaveable { mutableStateOf("Today") }
    var scheduleLimit by rememberSaveable(sport, schedule) { mutableIntStateOf(12) }
    var featuredKey by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val primary = remember(feed.all, now, featuredKey, settings) { phoneDeskFeaturedMatch(feed.all, now, featuredKey, settings::involvesFavorite, settings::inScope) }
    LaunchedEffect(primary?.key) { featuredKey = primary?.key }
    val scoped = remember(feed.all, sport, settings) { phoneDeskScope(feed.all, sport, settings::inScope) }
    val live = remember(scoped, primary?.key, settings) { phoneDeskLive(scoped, primary?.key, settings::involvesFavorite) }
    val liveCount = scoped.count { it.state == GameState.LIVE }
    val scheduled = remember(scoped, schedule, now) { phoneDeskSchedule(scoped, schedule, now) }
    val unavailable = phoneFeedUnavailable(feed)
    val nativeIndex = phoneNativeFantasyIndex(fantasyIndex, localFantasy, now)
    val nativeSummary = phoneFantasyHomeSummary(nativeIndex, library.hiddenGameKeys, now)
    val favorites = remember(playlists.revision, settings.favoriteChannelIds) {
        val resolved = playlists.channels.filter { it.key in settings.favoriteChannelIds }.associateBy { it.key }
        settings.favoriteChannelIds.take(20).mapNotNull(resolved::get)
    }
    val recent = remember(history, playlists.revision) { model.container.watchHistory.resolved(playlists.channels).take(8) }

    PhoneAdaptiveLazyFeed(sectionSpacing = 12.dp, primary = {
        item("desk-heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your live desk", style = MaterialTheme.typography.headlineSmall)
                    Text(Dates.format("EEEE, MMMM d", now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { model.open(PhonePage.Teams) }) { Icon(Icons.Rounded.StarOutline, "Manage followed teams and leagues") }
            }
        }
        if (unavailable) item("feed-status") {
            AssistChip(onClick = { model.refresh() }, label = { Text(phoneFeedFailureLabel(feed)) }, leadingIcon = { Icon(Icons.Rounded.CloudOff, null) })
        }
        if (feed.isLoading && feed.all.isEmpty()) item("loading") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Checking scores and schedules…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        primary?.let { match -> item("hero") {
            FeaturedMatchHero(match, model, now, heading = when {
                settings.involvesFavorite(match) -> "YOUR TEAM"
                match.state == GameState.LIVE -> "ON NOW"
                else -> "NEXT UP"
            })
        } }
        item("live-heading") { SectionTitle(if (sport == "For you" && settings.isPersonalised) "Following live" else "Live now", "All live", { model.openLive(PhoneLiveSection.Sports) }) }
        item("sports-filter") { ChoiceRow(listOf("For you", "All sports") + League.groups.map { it.label }, sport) { sport = it } }
        if (live.isNotEmpty()) {
            items(live.take(4), key = { "live-${it.key}" }) { CinematicEventRow(it, model) }
            if (live.size > 4) item("more-live") { TextButton(onClick = { model.openLive(PhoneLiveSection.Sports) }, Modifier.fillMaxWidth()) { Text("See all live games") } }
        } else if (liveCount > 0) item("live-featured") {
            Text("Your live game is featured above.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (feed.isLoading && !unavailable) {
            if (feed.all.isNotEmpty()) item("live-checking") { Text("Checking live games in this view…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else item("live-empty") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when { unavailable -> "Live scores unavailable"; sport == "For you" && settings.isPersonalised -> "No followed games live right now"; else -> "No live games in this view" }, style = MaterialTheme.typography.titleMedium)
                Text(if (unavailable) "Retry to check current scores. Saved games remain available." else "The schedule below keeps the next games in view.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (sport != "All sports" && feed.liveNow.isNotEmpty()) TextButton(onClick = { sport = "All sports" }) { Text("Show all sports") }
            }
        }
    }, secondary = {
        item("schedule-title") { SectionTitle("Schedule", "Following", { model.selectTab(PhoneTab.Following) }) }
        item("schedule-days") { SegmentedChoices(listOf("Today", "Tomorrow", "Weekend"), schedule) { schedule = it } }
        item("schedule-context") { Text("${if (sport == "For you") { if (settings.isPersonalised) "Your teams & leagues" else "All sports" } else sport} · Local start times", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (scheduled.isEmpty()) item("schedule-empty") {
            PhoneCard {
                Text(if (feed.isLoading && !unavailable) "Checking this schedule…" else phoneScheduleEmptyTitle(feed, "No games in this view for ${schedule.lowercase()}."), style = MaterialTheme.typography.bodyMedium)
                when {
                    unavailable -> TextButton(onClick = { model.refresh() }) { Text("Retry schedule") }
                    !feed.isLoading && sport != "All sports" -> TextButton(onClick = { sport = "All sports" }) { Text("Show all sports") }
                }
            }
        }
        scheduled.take(scheduleLimit).groupBy { Dates.startOfDay(it.startMillis) }.forEach { (day, games) ->
            if (schedule != "Today") item("day-$day") { Overline(Dates.format("EEEE, MMMM d", day)) }
            items(games, key = { "schedule-${it.key}" }) { CinematicEventRow(it, model) }
        }
        if (scheduled.size > scheduleLimit) item("more-schedule") {
            TextButton(onClick = { scheduleLimit += 12 }, Modifier.fillMaxWidth()) { Text("Show more games (${scheduled.size - scheduleLimit})") }
        }
        if (settings.favoriteTeams.isEmpty()) item("follow-teams") {
            PhoneCard(onClick = { model.open(PhonePage.Teams) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.StarOutline, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text("Make this your desk", style = MaterialTheme.typography.titleMedium); Text("Follow teams to put their live games first.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        if (playlists.isEmpty) item("playlist") {
            PhoneCard(onClick = { model.open(PhonePage.PlaylistForm()) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LiveTv, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text("Bring your channels", style = MaterialTheme.typography.titleMedium); Text("Connect a playlist to watch your games.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Icon(Icons.Rounded.Add, null)
                }
            }
        }
        if (recent.isNotEmpty()) {
            item("recent-title") { SectionTitle("Recently watched", "History", { model.open(PhonePage.History) }) }
            item("recent") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(recent, key = { it.channel.key }) { ChannelResumeCard(it.channel, model) } } }
        }
        if (favorites.isNotEmpty()) item("favorite-channels") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Your channels", "Guide", { model.openLive(PhoneLiveSection.Guide) })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(favorites, key = { it.key }) { channel ->
                    Column(Modifier.width(80.dp).clip(RoundedCornerShape(12.dp)).clickable { model.play(channel) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Logo(channel.logoUrl, channel.name, 44.dp)
                        Text(channel.canonicalName ?: channel.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                } }
            }
        }
        if (library.fantasyDisplay.showHome && (fantasy.isConnected || localFantasy.leagues.isNotEmpty() || library.sleeperAccount != null)) item("fantasy") {
            HomeFantasyContextCard(nativeSummary, fantasy.snapshot?.matchup?.let { "${it.me.name} vs ${it.opponent?.name ?: "Bye week"}" }
                ?: nativeIndex.owner?.leagueName ?: fantasy.snapshot?.leagueName ?: "Your leagues & rosters", model)
        }
        item("news-title") { SectionTitle("Around your leagues", "Discover", { model.selectTab(PhoneTab.Discover) }) }
        item("news") { AsyncSection(settings.selectedLeagueIds, { model.container.news.latest(settings.followedLeagues, perLeague = 2).take(3) }) { news ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { news.forEach { StoryCard(it, model) } }
        } }
    })
}

@Composable
fun Overline(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, letterSpacing = 0.7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
fun SegmentedChoices(choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().clip(PhoneShape.panel).background(MaterialTheme.colorScheme.surface).padding(4.dp)) {
        choices.forEach { title ->
            Box(Modifier.weight(1f).clip(PhoneShape.control)
                .background(if (title == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .selectable(selected = title == selected, role = Role.Tab, onClick = { onSelect(title) })
                .heightIn(min = PhoneSpace.touchTarget).padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(title, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = if (title == selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A compact, stable broadcast feature. Real crests are the artwork until verified event photography exists. */
@Composable
fun FeaturedMatchHero(match: Match, model: MobileViewModel, now: Long, heading: String = "TOP MATCH") {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val confirmed = sources[match.key]?.let { model.container.streams.sourcesFor(match).confirmed.firstOrNull() }
    val hidden = settings.spoilerFreeMode && match.state == GameState.FINAL
    val fontScale = LocalDensity.current.fontScale
    Surface(shape = PhoneShape.feature, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        BoxWithConstraints(Modifier.padding(20.dp)) {
            // Three-digit basketball scores need independent columns even at normal phone text size.
            val stack = maxWidth < 300.dp || fontScale > 1.25f || match.league.group == SportGroup.Basketball
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$heading · ${match.league.shortName}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StatusPill(match.state.label, match.state == GameState.LIVE)
                }
                Column(Modifier.fillMaxWidth().clip(PhoneShape.control).clickable { model.open(PhonePage.Game(match)) }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (match.league.group in setOf(SportGroup.Racing, SportGroup.Golf)) {
                        Text(match.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        match.venue?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else if (stack) {
                        match.displaySides.forEach { side ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Logo(side.logoUrl, side.abbreviation, 40.dp)
                                Text(side.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                if (!hidden && match.state != GameState.PRE) Text(side.score ?: "—", style = PhoneType.score)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            match.displaySides.forEachIndexed { index, side ->
                                if (index == 1) Column(Modifier.weight(1.15f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (hidden) "—" else if (match.state == GameState.PRE) "VS" else match.displayScore, style = PhoneType.score, textAlign = TextAlign.Center)
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Logo(side.logoUrl, side.abbreviation, 52.dp)
                                    Text(side.shortName, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    Text(when {
                        hidden -> "Final · Score hidden"
                        match.state == GameState.PRE -> phoneDeskUpcomingStatus(match, now)
                        else -> listOfNotNull(match.live.period, match.live.clock, match.statusDetail.takeIf { it != match.live.period }).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val actions: @Composable RowScope.() -> Unit = {
                    FilledTonalButton(onClick = { model.open(PhonePage.Game(match)) }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Game Centre") }
                    if (match.state == GameState.LIVE && confirmed != null) Button(onClick = {
                        val current = model.container.streams.sourcesFor(match).confirmed.firstOrNull()
                        if (current != null) model.play(current.channel, match) else model.open(PhonePage.Game(match))
                    }, Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Watch live") }
                }
                if (stack) {
                    if (match.state == GameState.LIVE && confirmed != null) Button(onClick = {
                        val current = model.container.streams.sourcesFor(match).confirmed.firstOrNull()
                        if (current != null) model.play(current.channel, match) else model.open(PhonePage.Game(match))
                    }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Rounded.PlayArrow, null); Text("Watch live") }
                    FilledTonalButton(onClick = { model.open(PhonePage.Game(match)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Game Centre") }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                if (match.state == GameState.PRE) GameReminderButton(match, model)
            }
        }
    }
}

@Composable
fun CompactGameRow(match: Match, model: MobileViewModel) { CinematicEventRow(match, model) }

@Composable
private fun ChannelResumeCard(channel: Channel, model: MobileViewModel) {
    val guide by model.container.guide.state.collectAsStateWithLifecycle()
    val programme = remember(channel.key, guide.revision) { model.container.guide.nowAndNext(channel).first }
    Surface(color = MaterialTheme.colorScheme.surface, shape = PhoneShape.panel) {
        Column(Modifier.width(220.dp).clickable { model.play(channel) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Logo(channel.logoUrl, channel.name, 38.dp); Spacer(Modifier.weight(1f)); Icon(Icons.Rounded.PlayCircle, "Watch channel", tint = MaterialTheme.colorScheme.primary) }
            Text(channel.canonicalName ?: channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(programme?.title ?: "Programme information unavailable", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            programme?.let { LinearProgressIndicator(progress = { it.progress(System.currentTimeMillis()) }, Modifier.fillMaxWidth().height(3.dp), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh) }
        }
    }
}
