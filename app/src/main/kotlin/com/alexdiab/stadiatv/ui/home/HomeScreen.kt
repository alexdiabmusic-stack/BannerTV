package com.alexdiab.stadiatv.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.data.FeedSections
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.ChipStyle
import com.alexdiab.stadiatv.ui.components.FeaturedGameCard
import com.alexdiab.stadiatv.ui.components.ActionNote
import com.alexdiab.stadiatv.ui.components.InfoNote
import com.alexdiab.stadiatv.ui.components.StaleNote
import com.alexdiab.stadiatv.ui.components.LocalMatchSources
import com.alexdiab.stadiatv.ui.components.LocalSpoilerFree
import com.alexdiab.stadiatv.ui.components.ScoreTile
import com.alexdiab.stadiatv.ui.components.ViewAllTile
import com.alexdiab.stadiatv.ui.components.WatchState
import com.alexdiab.stadiatv.ui.components.MatchCard
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.Shelf
import com.alexdiab.stadiatv.ui.components.SkeletonCard
import com.alexdiab.stadiatv.ui.components.SkeletonShelf
import com.alexdiab.stadiatv.ui.livetv.ChannelShelf
import com.alexdiab.stadiatv.ui.livetv.ChannelShelvesViewModel
import com.alexdiab.stadiatv.ui.livetv.LineupChannelCard
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.Tab
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val feed by container.matchFeed.sections.collectAsStateWithLifecycle()
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val open: (Match) -> Unit = { navigator.push(Route.MatchDetail(it)) }

    when {
        feed.isLoading && feed.isEmpty -> HomeSkeleton()
        feed.error != null && feed.isEmpty -> Column(Modifier.padding(top = TopBarInset)) {
            EmptyState(
                icon = Icons.Rounded.WifiOff,
                title = "Couldn't load games",
                subtitle = feed.error,
                action = {
                    FocusPill(onClick = { scope.launch { container.matchFeed.load(force = true) } }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Row(
                            Modifier.padding(horizontal = 28.dp).align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, null, tint = Palette.TextPrimary)
                            Text("Try Again", style = Type.BodyStrong, color = Palette.TextPrimary)
                        }
                    }
                },
            )
        }
        else -> HomeContent(
            feed,
            hasFavorites = settings.favoriteTeams.isNotEmpty(),
            personalised = settings.isPersonalised,
            open = open,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeContent(feed: FeedSections, hasFavorites: Boolean, personalised: Boolean, open: (Match) -> Unit) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val spoilerFree = LocalSpoilerFree.current
    val viewModel: ChannelShelvesViewModel = viewModel(key = "shelves", factory = viewModelFactory { initializer { ChannelShelvesViewModel(container) } })
    val channels by viewModel.shelves.collectAsStateWithLifecycle()
    val lineup by container.lineup.lineup.collectAsStateWithLifecycle()
    val playlists by container.playlists.state.collectAsStateWithLifecycle()
    val sources = LocalMatchSources.current
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    val daypart = Daypart.of(System.currentTimeMillis())
    val featured = feed.prime ?: feed.liveNow.firstOrNull() ?: feed.favoriteUpcoming.firstOrNull() ?: feed.upcoming.firstOrNull()
    // Home shows the viewer's own live games; the Sports hub is where every game in every league lives.
    val liveElsewhere = remember(feed.liveForYou, featured) { feed.liveForYou.filter { it.key != featured?.key } }
    val teams = feed.favoriteToday.ifEmpty { feed.favoriteLive }
    val picks = remember(feed.startingSoonForYou, feed.upcoming, featured) { feed.startingSoonForYou.ifEmpty { feed.upcoming }.filter { it.key != featured?.key }.take(10) }
    val results = remember(feed.favoriteRecent, feed.recent) { feed.favoriteRecent.ifEmpty { feed.recent }.take(10) }
    val channelShelf: ChannelShelf? = channels.shelves.firstOrNull { it.key == "favorites" } ?: channels.shelves.firstOrNull { it.key == "sports" }
    val plan = HomeLayout.plan(daypart, hasLive = liveElsewhere.isNotEmpty() && !spoilerFree)
    // Entering the screen from the bar picks the left-most item, which would skip the featured game's buttons.
    // The button only exists while its row is composed; asking an unattached requester for focus throws.
    val primaryFocus = remember { FocusRequester() }
    var featuredComposed by remember { mutableStateOf(false) }

    Box(Modifier.focusProperties { enter = { if (featuredComposed) primaryFocus else FocusRequester.Default } }.focusGroup()) {
        PagePivot(anchorFraction = 0.3f) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(top = TopBarInset, bottom = Spacing.ScreenBottom + 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "header") { Header(daypart, liveCount = if (spoilerFree) 0 else feed.liveForYou.size) }
                if (!personalised) item(key = "personalise") {
                    ActionNote(
                        Icons.Rounded.Star, "Make Home yours",
                        "Choose your sports, leagues and teams and their games lead this screen.",
                        action = "Choose Favourites", onClick = { navigator.push(Route.TeamPicker) },
                    )
                }
                if (feed.refreshFailed) item(key = "stale") { StaleNote(feed.updatedAt, retry = { scope.launch { container.matchFeed.load(force = true) } }) }
                if (featured != null) {
                    item(key = "featured") {
                        DisposableEffect(Unit) {
                            featuredComposed = true
                            onDispose { featuredComposed = false }
                        }
                        val currentSources = sources[featured.key]?.let { container.streams.sourcesFor(featured) }
                        val best = currentSources?.confirmed?.firstOrNull()
                        val possible = currentSources?.possible?.size ?: 0
                        val watch = when {
                            best != null -> WatchState.Ready
                            playlists.playlists.isEmpty() -> WatchState.NoPlaylist
                            // Absent means "not scanned yet": still working while a lineup is loading or exists, a plain no when there is none.
                            sources[featured.key] == null ->
                                if (playlists.channels.isNotEmpty() || playlists.loading.isNotEmpty() || !playlists.hasLoadedCache) WatchState.Checking else WatchState.NoMatch
                            else -> WatchState.NoMatch
                        }
                        FeaturedGameCard(
                            featured, source = best, sourceName = best?.let { lineup.entryFor(it.channel.key)?.name }, possibleCount = possible, watch = watch,
                            onWatch = {
                                val current = container.streams.sourcesFor(featured).confirmed.firstOrNull()
                                if (current != null) navigator.push(Route.Player(current.channel, featured)) else open(featured)
                            },
                            onMatchCentre = { open(featured) },
                            modifier = Modifier.padding(horizontal = Spacing.ScreenH),
                            primaryFocus = primaryFocus,
                        )
                    }
                } else {
                    item(key = "none") { NoGamesNote(feed) }
                }
                plan.forEach { part ->
                    when (part) {
                        HomePart.LiveAround -> if (liveElsewhere.isNotEmpty() && !spoilerFree) item(key = "live") {
                            Shelf("Live Around the Leagues", icon = Icons.Rounded.Sensors) {
                                items(liveElsewhere.take(12), key = { it.key }) { ScoreTile(it, onClick = { open(it) }) }
                                item(key = "viewAll") { ViewAllTile("View all", onClick = { navigator.select(Tab.Sports) }) }
                            }
                        }
                        HomePart.Continue -> if (channels.recent.isNotEmpty()) item(key = "continue") {
                            Shelf("Continue Watching", icon = Icons.Rounded.PlayCircle) {
                                items(channels.recent, key = { it.key }) { row ->
                                    LineupChannelCard(row, onClick = { navigator.push(Route.Player(row.pick.channel)) })
                                }
                            }
                        }
                        HomePart.Teams -> if (teams.isNotEmpty()) item(key = "teams") {
                            Shelf("Your Teams", icon = Icons.Rounded.Star) {
                                items(teams.take(8), key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                            }
                        } else if (hasFavorites) item(key = "teamsNone") { NoTeamsToday(feed) }
                        HomePart.Picks -> if (picks.isNotEmpty()) item(key = "picks") {
                            Shelf(HomeLayout.picksTitle(daypart), icon = Icons.Rounded.CalendarMonth) {
                                items(picks, key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                            }
                        }
                        HomePart.Channels -> if (channelShelf != null) item(key = "channels") {
                            Shelf(if (channelShelf.key == "favorites") "Your Live TV Channels" else channelShelf.title, icon = Icons.Rounded.LiveTv) {
                                items(channelShelf.rows, key = { it.key }) { row ->
                                    LineupChannelCard(row, onClick = { navigator.push(Route.Player(row.pick.channel)) })
                                }
                            }
                        }
                        HomePart.Results -> if (results.isNotEmpty()) item(key = "results") {
                            Shelf(HomeLayout.resultsTitle(daypart), icon = Icons.Rounded.AccessTime) {
                                items(results, key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(daypart: Daypart, liveCount: Int) {
    val date = remember { Dates.longDay() }
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(daypart.title, style = Type.Title1, color = Palette.TextPrimary)
            Text("$date · Personalized for you", style = Type.Headline, color = Palette.TextSecondary)
        }
        if (liveCount > 0) Chip(HomeLayout.liveCountLabel(liveCount), style = ChipStyle.Live, icon = Icons.Rounded.Sensors)
    }
}

/** A real answer to "nothing is on", as opposed to "we couldn't find out" (that has its own screen and the stale note). */
@Composable
private fun NoGamesNote(feed: FeedSections) {
    val next = feed.nextAcrossSports.firstOrNull()
    InfoNote(
        icon = Icons.Rounded.CalendarMonth,
        title = "No games on today in your leagues",
        subtitle = next?.let { "Next up: ${it.shortName} · ${Dates.dateTime(it.startMillis)}" } ?: "Follow more leagues in My Banner to see more games here.",
    )
}

@Composable
private fun NoTeamsToday(feed: FeedSections) {
    val next = feed.favoriteUpcoming.firstOrNull() ?: return
    InfoNote(Icons.Rounded.CalendarMonth, "No followed teams play today", "Next: ${next.shortName} · ${Dates.dateTime(next.startMillis)}")
}

@Composable
private fun HomeSkeleton() {
    Column(
        Modifier.padding(top = TopBarInset),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        SkeletonCard(1760.dp, 232.dp, Modifier.padding(horizontal = Spacing.ScreenH))
        SkeletonShelf("Live Around the Leagues")
    }
}
