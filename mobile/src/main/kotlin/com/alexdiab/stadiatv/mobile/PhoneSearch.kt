package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import com.alexdiab.stadiatv.domain.Article

private data class SearchHit(val id: String, val title: String, val subtitle: String, val image: String?, val rank: Int, val action: () -> Unit)

@Composable
fun SearchScreen(model: MobileViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var settled by remember { mutableStateOf("") }
    val feed by model.container.matchFeed.sections.collectAsStateWithLifecycle()
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    val directory by model.searchDirectory.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scope by rememberSaveable { mutableStateOf("All") }
    var stories by remember { mutableStateOf(emptyList<Article>()) }
    var podcasts by remember { mutableStateOf(emptyList<PodcastShow>()) }
    var contentLoading by remember { mutableStateOf(false) }
    LaunchedEffect(query) { delay(250); settled = query.trim(); if (settled.length >= 2) model.prepareSearch() }
    LaunchedEffect(settled) {
        stories = emptyList(); podcasts = emptyList()
        if (settled.length < 2) { contentLoading = false; return@LaunchedEffect }
        contentLoading = true
        try { withContext(Dispatchers.IO) { coroutineScope {
            val news = async { try { model.container.news.latest(model.container.preferences.current.followedLeagues, perLeague = 12) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() } }
            val shows = async { model.podcasts.search(settled) }
            stories = news.await(); podcasts = shows.await()
        } } } finally { contentLoading = false }
    }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    LaunchedEffect(settled, feed.all, playlist.revision, directory, library, stories, podcasts, scope) {
        if (settled.length < 2) { hits = emptyList(); return@LaunchedEffect }
        hits = withContext(Dispatchers.Default) {
        buildList {
            fun add(id: String, title: String, subtitle: String, image: String? = null, fields: String = "", boost: Int = 0, action: () -> Unit) {
                val type = when (id.substringBefore(':')) { "team", "league" -> "Teams & leagues"; "player" -> "Players"; "game" -> "Games"; "channel" -> "Channels"; "story" -> "Articles"; "podcast" -> "Podcasts"; else -> "Settings" }
                if (scope != "All" && type != scope) return
                val rank = searchRank(settled, title, "$subtitle $fields")
                if (rank > 0) this@buildList.add(SearchHit(id, title, subtitle, image, rank + boost, action))
            }
            directory.teams.forEach { team -> add("team:${team.league.path}:${team.id}", team.name, "Team · ${team.league.shortName}", team.logoUrl, "${team.abbreviation} ${team.shortName}", 24) { model.open(PhonePage.TeamDetail(team)) } }
            directory.players.forEach { (league, person) -> add("player:${league.path}:${person.id}", person.name, "Player · ${league.shortName} · ${person.position}", person.headshotUrl, boost = 20) { model.open(PhonePage.PlayerDetail(league, person)) } }
            com.alexdiab.stadiatv.domain.League.ALL.forEach { league -> add("league:${league.path}", league.name, "League · ${league.group.label}", fields = "${league.shortName} ${league.keywords.joinToString(" ")}", boost = 18) { model.open(PhonePage.LeagueHub(league)) } }
            feed.all.forEach { match -> add("game:${match.key}", match.name, "Game · ${match.league.shortName} · ${dateLabel(match.startMillis)}", match.home.logoUrl, "${match.home.abbreviation} ${match.away.abbreviation}", 12) { model.open(PhonePage.Game(match)) } }
            playlist.channels.forEach { channel -> add("channel:${channel.key}", channel.canonicalName ?: channel.name, "Channel · ${channel.group}", channel.logoUrl, channel.name, 8) { model.play(channel) } }
            (library.savedStories.map { it.article() } + stories).distinctBy { it.id }.filter { it.id !in library.hiddenStoryIds && it.source !in library.mutedNewsSources }.forEach { story -> add("story:${story.id}", story.headline, "Article · ${story.source}", story.imageUrl, story.description) { model.open(PhonePage.Reader(story)) } }
            podcasts.forEach { show -> add("podcast:${show.feed}", show.title, "Podcast · ${show.publisher}", show.image, "${show.sport} ${show.tags.joinToString(" ")}") { model.open(PhonePage.Podcast(show)) } }
            listOf("Playlists and provider login" to PhonePage.Playlists, "Teams and leagues" to PhonePage.Teams, "Stats · standings, leaders and rosters" to PhonePage.Stats(), "Fantasy · Banner, ESPN & Sleeper" to PhonePage.Fantasy, "Cloud backup & restore" to PhonePage.CloudBackup, "Custom channel groups" to PhonePage.ChannelGroups, "Watch history" to PhonePage.History, "Saved articles" to PhonePage.SavedArticles).forEach { (title, page) -> add("setting:$title", title, "Settings shortcut") { model.open(page) } }
            add("settings", "Playback, captions, buffering and appearance", "Settings", fields = "quality video spoiler score delay theme") { model.selectTab(PhoneTab.Settings) }
        }.sortedWith(compareByDescending<SearchHit> { it.rank }.thenBy { it.title }).take(100)
        }
    }
    Box(Modifier.imePadding()) { ScreenList {
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search BannerTV") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }, singleLine = true) }
        item { ChoiceRow(listOf("All", "Teams & leagues", "Players", "Games", "Channels", "Articles", "Podcasts", "Settings"), scope) { scope = it } }
        if (contentLoading) item { Text("Searching articles and podcast feeds…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (directory.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Loading team directories and followed-team players…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (settled.length >= 2 && !directory.loading && directory.teams.isEmpty()) item { TextButton(onClick = { model.prepareSearch(force = true) }) { Text("Retry team and player directory") } }
        if (settled.length < 2) item { EmptyState("Find your next watch", "Search teams, games, channels, followed-team players, current articles, saved stories, podcasts and settings. Exact names and prefixes appear first.") }
        else if (hits.isEmpty() && !directory.loading) item { EmptyState("No results", "Try a team name, league, player from a followed team, or a setting such as subtitles.") }
        items(hits, key = { it.id }) { hit -> ListItem(headlineContent = { Text(hit.title) }, supportingContent = { Text(hit.subtitle) }, leadingContent = { Logo(hit.image, hit.title, 36.dp) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) }, modifier = Modifier.clickable(onClick = hit.action), colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) }
    } }
}
