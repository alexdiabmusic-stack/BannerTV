package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.ui.components.ChannelLogo
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.SideRow
import com.alexdiab.stadiatv.ui.components.SidePanel
import com.alexdiab.stadiatv.ui.components.SkeletonCard
import com.alexdiab.stadiatv.ui.components.TvTextField
import com.alexdiab.stadiatv.live.ChannelFilterEngine
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.Tab
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveTvScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val lineup by container.playlists.state.collectAsStateWithLifecycle()
    val settings by container.preferences.settings.collectAsStateWithLifecycle()

    when {
        lineup.playlists.isEmpty() -> NoPlaylist(onAdd = { navigator.push(Route.AddPlaylist) })
        lineup.channels.isEmpty() && lineup.loading.isNotEmpty() -> LoadingLineup()
        lineup.channels.isEmpty() && lineup.hasLoadedCache -> EmptyState(
            Icons.Rounded.Tv, "No channels found",
            subtitle = lineup.errors.values.firstOrNull() ?: "Your playlist loaded but returned no channels.",
            modifier = Modifier.padding(top = TopBarInset),
            action = { FocusPill(onClick = { container.playlists.refreshAll() }) { Text("Try Again", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 30.dp).align(Alignment.Center)) } },
        )
        else -> {
            var browsing by rememberSaveable { mutableStateOf(false) }
            BackHandler(enabled = browsing) { browsing = false }
            if (browsing) Browser(settings.favoriteChannelIds) else LiveTvDiscover(onBrowse = { browsing = true })
        }
    }
}

@Composable
private fun NoPlaylist(onAdd: () -> Unit) {
    EmptyState(
        icon = Icons.Rounded.Tv,
        title = "No playlists added",
        subtitle = "Add an M3U link or stream login to watch your own channels, and have matches linked to streams automatically.",
        modifier = Modifier.padding(top = TopBarInset),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                FocusPill(onClick = onAdd, selected = true, height = 64.dp) {
                    Row(Modifier.padding(horizontal = 34.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.AddLink, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Text("Add M3U Link", style = Type.Headline, color = Color.White)
                    }
                }
                FocusPill(onClick = onAdd, height = 64.dp) {
                    Row(Modifier.padding(horizontal = 34.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.VpnKey, null, tint = Palette.TextPrimary, modifier = Modifier.size(28.dp))
                        Text("Stream Login", style = Type.Headline, color = Palette.TextPrimary)
                    }
                }
            }
        },
    )
}

@Composable
private fun LoadingLineup() {
    Column(Modifier.padding(top = TopBarInset, start = Spacing.ScreenH, end = Spacing.ScreenH), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Loading your channels…", style = Type.Title3, color = Palette.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { repeat(6) { SkeletonCard(250.dp, 216.dp) } }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { repeat(6) { SkeletonCard(250.dp, 216.dp) } }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Browser(favoriteKeys: Set<String>) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val viewModel: LiveTvViewModel = viewModel(key = "livetv", factory = viewModelFactory { initializer { LiveTvViewModel(container) } })
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val guideState by container.guide.state.collectAsStateWithLifecycle()

    Row(Modifier.fillMaxSize().padding(top = TopBarInset - 12.dp, start = Spacing.ScreenH - 8.dp)) {
        SidePanel("LIVE TV", Modifier.width(320.dp).fillMaxHeight().padding(bottom = 40.dp)) {
            val all = !filter.favoritesOnly && filter.categories.isEmpty()
            SideRow("All Channels", all, { viewModel.selectAll() }, subtitle = "${ui.total} channels", icon = Icons.Rounded.LiveTv)
            SideRow("Favourites", filter.favoritesOnly, { viewModel.selectFavorites() }, subtitle = "${favoriteKeys.size} saved", icon = Icons.Rounded.Favorite)
            Text("CATEGORIES", style = Type.Overline, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
            ui.categoryCounts.forEach { (category, count) ->
                SideRow(category, category in filter.categories, { viewModel.selectCategory(category) }, subtitle = "$count", icon = null)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = Spacing.ScreenH, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TvTextField(query, { viewModel.query.value = it }, "Search channels", placeholder = "Type a channel name", modifier = Modifier.weight(1f))
                FocusPill(onClick = { navigator.push(Route.Multiview(emptyList())) }, height = 64.dp) {
                    Row(Modifier.padding(horizontal = 28.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.ViewModule, null, tint = Palette.TextPrimary, modifier = Modifier.size(26.dp))
                        Text("Multiview", style = Type.Headline, color = Palette.TextPrimary)
                    }
                }
                FocusPill(onClick = { navigator.select(Tab.Guide) }, height = 64.dp, selected = true) {
                    Row(Modifier.padding(horizontal = 30.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.GridView, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Text("TV Guide", style = Type.Headline, color = Color.White)
                    }
                }
            }
            ChipRow(startPadding = 24.dp) {
                item("q-all") { FilterChip("Any quality", filter.qualities.isEmpty(), { viewModel.clearQuality() }) }
                items(ChannelFilterEngine.QUALITIES, key = { "q-$it" }) { quality -> FilterChip(quality, quality in filter.qualities, { viewModel.toggleQuality(quality) }) }
            }
            if (ui.isIndexing) {
                com.alexdiab.stadiatv.ui.components.CenteredSpinner(Modifier.weight(1f), "Getting your channels ready…")
            } else if (ui.channels.isEmpty()) {
                EmptyState(Icons.Rounded.SearchOff, "No channels found", subtitle = "Try a different search or filter.", modifier = Modifier.weight(1f))
            } else {
                PagePivot(0.25f) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(250.dp),
                        contentPadding = PaddingValues(start = 24.dp, end = Spacing.ScreenH, top = 12.dp, bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                    ) {
                        items(ui.channels, key = { it.key }) { channel ->
                            ChannelCard(
                                channel = channel,
                                favorite = channel.key in favoriteKeys || channel.id in favoriteKeys,
                                guideRevision = guideState.revision,
                                onClick = { navigator.push(Route.Player(channel)) },
                                onLongClick = { container.preferences.toggleFavoriteChannel(channel.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, favorite: Boolean, guideRevision: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val guide = LocalContainer.current.guide
    val now = remember(channel.key, guideRevision) { guide.nowAndNext(channel).first }
    FocusCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().height(216.dp),
        restingBorder = if (favorite) Palette.Accent.copy(alpha = 0.6f) else Palette.Hairline,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ChannelLogo(channel.logoUrl, 80.dp)
                if (favorite) Icon(Icons.Rounded.Favorite, null, tint = Palette.Live, modifier = Modifier.align(Alignment.TopEnd).size(24.dp))
            }
            Text(channel.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(now?.title ?: channel.group, style = Type.Caption, color = if (now != null) Palette.AccessibleAccent else Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                val progress = remember(now) { now.progress(System.currentTimeMillis()) }
                Box(Modifier.fillMaxWidth().height(4.dp).background(Palette.SurfaceHigh, RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Palette.Accent, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}
