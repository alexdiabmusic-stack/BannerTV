@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PhonePodcasts(model: MobileViewModel) {
    val library by model.podcasts.state.collectAsStateWithLifecycle()
    val catalog by model.podcasts.catalog.collectAsStateWithLifecycle()
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf("For you") }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PodcastShow>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var topicId by rememberSaveable { mutableStateOf("all") }
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    val topics = remember(settings.favoriteTeams, settings.followedLeagues) { phonePodcastTopics(settings.favoriteTeams, settings.followedLeagues) }
    val topic = topics.firstOrNull { it.id == topicId } ?: topics.first()
    var teamResult by remember(topic.id) { mutableStateOf<com.alexdiab.stadiatv.data.news.TeamPodcastResult?>(null) }
    var teamLoading by remember(topic.id) { mutableStateOf(false) }
    var teamRefresh by remember(topic.id) { mutableIntStateOf(0) }
    var appliedTeamRefresh by remember(topic.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val audio by PhoneAudioService.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.podcasts.ensureCatalog() }
    LaunchedEffect(query) { if (query.trim().length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }; delay(350); searching = true; try { results = withContext(Dispatchers.IO) { model.podcasts.search(query) } } finally { searching = false } }
    LaunchedEffect(section) { if (section == "Downloads") while (true) { withContext(Dispatchers.IO) { model.podcasts.refreshDownloads() }; delay(3_000) } }
    LaunchedEffect(topic.id, section, query.trim().length >= 2, teamRefresh) {
        val team = topic.team
        if (team == null || section != "For you" || query.trim().length >= 2) return@LaunchedEffect
        teamLoading = true
        try { teamResult = model.podcasts.teamPodcasts(team, teamRefresh > appliedTeamRefresh); appliedTeamRefresh = teamRefresh }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { teamResult = com.alexdiab.stadiatv.data.news.TeamPodcastResult(emptyList(), null, true) }
        finally { teamLoading = false }
    }
    val rawShows = when {
        query.trim().length >= 2 -> results
        section == "Following" -> library.subscriptions
        topic.team != null && teamResult != null -> teamResult!!.shows.map { PodcastShow(it.feed, it.title, it.publisher, it.image, it.sport, it.tags) }
        else -> phonePodcastCatalog(topic, catalog, settings.followedLeagues)
    }
    val shows = if (alphabetical) rawShows.sortedBy { it.title.lowercase() } else rawShows
    ScreenList {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Your sports, in your ears.", style = MaterialTheme.typography.headlineMedium); Text("Publisher feeds · background listening · Android Auto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = { options = true }) { Icon(Icons.Rounded.Tune, "Listening settings") } } }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search podcasts worldwide") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } }) }
        item { ChoiceRow(listOf("For you", "Following", "Queue", "Downloads", "History"), section) { section = it; query = "" } }
        if (section == "For you" && query.trim().length < 2) item("topics") {
            ChoiceRow(topics.map { it.label }, topic.label) { label -> topicId = topics.first { it.label == label }.id }
        }
        if (section in setOf("For you", "Following") || query.trim().length >= 2) item("sort") {
            ChoiceRow(listOf("Recommended", "A to Z"), if (alphabetical) "A to Z" else "Recommended") { alphabetical = it == "A to Z" }
        }
        if (section == "For you" && topic.team != null && query.trim().length < 2) item("team-status") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("For ${topic.team.name}", if (teamLoading) null else "Refresh", { teamRefresh++ })
                Text("Team-specific public podcast search and curated publisher feeds.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (teamLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (teamResult?.unavailable == true) Text(if (shows.isEmpty()) "Podcast search is unavailable. Retry later or search by show name."
                    else "Some searches couldn't complete. Available recommendations are retained; Refresh retries without changing your library.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                teamResult?.checkedAt?.let { Text("Search checked ${snapshotTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (query.trim().length < 2 && section == "Queue") {
            if (library.queue.isEmpty()) item { EmptyState("Build your listening queue", "Add episodes from a show to line them up here.") }
            items(library.queue, key = { it.id }) { episode -> Column { EpisodeRow(episode, model); Row {
                val index = library.queue.indexOfFirst { it.id == episode.id }
                IconButton(enabled = index > 0, onClick = { model.podcasts.update { state -> val changed = state.queue.toMutableList(); changed.add(index - 1, changed.removeAt(index)); state.copy(queue = changed) }; scope.launch { model.audio.syncQueue() } }) { Icon(Icons.Rounded.ArrowUpward, "Move episode up") }
                IconButton(enabled = index < library.queue.lastIndex, onClick = { model.podcasts.update { state -> val changed = state.queue.toMutableList(); changed.add(index + 1, changed.removeAt(index)); state.copy(queue = changed) }; scope.launch { model.audio.syncQueue() } }) { Icon(Icons.Rounded.ArrowDownward, "Move episode down") }
                TextButton(enabled = episode.id != audio.episode?.id, onClick = { model.podcasts.update { it.copy(queue = it.queue.filterNot { e -> e.id == episode.id }) }; scope.launch { model.audio.syncQueue() } }) { Text("Remove") }
            } } }
        } else if (query.trim().length < 2 && section == "Downloads") {
            if (library.downloads.isEmpty()) item { EmptyState("Take the conversation offline", "Download an episode using its menu. Downloads default to Wi-Fi.") }
            items(library.downloads, key = { it.episode.id }) { download -> Column { EpisodeRow(download.episode, model); Row(verticalAlignment = Alignment.CenterVertically) { Text(if (download.complete) "Available offline" else if (download.error) "Download failed" else "Downloading…", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); TextButton(onClick = { model.podcasts.removeDownload(download.episode.id) }) { Text("Remove download") } } } }
        } else if (query.trim().length < 2 && section == "History") {
            if (library.history.isEmpty()) item { EmptyState("Your listening history", "Episodes you play will appear here with saved progress.") }
            items(library.history, key = { it.id }) { EpisodeRow(it, model) }
        } else {
            if (shows.isEmpty() && !searching && !teamLoading) item { EmptyState(if (section == "Following") "Follow your favorite shows" else "No shows found", "Search by show, sport or team. Your subscribed shows and downloads stay in your library.") }
            items(shows, key = { it.feed }) { show -> PodcastShowCard(show, model) }
        }
    }
    if (options) ModalBottomSheet(onDismissRequest = { options = false }) { Column(Modifier.padding(24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Listening settings", style = MaterialTheme.typography.titleLarge)
        SettingSwitch("Automatically play next episode", library.automaticallyAdvance) { value -> model.podcasts.update { it.copy(automaticallyAdvance = value) } }
        SettingSwitch("Download on Wi-Fi only", library.wifiDownloadsOnly) { value -> model.podcasts.update { it.copy(wifiDownloadsOnly = value) } }
        Text("Playback speed"); ChoiceRow(listOf("0.75", "1", "1.25", "1.5", "1.75", "2"), library.speed.toString().removeSuffix(".0")) { value -> model.podcasts.update { it.copy(speed = value.toFloat()) } }
        Text("Audio keeps playing with lock-screen and headset controls. Android Auto offers audio episodes only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable
private fun PodcastShowCard(show: PodcastShow, model: MobileViewModel) {
    val library by model.podcasts.state.collectAsStateWithLifecycle()
    PhoneCard(onClick = { model.open(PhonePage.Podcast(show)) }) { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(show.image, show.title, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(show.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(show.publisher.ifBlank { show.sport }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        IconToggleButton(library.subscriptions.any { it.feed == show.feed }, { model.podcasts.subscribe(show) }) { Icon(if (library.subscriptions.any { it.feed == show.feed }) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Follow podcast") }
    } }
}

@Composable
fun PhonePodcastShow(show: PodcastShow, model: MobileViewModel) {
    val library by model.podcasts.state.collectAsStateWithLifecycle()
    var refresh by remember(show.feed) { mutableIntStateOf(0) }
    var episodes by remember(show.feed) { mutableStateOf(model.podcasts.cachedEpisodes(show.feed)) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(show.feed, refresh) { loading = true; failed = false; try { episodes = model.podcasts.episodes(show, refresh > 0) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { failed = true } finally { loading = false } }
    ScreenList {
        item { PhoneCard { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Logo(show.image, show.title, 88.dp); Column(Modifier.weight(1f)) { Text(show.title, style = MaterialTheme.typography.headlineSmall); Text(show.publisher, color = MaterialTheme.colorScheme.onSurfaceVariant); FilledTonalButton(onClick = { model.podcasts.subscribe(show) }) { Text(if (library.subscriptions.any { it.feed == show.feed }) "Following" else "Follow show") } } } } }
        item { SectionTitle("Episodes", "Refresh", { refresh++ }) }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (failed) item { EmptyState("This publisher's feed couldn't load", "Try again or pick another show. Saved queue items and downloads remain available.", "Retry", { refresh++ }) }
        if (episodes.isEmpty() && !loading && !failed) item { EmptyState("No playable episodes", "The publisher has not supplied a supported audio or video enclosure.") }
        items(episodes, key = { it.id }) { EpisodeRow(it, model) }
    }
}

@Composable
private fun EpisodeRow(episode: PhoneEpisode, model: MobileViewModel) {
    var menu by remember { mutableStateOf(false) }
    val library by model.podcasts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    PhoneCard(onClick = { model.playPodcast(episode) }) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(episode.title, style = MaterialTheme.typography.titleMedium); Text(listOfNotNull(episode.published?.let(::dateLabel), episode.duration.takeIf { it > 0 }?.let { "${it / 60} min" }, if (episode.mime.startsWith("video")) "Video" else null).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { model.playPodcast(episode) }) { Icon(Icons.Rounded.PlayCircle, "Play episode", tint = MaterialTheme.colorScheme.primary) }
            Box { IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Episode actions") }; DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Add to queue") }, onClick = { menu = false; model.podcasts.enqueue(episode); scope.launch { model.audio.syncQueue() } })
                DropdownMenuItem(text = { Text("Download episode") }, onClick = { menu = false; scope.launch { try { withContext(Dispatchers.IO) { model.podcasts.download(episode) }; model.message = "Download requested. See Downloads for progress." } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) { model.message = error.message ?: "The download couldn't start." } } })
                DropdownMenuItem(text = { Text("Mark unplayed") }, onClick = { menu = false; model.podcasts.bookmark(episode.id, 0) })
            } }
        }
        if (episode.description.isNotBlank()) Text(episode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val progress = library.bookmarks[episode.id] ?: library.importedBookmarks[PhoneCloudBackup.episodeHash(episode.id)] ?: 0
        if (progress > 0 && episode.duration > 0) LinearProgressIndicator(progress = { (progress.toFloat() / (episode.duration * 1000)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp))
    }
}

@Composable
fun MiniAudioPlayer(model: MobileViewModel) {
    val audio by PhoneAudioService.ui.collectAsStateWithLifecycle()
    val episode = audio.episode ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) { Row(Modifier.fillMaxWidth().heightIn(min = 70.dp).clickable { model.open(PhonePage.AudioPlayer) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Logo(episode.image, episode.show, 46.dp)
        Column(Modifier.weight(1f)) { Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge); Text(episode.show, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = PhoneAudioService::toggle) { Icon(if (audio.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause podcast") }
        IconButton(onClick = PhoneAudioService::stop) { Icon(Icons.Rounded.Close, "Stop podcast") }
    } }
}

@Composable
fun PhoneAudioPlayer(model: MobileViewModel, pip: Boolean = false) {
    val audio by PhoneAudioService.ui.collectAsStateWithLifecycle()
    val library by model.podcasts.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    LaunchedEffect(Unit) { try { controller = model.audio.connect() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { model.message = "Audio controls couldn't connect. Reopen the episode to try again." } }
    val episode = audio.episode ?: run { EmptyState("Nothing playing", "Choose an episode from your listening library.", "Browse podcasts", { model.open(PhonePage.Podcasts) }); return }
    var seeking by remember(episode.id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().then(if (pip) Modifier.background(androidx.compose.ui.graphics.Color.Black) else Modifier.verticalScroll(rememberScrollState()).padding(24.dp)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (pip) 0.dp else 18.dp)) {
        if (episode.mime.startsWith("video")) AndroidView(factory = { PlayerView(it).apply { useController = false; player = controller } }, update = { it.player = controller }, onRelease = { it.player = null }, modifier = Modifier.fillMaxWidth().then(if (pip) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9)).clip(RoundedCornerShape(if (pip) 0.dp else 16.dp)))
        else AsyncImage(episode.image, episode.show, Modifier.size(220.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Crop)
        if (!pip) {
            Text(episode.show.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(episode.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Slider(value = seeking ?: audio.position.toFloat().coerceIn(0f, audio.duration.coerceAtLeast(1).toFloat()), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { PhoneAudioService.seek(it.toLong()) }; seeking = null }, valueRange = 0f..audio.duration.coerceAtLeast(1).toFloat(), enabled = audio.duration > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(audioTime(audio.position)); Text(audioTime(audio.duration)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = { scope.launch { model.audio.connect().seekToPreviousMediaItem() } }) { Icon(Icons.Rounded.SkipPrevious, "Previous episode") }
                TextButton(onClick = { PhoneAudioService.seek(audio.position - 15_000) }) { Text("−15s") }
                FilledIconButton(onClick = PhoneAudioService::toggle, Modifier.size(64.dp)) { if (audio.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else Icon(if (audio.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", Modifier.size(34.dp)) }
                IconButton(onClick = { PhoneAudioService.seek(audio.position + 30_000) }) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds") }
                IconButton(onClick = { scope.launch { model.audio.connect().seekToNextMediaItem() } }) { Icon(Icons.Rounded.SkipNext, "Next episode") }
            }
            audio.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ChoiceRow(listOf("0.75", "1", "1.25", "1.5", "1.75", "2"), library.speed.toString().removeSuffix(".0")) { speed -> model.podcasts.update { it.copy(speed = speed.toFloat()) } }
            Row { TextButton(onClick = { model.open(PhonePage.Podcasts) }) { Text("Queue & library") }; if (episode.mime.startsWith("video")) TextButton(onClick = { (context as? MobileActivity)?.enterPlayerPip() }) { Text("Picture in picture") } }
            Text(episode.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun audioTime(millis: Long): String { val seconds = millis.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60) }
