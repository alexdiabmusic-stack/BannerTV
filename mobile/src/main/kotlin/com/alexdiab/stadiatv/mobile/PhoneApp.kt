@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle

@Composable
fun PhoneApp(model: MobileViewModel, activity: MobileActivity) {
    NativeFantasyContextCoordinator(model)
    // Keep screen/tab state alive while the full-screen player replaces the scaffold.
    val savedUi = rememberSaveableStateHolder()
    val pageKeys = remember(model.pages.toList()) { model.pages.map(::phonePageStateKey).toSet() }
    var knownPageKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(pageKeys) {
        (knownPageKeys - pageKeys).forEach(savedUi::removeState)
        knownPageKeys = pageKeys
    }
    val settings by model.container.preferences.settings.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val audioState by PhoneAudioService.ui.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val networkRecovery = remember { PhoneConnectionRecovery() }
    val lifecycle = LocalLifecycleOwner.current
    // iOS keys its feed task by followed teams/leagues. Don't leave a newly followed team
    // waiting for the next 30/60-second timer before requesting its season schedule.
    LaunchedEffect(lifecycle, settings.feedSignature, connection) {
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val restored = networkRecovery.accept(connection)
            if (restored) model.container.sports.onNetworkRestored()
            if (connection.known && !connection.online) { model.container.matchFeed.markOffline(); return@repeatOnLifecycle }
            model.container.matchFeed.load(force = restored, waitForCurrent = true)
            model.container.matchFeed.autoRefresh()
        }
    }
    LaunchedEffect(lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) { model.entitlements.refresh(); kotlinx.coroutines.delay(5 * 60_000L) }
    } }
    LaunchedEffect(settings.playerBufferProfile) { model.controller.setProfile(model.profile()) }
    LaunchedEffect(settings.captionsEnabled, settings.captionLanguage) { model.controller.setCaptionPreference(settings.captionsEnabled, settings.captionLanguage) }
    LaunchedEffect(settings.preferredAudioLanguage) { model.controller.setAudioPreference(settings.preferredAudioLanguage) }

    PhoneTheme(library.appearance) {
        val lightSystemBars = MaterialTheme.colorScheme.background.luminance() > 0.5f && !(model.fullPlayer && model.channel != null)
        SideEffect {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = lightSystemBars
                isAppearanceLightNavigationBars = lightSystemBars
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when {
                activity.inPictureInPicture && model.channel == null && audioState.episode?.mime?.startsWith("video") == true -> PhoneAudioPlayer(model, pip = true)
                model.fullPlayer && model.channel != null -> PhonePlayer(model, activity)
                !settings.hasCompletedOnboarding -> PhoneOnboarding(model)
                else -> PhoneScaffold(model, activity, savedUi)
            }
            model.message?.let { message ->
                AlertDialog(onDismissRequest = { model.message = null }, title = { Text("BannerTV") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { model.message = null }) { Text("OK") } })
            }
        }
    }
}

@Composable
private fun PhoneScaffold(model: MobileViewModel, activity: MobileActivity, savedUi: SaveableStateHolder) {
    val page = model.pages.lastOrNull()
    val contentKey = remember(page, model.tab) { page?.let(::phonePageStateKey) ?: "tab:${model.tab.name}" }
    BackHandler(page != null) { model.back() }
    val title = when (page) {
        is PhonePage.Game -> page.match.league.shortName
        is PhonePage.Golf -> "PGA Tour"
        PhonePage.Search -> "Search"
        PhonePage.Teams -> "Your teams & leagues"
        PhonePage.Playlists -> "Playlists"
        is PhonePage.PlaylistForm -> if (page.editing == null) "Add playlist" else "Edit playlist"
        is PhonePage.LeagueHub -> page.league.name
        is PhonePage.TeamDetail -> page.team.name
        is PhonePage.PlayerDetail -> page.athlete.name
        PhonePage.Fantasy -> "Fantasy"
        PhonePage.SavedArticles -> "Saved articles"
        PhonePage.History -> "Watch history"
        PhonePage.Multiview -> "Multiview"
        is PhonePage.Reader -> page.article.source
        PhonePage.Podcasts -> "Podcasts"
        is PhonePage.Podcast -> page.show.title
        PhonePage.AudioPlayer -> "Now playing"
        PhonePage.CloudBackup -> "Cloud backup"
        PhonePage.Addons -> "Your add-ons"
        PhonePage.Cinema -> "Movies & shows"
        is PhonePage.CinemaDetail -> "Movies & shows"
        PhonePage.ChannelGroups -> "Channel groups"
        is PhonePage.Stats -> "Stats"
        PhonePage.Premium -> "Premium & purchases"
        is PhonePage.FollowingSchedule -> "Following schedule"
        null -> model.tab.label
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (page !is PhonePage.CinemaDetail) {
            TopAppBar(
                title = {
                    if (page == null && model.tab == PhoneTab.Home) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("BANNER", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, fontSize = 19.sp)
                    } else Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                },
                navigationIcon = { if (page != null) IconButton(onClick = model::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (page != PhonePage.Cinema && page != PhonePage.Addons) PhoneCastButton(model)
                    if (page != PhonePage.Search && page != PhonePage.Cinema && page != PhonePage.Addons) IconButton(onClick = { model.open(PhonePage.Search) }) { Icon(Icons.Rounded.Search, "Search") }
                    if (page == null) IconButton(onClick = { model.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh scores") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            }
        },
        bottomBar = {
            Column {
                PhoneCastPanel(model)
                if (model.channel != null && page != PhonePage.Multiview) MiniPlayer(model)
                if (model.channel == null && page != PhonePage.AudioPlayer) MiniAudioPlayer(model)
                if (page != PhonePage.Multiview) NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    PhoneTab.entries.forEach { tab ->
                        val icon = when (tab) { PhoneTab.Home -> Icons.Rounded.Home; PhoneTab.Following -> Icons.Rounded.Star; PhoneTab.Live -> Icons.Rounded.LiveTv; PhoneTab.Discover -> Icons.Rounded.Explore; PhoneTab.Settings -> Icons.Rounded.Settings }
                        NavigationBarItem(selected = model.tab == tab, onClick = { model.selectTab(tab) }, icon = { Icon(icon, null) }, label = { Text(tab.label, style = MaterialTheme.typography.labelMedium, maxLines = 2) }, alwaysShowLabel = true)
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            savedUi.SaveableStateProvider(contentKey) {
            when (page) {
                is PhonePage.Game -> GameDetailScreen(page.match, model)
                is PhonePage.Golf -> GolfCentre(page.id, model)
                PhonePage.Search -> SearchScreen(model)
                PhonePage.Teams -> TeamsScreen(model)
                PhonePage.Playlists -> PlaylistsScreen(model)
                is PhonePage.PlaylistForm -> PlaylistEditor(page.editing, model, model::back)
                is PhonePage.LeagueHub -> LeagueScreen(page.league, model, page.initialSection, nflWeek = page.nflWeek, calendarDate = page.calendarDate)
                is PhonePage.TeamDetail -> TeamScreen(page.team, model)
                is PhonePage.PlayerDetail -> AthleteScreen(page.league, page.athlete, model)
                PhonePage.Fantasy -> FantasyScreen(model)
                PhonePage.SavedArticles -> SavedArticlesScreen(model)
                PhonePage.History -> HistoryScreen(model)
                PhonePage.Multiview -> PhoneMultiview(model)
                is PhonePage.Reader -> PhoneArticleReader(page.article, model)
                PhonePage.Podcasts -> PhonePodcasts(model)
                is PhonePage.Podcast -> PhonePodcastShow(page.show, model)
                PhonePage.AudioPlayer -> PhoneAudioPlayer(model)
                PhonePage.CloudBackup -> CloudBackupScreen(model)
                PhonePage.Addons -> PhoneAddons(model)
                PhonePage.Cinema -> PhoneCinema(model)
                is PhonePage.CinemaDetail -> PhoneCinemaDetail(page.id,model)
                PhonePage.ChannelGroups -> ChannelGroupsScreen(model)
                is PhonePage.Stats -> PhoneStats(model, page.leaguePath)
                PhonePage.Premium -> PhonePremiumScreen(model, activity)
                is PhonePage.FollowingSchedule -> FollowingScheduleScreen(model, page.entity, page.sport)
                null -> when (model.tab) {
                    PhoneTab.Home -> HomeScreen(model)
                    PhoneTab.Following -> FollowingScreen(model)
                    PhoneTab.Live -> LiveScreen(model)
                    PhoneTab.Discover -> DiscoverScreen(model)
                    PhoneTab.Settings -> SettingsScreen(model)
                }
            }
            }
        }
    }
}

@Composable
private fun MiniPlayer(model: MobileViewModel) {
    val channel = model.channel ?: return
    val playback by model.controller.ui.collectAsStateWithLifecycle()
    val presentation = phonePlaybackPresentation(playback)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { model.fullPlayer = true }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayerVideo(model.controller, Modifier.width(88.dp).height(50.dp), showNativeControls = false)
            Column(Modifier.weight(1f)) {
                Text(channel.canonicalName ?: channel.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (presentation.phase == PhonePlaybackPhase.Playing) model.replay?.let { "Replay · ${it.programme.title}" } ?: presentation.statusLabel else presentation.statusLabel,
                    style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (presentation.recoveryRequired) PhoneDesign.colors.error.foreground else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { if (presentation.recoveryRequired) model.restart() else model.controller.togglePlayPause() }) {
                Icon(if (presentation.recoveryRequired) Icons.Rounded.Refresh else if (playback.timeline.ended) Icons.Rounded.Replay else if (playback.isUserPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (presentation.recoveryRequired) "Restart stream" else if (playback.timeline.ended) "Replay from start" else if (playback.isUserPaused) "Resume stream" else "Pause stream")
            }
            IconButton(onClick = model::stopPlayer) { Icon(Icons.Rounded.Close, "Stop stream") }
        }
    }
}
