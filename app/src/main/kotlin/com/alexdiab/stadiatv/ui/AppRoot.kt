package com.alexdiab.stadiatv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.android.awaitFrame
import com.alexdiab.stadiatv.AppContainer
import com.alexdiab.stadiatv.ui.components.LocalMatchSources
import com.alexdiab.stadiatv.ui.components.LocalSpoilerFree
import com.alexdiab.stadiatv.ui.fantasy.FantasyConnectScreen
import com.alexdiab.stadiatv.ui.fantasy.FantasyScreen
import com.alexdiab.stadiatv.ui.following.MyBannerScreen
import com.alexdiab.stadiatv.ui.home.HomeScreen
import com.alexdiab.stadiatv.ui.livetv.GuideScreen
import com.alexdiab.stadiatv.ui.livetv.LiveTvScreen
import com.alexdiab.stadiatv.ui.match.MatchDetailScreen
import com.alexdiab.stadiatv.ui.multiview.MultiviewScreen
import com.alexdiab.stadiatv.ui.news.ArticleScreen
import com.alexdiab.stadiatv.ui.stats.AthleteScreen
import com.alexdiab.stadiatv.ui.stats.RosterScreen
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.player.PlayerScreen
import com.alexdiab.stadiatv.ui.settings.SettingsScreen
import com.alexdiab.stadiatv.ui.setup.AddPlaylistScreen
import com.alexdiab.stadiatv.ui.setup.EditPlaylistScreen
import com.alexdiab.stadiatv.ui.setup.OnboardingScreen
import com.alexdiab.stadiatv.ui.setup.TeamPickerScreen
import com.alexdiab.stadiatv.ui.sports.SportsHubScreen
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Navigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.Tab
import com.alexdiab.stadiatv.ui.nav.TopBar
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.StadiaTvTheme
import com.alexdiab.stadiatv.ui.theme.Tv

@Composable
fun AppRoot(container: AppContainer) {
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val matchSources by container.streams.sources.collectAsStateWithLifecycle()
    val navigator = remember { Navigator() }
    StadiaTvTheme {
        CompositionLocalProvider(
            LocalContainer provides container,
            LocalNavigator provides navigator,
            LocalSpoilerFree provides settings.spoilerFreeMode,
            LocalMatchSources provides matchSources,
        ) {
            Box(Modifier.fillMaxSize().background(Tv.gradients.screen)) {
                if (settings.hasCompletedOnboarding) MainShell(container, navigator) else OnboardingScreen()
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MainShell(container: AppContainer, navigator: Navigator) {
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val saveable = rememberSaveableStateHolder()
    val barRequesters = remember { Tab.entries.associateWith { FocusRequester() } }
    val contentRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var barFocused by remember { mutableStateOf(false) }
    val overlayOpen = navigator.top != null

    // Keep the sports feed fresh while the app is on screen.
    LaunchedEffect(settings.feedSignature) {
        container.matchFeed.load()
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { container.matchFeed.autoRefresh() }
    }

    // Back: close the top screen, else step up from content to the tab bar, else to Home, else exit.
    BackHandler(enabled = overlayOpen) { navigator.pop() }
    BackHandler(enabled = !overlayOpen && (!barFocused || navigator.tab != Tab.Home)) {
        if (!barFocused) {
            runCatching { barRequesters.getValue(navigator.tab).requestFocus() }
        } else {
            navigator.select(Tab.Home)
            runCatching { barRequesters.getValue(Tab.Home).requestFocus() }
        }
    }

    // Start on the selected tab (as tvOS does); returning from a pushed screen puts focus back
    // where it was in the tab content.
    var overlayWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { barRequesters.getValue(Tab.Home).requestFocus() } }
    LaunchedEffect(overlayOpen) {
        if (overlayOpen) overlayWasOpen = true
        else if (overlayWasOpen) {
            overlayWasOpen = false
            // The closing screen stays composed for its fade-out and still holds focus; release it first,
            // or it keeps the focus and the fallback lands on the first tab.
            focusManager.clearFocus(force = true)
            runCatching { contentRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // The tab content and bar stay composed under a pushed screen so Back returns to exactly where
        // the user was, but focus must not wander into them while that screen is open.
        Box(
            Modifier
                .fillMaxSize()
                .focusProperties { if (overlayOpen) onEnter = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            // Up from the top of a screen returns to the tab you are on, not whichever tab is nearest above the cursor.
            // This is its own group because focusRestorer, on the group inside, already owns `exit` to remember where focus was.
            Box(
                Modifier
                    .fillMaxSize()
                    .focusProperties { exit = { direction -> if (direction == FocusDirection.Up) barRequesters.getValue(navigator.tab) else FocusRequester.Default } }
                    .focusGroup(),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(contentRequester)
                        .focusRestorer()
                        .focusGroup(),
                ) {
                    AnimatedContent(
                        targetState = navigator.tab,
                        transitionSpec = { tabTransition() },
                        label = "tab",
                    ) { tab ->
                        saveable.SaveableStateProvider(tab) { TabContent(tab) }
                    }
                }
            }

            // Fade content under the bar so scrolling shelves never collide with it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .background(Brush.verticalGradient(listOf(Palette.Background, Palette.Background.copy(alpha = 0.92f), Color.Transparent))),
            )
            TopBar(
                selected = navigator.tab,
                onSelect = navigator::select,
                barFocusRequesters = barRequesters,
                contentFocusRequester = contentRequester,
                onBarFocusChanged = { barFocused = it },
            )
        }

        AnimatedContent(
            targetState = navigator.top,
            transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(260)))
                    .togetherWith(fadeOut(tween(160)))
            },
            label = "route",
        ) { route ->
            if (route != null) OverlayHost { RouteScreen(route) }
        }
    }
}

/**
 * A pushed screen. It claims focus when it opens (unless the screen already did) and keeps D-pad
 * movement from leaving it, so the remote always drives what is on screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun OverlayHost(content: @Composable () -> Unit) {
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Screens with their own initial focus claim it first; this covers the ones without.
        repeat(3) { awaitFrame() }
        if (!hasFocus && !runCatching { requester.requestFocus() }.getOrDefault(false)) {
            // Nothing focusable yet (content still loading): drop focus from the layer below so the
            // next D-pad press lands in this screen once it has something to focus.
            focusManager.clearFocus(force = true)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusRequester(requester)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
    ) { content() }
}

private fun tabTransition(): ContentTransform =
    fadeIn(tween(180, delayMillis = 40)) togetherWith fadeOut(tween(120))

@Composable
private fun TabContent(tab: Tab) {
    when (tab) {
        Tab.Home -> HomeScreen()
        Tab.Sports -> SportsHubScreen()
        Tab.LiveTv -> LiveTvScreen()
        Tab.Guide -> GuideScreen(asTab = true)
        Tab.MyBanner -> MyBannerScreen()
        Tab.Settings -> SettingsScreen()
    }
}

@Composable
private fun RouteScreen(route: Route) {
    when (route) {
        is Route.MatchDetail -> MatchDetailScreen(route.match)
        is Route.Player -> PlayerScreen(route.channel, route.match)
        is Route.Multiview -> MultiviewScreen(route.channels)
        Route.Fantasy -> FantasyScreen()
        Route.FantasyConnect -> FantasyConnectScreen()
        Route.Guide -> GuideScreen()
        Route.AddPlaylist -> AddPlaylistScreen()
        is Route.EditPlaylist -> EditPlaylistScreen(route.playlistId)
        Route.TeamPicker -> TeamPickerScreen()
        is Route.ArticleReader -> ArticleScreen(route.article)
        is Route.Roster -> RosterScreen(route.team)
        is Route.AthleteProfile -> AthleteScreen(route.league, route.athlete)
    }
}
