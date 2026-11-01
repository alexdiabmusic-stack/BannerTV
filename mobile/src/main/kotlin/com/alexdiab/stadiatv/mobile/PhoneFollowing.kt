package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.delay

@Composable
private fun followingClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    return now
}

@Composable
fun FollowingScreen(model: MobileViewModel) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val rawFeed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    val now = followingClock()
    var section by rememberSaveable { mutableStateOf("Matches") }
    var day by rememberSaveable { mutableStateOf("Overview") }
    var entity by rememberSaveable { mutableStateOf("All") }
    var sport by rememberSaveable { mutableStateOf("All sports") }
    val leagueChips = settings.explicitlyFollowedLeagues.filter { league -> settings.favoriteTeams.none { it.leaguePath == league.path } }
    val selection = entity.takeIf { it == "All" || settings.favoriteTeams.any { team -> team.key == it } || leagueChips.any { league -> league.path == it } } ?: "All"
    val matches = remember(rawFeed.all, settings, library.hiddenGameKeys, selection) {
        followingMatches(rawFeed.all.filterNot { it.key in library.hiddenGameKeys }, settings, selection)
    }
    val overview = remember(matches, now) { followingOverview(matches, now) }
    val sports = overview.upcoming.map { it.league.group.label }.distinct()
    val activeSport = sport.takeIf { it in sports } ?: "All sports"
    val coming = overview.upcoming.filter { activeSport == "All sports" || it.league.group.label == activeSport }
    val dayStart = Dates.addDays(Dates.startOfDay(now), when (day) { "Yesterday" -> -1; "Tomorrow" -> 1; else -> 0 })
    val dayGames = matches.filter { it.startMillis in dayStart until Dates.addDays(dayStart, 1) }
    val favoriteChannels = remember(playlists.revision, settings.favoriteChannelIds) {
        val byKey = playlists.channels.filter { it.key in settings.favoriteChannelIds }.associateBy { it.key }
        settings.favoriteChannelIds.mapNotNull(byKey::get)
    }
    PhoneAdaptiveLazyFeed(sectionSpacing = 18.dp, primary = {
        item("sections") { ChoiceRow(listOf("Matches", "Fantasy", "Channels"), section) { section = it } }
        when (section) {
            "Matches" -> {
                item("teams") { SectionTitle("Your teams & leagues", "Edit", { model.open(PhonePage.Teams) }) }
                item("entities") { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selection == "All", { entity = "All" }, label = { Text("All") }) }
                    items(settings.favoriteTeams, key = { it.key }) { team -> FilterChip(selection == team.key, { entity = team.key }, label = { Text(team.abbreviation.ifBlank { team.name }) }, leadingIcon = { Logo(team.logoUrl, team.abbreviation, 24.dp) }) }
                    items(leagueChips, key = { it.path }) { league -> FilterChip(selection == league.path, { entity = league.path }, label = { Text(league.shortName) }) }
                    item { IconButton(onClick = { model.open(PhonePage.Teams) }) { Icon(Icons.Rounded.Add, "Manage following") } }
                } }
                if (!settings.isPersonalised) item("follow-empty") { EmptyState("Make it your sports", "Follow a team or league to see your live games, schedule, news and standings here.", "Follow teams", { model.open(PhonePage.Teams) }) }
                else {
                    if (rawFeed.isLoading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (rawFeed.refreshFailed || rawFeed.error != null) item("feed-error") { TextButton(onClick = { model.refresh() }) { Text("Scores couldn't refresh · Retry") } }
                    item("summary") { PhoneCard {
                        Overline("Your sports")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("Live" to overview.live.size, "Today" to overview.today, "This week" to overview.thisWeek).forEach { (label, count) -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (phoneFeedHasKnownCounts(rawFeed)) "$count" else "–", style = MaterialTheme.typography.headlineMedium, color = if (label == "Live" && count > 0) BannerLive else MaterialTheme.colorScheme.onSurface)
                                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        overview.upcoming.firstOrNull()?.let { Text("Next · ${it.shortName}\n${dateLabel(it.startMillis)} · ${clockTime(it.startMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } }
                    item("days") { ChoiceRow(listOf("Overview", "Yesterday", "Today", "Tomorrow"), day) { day = it } }
                    if (day == "Overview") {
                        val hero = overview.live.firstOrNull() ?: overview.upcoming.firstOrNull()
                        if (hero != null) item("hero") { FeaturedMatchHero(hero, model, now, if (hero.state == GameState.LIVE) "LIVE" else "UP NEXT") }
                        else if (!rawFeed.isLoading) item("no-events") { EmptyState(phoneScheduleEmptyTitle(rawFeed, "Between the action"), if (phoneFeedUnavailable(rawFeed)) "The schedule couldn't refresh. Reconnect or retry to check this selection." else "No announced upcoming events were returned for this selection.") }
                        if (overview.live.size > 1) {
                            item("other-live-title") { Overline("Also live") }
                            items(overview.live.drop(1), key = { "live-${it.key}" }) { CompactGameRow(it, model) }
                        }
                        item("coming-title") { SectionTitle("Coming up", "Full schedule", { model.open(PhonePage.FollowingSchedule(selection, activeSport)) }) }
                        if (sports.size > 1) item("sports") { ChoiceRow(listOf("All sports") + sports, activeSport) { sport = it } }
                        if (coming.isEmpty()) item("coming-empty") { Text(phoneScheduleEmptyTitle(rawFeed, "No upcoming events scheduled."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        coming.take(6).groupBy { Dates.startOfDay(it.startMillis) }.forEach { (date, games) ->
                            item("date-$date") { Overline(followingDateLabel(date, now)) }
                            items(games, key = { "coming-${it.key}" }) { FollowingEventRow(it, model) }
                        }
                    } else {
                        if (dayGames.isEmpty() && !rawFeed.isLoading) item("no-games") { EmptyState("Nothing scheduled", "No games were returned for this selection and day.") }
                        items(dayGames, key = { it.key }) { GameCard(it, model) }
                    }
                }
            }
            "Fantasy" -> item("fantasy") { FantasyContent(model) }
            else -> {
                if (favoriteChannels.isEmpty()) item("no-channels") { EmptyState("Your channels, one tap away", "Tap the star on any live channel to save it here.") }
                items(favoriteChannels, key = { it.key }) { ChannelRow(it, model) }
            }
        }
    }, secondary = {
        if (section == "Matches" && settings.isPersonalised) item("snapshots") { FollowingSnapshots(model, selection) }
    })
}

private fun followingDateLabel(date: Long, now: Long): String = when (date) {
    Dates.startOfDay(now) -> "Today"
    Dates.addDays(Dates.startOfDay(now), 1) -> "Tomorrow"
    else -> Dates.format("EEEE · MMM d", date)
}

@Composable
fun FollowingScheduleScreen(model: MobileViewModel, entity: String, initialSport: String?) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val feed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val now = followingClock()
    val matches = remember(feed.all, settings, entity, library.hiddenGameKeys, now) { followingOverview(followingMatches(feed.all.filterNot { it.key in library.hiddenGameKeys }, settings, entity), now).upcoming }
    var sport by rememberSaveable(entity, initialSport) { mutableStateOf(initialSport ?: "All sports") }
    val sports = matches.map { it.league.group.label }.distinct()
    val activeSport = sport.takeIf { it in sports } ?: "All sports"
    val visible = matches.filter { activeSport == "All sports" || it.league.group.label == activeSport }
    ScreenList {
        item("filters") { ChoiceRow(listOf("All sports") + sports, activeSport) { sport = it } }
        item("source") { Text("Announced games from your followed teams and leagues. Times are local; providers may not have published the full season yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (feed.isLoading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (visible.isEmpty() && !feed.isLoading) item("empty") { EmptyState("No upcoming events", "Try another sport or refresh your schedule.", "Refresh", { model.refresh() }) }
        visible.groupBy { Dates.startOfDay(it.startMillis) }.forEach { (date, games) ->
            item("date-$date") { Overline(followingDateLabel(date, now)) }
            items(games, key = { it.key }) { FollowingEventRow(it, model) }
        }
    }
}

@Composable
private fun FollowingEventRow(match: Match, model: MobileViewModel) {
    val sources by model.container.streams.sources.collectAsStateWithLifecycle()
    val count = sources[match.key]?.let { model.container.streams.sourcesFor(match).confirmed.size } ?: 0
    PhoneCard {
        Row(Modifier.fillMaxWidth().clickable { model.open(PhonePage.Game(match)) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(68.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(clockTime(match.startMillis), style = MaterialTheme.typography.labelLarge); Text(match.league.shortName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (match.league.group in setOf(SportGroup.Racing, SportGroup.Golf)) Text(match.name, style = MaterialTheme.typography.titleSmall)
                else match.displaySides.forEach { side -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Logo(side.logoUrl, side.abbreviation, 24.dp); Text(side.shortName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                if (count > 0) Text("$count confirmed ${if (count == 1) "stream" else "streams"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FollowingSnapshots(model: MobileViewModel, entity: String) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val tables by model.followingTables.state.collectAsStateWithLifecycle()
    val news by model.followingNews.state.collectAsStateWithLifecycle()
    val target = followingStandingsTarget(settings, entity)
    val paths = followingNewsLeagues(settings, entity).map { it.path }
    LaunchedEffect(target?.league?.path, paths) {
        target?.let { model.followingTables.refresh(it.league.path) }
        if (paths.isNotEmpty()) model.followingNews.refresh(paths)
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (paths.isNotEmpty()) {
            val state = news[paths]
            val stories = state?.value.orEmpty().filterNot { it.id in library.hiddenStoryIds || it.source in library.mutedNewsSources }
            SectionTitle("Around your teams", "Discover", { model.selectTab(PhoneTab.Discover) })
            SnapshotStatus(state, { model.followingNews.refresh(paths, true) })
            if (state?.value != null && stories.isEmpty()) Text("No visible headlines. You can manage hidden stories and muted sources in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(stories, key = { it.id }) { story -> Box(Modifier.width(270.dp)) { StoryCard(story, model) } } }
        }
        if (target != null) {
            val state = tables[target.league.path]
            val snapshot = followingStandings(state?.value.orEmpty(), target.team)
            SectionTitle("Your standings · ${target.league.shortName}", "Full table", { model.open(PhonePage.LeagueHub(target.league, "Standings")) })
            SnapshotStatus(state, { model.followingTables.refresh(target.league.path, true) })
            if (snapshot != null) PhoneCard {
                Overline(snapshot.name)
                snapshot.rows.forEach { standing ->
                    val row = standing.row
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (standing.highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else androidx.compose.ui.graphics.Color.Transparent).clickable {
                        if (target.league.group == SportGroup.Racing) model.open(PhonePage.LeagueHub(target.league, "Standings"))
                        else model.open(PhonePage.TeamDetail(Team(row.teamId, row.teamName, row.teamName, row.abbreviation, row.logoUrl, target.league)))
                    }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${standing.rank}", Modifier.width(22.dp), style = MaterialTheme.typography.labelLarge, color = if (standing.highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Logo(row.logoUrl, row.abbreviation, 24.dp)
                        Text(row.abbreviation.ifBlank { row.teamName }, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = if (standing.highlighted) FontWeight.ExtraBold else FontWeight.Normal)
                        Text(row.values["points"]?.takeIf { it.isNotBlank() } ?: row.record, style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else if (state?.loading == false && !state.failed) Text("Standings not available right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> SnapshotStatus(state: PhoneSnapshot<T>?, retry: () -> Unit) {
    if (state == null || state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state?.failed == true) TextButton(onClick = retry) { Text(if (state.value == null) "Couldn't load · Retry" else "Saved snapshot · Refresh failed · Retry") }
    if (state != null && state.checkedAt > 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Checked ${dateLabel(state.checkedAt)} · ${clockTime(state.checkedAt)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.loading && !state.failed) IconButton(onClick = retry) { Icon(Icons.Rounded.Refresh, "Refresh snapshot", Modifier.size(18.dp)) }
    }
}
