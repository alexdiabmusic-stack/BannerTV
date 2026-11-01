package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Calendar


@Composable
fun ChoiceRow(choices: List<String>, selected: String, onSelect: (String) -> Unit) {
    ChoiceRow(choices, selected, enabled = true, onSelect = onSelect)
}

@Composable
fun ChoiceRow(choices: List<String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(choices, key = { it }) { FilterChip(selected = it == selected, enabled = enabled, onClick = { onSelect(it) }, label = { Text(it) }) }
    }
}

@Composable
fun DiscoverScreen(model: MobileViewModel) {
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    var leagueId by rememberSaveable { mutableStateOf<String?>(null) }
    var newsLimit by rememberSaveable(leagueId) { mutableIntStateOf(20) }
    val league = League.find(leagueId)
    ScreenList {
        item("intro") { Text("Go beyond the score.", style = MaterialTheme.typography.headlineLarge) }
        item("entertainment") { PhoneCard(onClick = { model.open(PhonePage.Cinema) }) {
            Text("Movies & shows", style = MaterialTheme.typography.titleLarge)
            Text("Discover your next watch. Movies, series and your saved library.", style = MaterialTheme.typography.bodyMedium)
        } }
        item("audio") { OutlinedButton(onClick = { model.open(PhonePage.Podcasts) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Podcasts, null); Spacer(Modifier.width(8.dp)); Text("Podcasts & your listening library") } }
        item("stats") { PhoneCard(onClick = { model.open(PhonePage.Stats(league?.path)) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Insights, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) { Text("Stats", style = MaterialTheme.typography.titleMedium); Text("Standings, leaders, rosters & profiles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Rounded.ChevronRight, null)
            }
        } }
        item("leagues") { ChoiceRow(listOf("For you") + League.ALL.map { it.shortName }, league?.shortName ?: "For you") { pick -> leagueId = League.ALL.firstOrNull { it.shortName == pick }?.path } }
        item("browse-title") { SectionTitle("Explore the leagues") }
        item("browse") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(League.ALL, key = { it.path }) { item ->
                    OutlinedCard(onClick = { model.open(PhonePage.LeagueHub(item)) }, modifier = Modifier.width(132.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Rounded.Sports, null, tint = MaterialTheme.colorScheme.primary); Text(item.shortName, style = MaterialTheme.typography.titleMedium); Text("Scores · Stats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item("news-title") { SectionTitle("Headlines", "Saved", { model.open(PhonePage.SavedArticles) }) }
        item("news") {
            AsyncSection(leagueId to settings.selectedLeagueIds, { if (league != null) model.container.news.forLeague(league, 100) else model.container.news.latest(settings.followedLeagues, perLeague = 20) }) { stories ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { stories.take(newsLimit).forEach { StoryCard(it, model) }; if (newsLimit < stories.size) OutlinedButton(onClick = { newsLimit += 20 }, Modifier.fillMaxWidth()) { Text("Load more stories") } }
                if (stories.isEmpty()) EmptyState("No stories yet", "Check back for the latest news.")
            }
        }
    }
}


@Composable
fun HistoryScreen(model: MobileViewModel) {
    val history by model.container.watchHistory.history.collectAsStateWithLifecycle()
    val playlists by model.container.playlists.state.collectAsStateWithLifecycle()
    val resolved = remember(history, playlists.revision) { model.container.watchHistory.resolved(playlists.channels) }
    ScreenList {
        if (resolved.isEmpty()) item { EmptyState("Nothing watched yet", "Channels you play will appear here.") }
        items(resolved, key = { it.channel.key }) { entry -> ChannelRow(entry.channel, model) }
        if (resolved.isNotEmpty()) item { TextButton(onClick = { model.container.watchHistory.clear() }) { Text("Clear watch history") } }
    }
}

@Composable
fun SavedArticlesScreen(model: MobileViewModel) {
    val library by model.library.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf("Saved") }
    val scope = rememberCoroutineScope()
    val stories = if (section == "Offline") library.offlineStories else library.savedStories
    ScreenList {
        item { ChoiceRow(listOf("Saved", "Offline"), section) { section = it } }
        if (stories.isEmpty()) item { EmptyState("A good read for later", if (section == "Offline") "Download a readable article from its reader to keep a local copy." else "Bookmark a story in Discover to save it here.") }
        items(stories, key = { it.id }) { story -> Column { StoryCard(story.article(), model, ignoreFilters = true); if (section == "Offline") TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { model.articleStore.remove(story.id) }; model.library.update { it.copy(offlineStories = it.offlineStories.filterNot { saved -> saved.id == story.id }) } } }) { Text("Remove offline copy") } } }
    }
}
