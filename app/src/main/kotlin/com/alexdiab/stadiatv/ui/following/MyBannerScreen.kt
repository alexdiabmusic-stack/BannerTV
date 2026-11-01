package com.alexdiab.stadiatv.ui.following

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.SectionHeader
import com.alexdiab.stadiatv.ui.fantasy.MatchupCard
import com.alexdiab.stadiatv.ui.livetv.ChannelShelvesViewModel
import com.alexdiab.stadiatv.ui.livetv.LineupChannelCard
import com.alexdiab.stadiatv.ui.livetv.LineupChannelCardWidth
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.Tab
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.delay

/** What is personal to the viewer: the teams they follow, the channels they starred, their fantasy league. */
enum class BannerSection(val label: String, val icon: ImageVector) {
    Teams("Teams & Games", Icons.Rounded.Star),
    Channels("Channels", Icons.Rounded.LiveTv),
    Fantasy("Fantasy", Icons.Rounded.EmojiEvents),
}

/**
 * My Banner: everything that is yours in one place. Following lived on its own tab while favourite channels hid in
 * Live TV and fantasy in Settings; they are now three sections of one destination.
 */
@Composable
fun MyBannerScreen() {
    var sectionName by rememberSaveable { mutableStateOf(BannerSection.Teams.name) }
    val section = BannerSection.entries.firstOrNull { it.name == sectionName } ?: BannerSection.Teams
    Column(Modifier.fillMaxSize().padding(top = TopBarInset - 20.dp)) {
        ChipRow {
            BannerSection.entries.forEach { item ->
                item(item.name) { FilterChip(item.label, section == item, { sectionName = item.name }, icon = item.icon) }
            }
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalTopInset provides 28.dp) {
                AnimatedContent(
                    targetState = section,
                    transitionSpec = { fadeIn(tween(160, delayMillis = 30)) togetherWith fadeOut(tween(100)) },
                    label = "bannerSection",
                ) { current ->
                    when (current) {
                        BannerSection.Teams -> FollowingScreen()
                        BannerSection.Channels -> SavedChannels()
                        BannerSection.Fantasy -> FantasySummary()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SavedChannels() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val viewModel: ChannelShelvesViewModel = viewModel(key = "shelves", factory = viewModelFactory { initializer { ChannelShelvesViewModel(container) } })
    val channels by viewModel.shelves.collectAsStateWithLifecycle()
    val favorites = channels.favorites
    when {
        !channels.ready -> Box(Modifier.fillMaxSize().padding(top = LocalTopInset.current)) { com.alexdiab.stadiatv.ui.components.CenteredSpinner(label = "Getting your channels ready…") }
        favorites.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = LocalTopInset.current), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Rounded.Favorite, "No favorite channels yet",
                subtitle = "Hold OK on a channel in Live TV or the TV Guide to add it here.",
                action = {
                    FocusPill(onClick = { navigator.select(Tab.LiveTv) }, selected = true, height = 60.dp) {
                        Text("Browse Live TV", style = Type.Headline, color = Color.White, modifier = Modifier.padding(horizontal = 36.dp).align(Alignment.Center))
                    }
                },
            )
        }
        else -> PagePivot(0.25f) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(LineupChannelCardWidth),
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = LocalTopInset.current, bottom = Spacing.ScreenBottom + 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                items(favorites, key = { it.key }) { row ->
                    LineupChannelCard(
                        row,
                        onClick = { navigator.push(Route.Player(row.pick.channel)) },
                        onLongClick = { container.preferences.toggleFavoriteEntry(row.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FantasySummary() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val state by container.fantasy.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    LaunchedEffect(state.isConnected) {
        while (state.isConnected) {
            container.fantasy.refresh()
            delay(60_000L)
        }
    }
    if (!state.isConnected) {
        Box(Modifier.fillMaxSize().padding(top = LocalTopInset.current), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Rounded.EmojiEvents, "Connect your fantasy league",
                subtitle = "See this week's matchup and your lineup on Home screens and beside the game you are watching.",
                action = {
                    FocusPill(onClick = { navigator.push(Route.FantasyConnect) }, selected = true, height = 60.dp) {
                        Text("Connect ESPN Fantasy", style = Type.Headline, color = Color.White, modifier = Modifier.padding(horizontal = 36.dp).align(Alignment.Center))
                    }
                },
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = LocalTopInset.current, bottom = Spacing.ScreenBottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("title") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(snapshot?.leagueName ?: state.connection?.leagueName ?: "ESPN Fantasy", Modifier.weight(1f), Icons.Rounded.EmojiEvents)
                FocusPill(onClick = { navigator.push(Route.Fantasy) }, selected = true) {
                    Text("Open League", style = Type.BodyStrong, color = Color.White, modifier = Modifier.padding(horizontal = 30.dp).align(Alignment.Center))
                }
            }
        }
        if (state.isStale) item("stale") {
            Text("Showing saved data. ${state.error.orEmpty()}", style = Type.Callout, color = Palette.TextSecondary)
        }
        if (snapshot == null) item("loading") {
            Text(state.error ?: "Loading your league…", style = Type.Headline, color = Palette.TextSecondary)
        } else {
            snapshot.matchup?.let { item("matchup") { MatchupCard(it) } }
            item("updated") { Text("Updated ${Dates.relative(snapshot.refreshedAtMillis)}", style = Type.Caption, color = Palette.TextTertiary) }
        }
    }
}
