package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.ui.components.CenteredSpinner
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.Shelf
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.Tab
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

/**
 * Live TV as somewhere to find something to watch: what you were just watching, your favourites, sport that is on right
 * now, then channels by kind. Every tile is a lineup channel (a clean name, the quality that will play, what is on and
 * how far through it is), so what you see here is what the guide shows. The flat list of every channel is one press away.
 */
@Composable
fun LiveTvDiscover(onBrowse: () -> Unit) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val viewModel: ChannelShelvesViewModel = viewModel(key = "shelves", factory = viewModelFactory { initializer { ChannelShelvesViewModel(container) } })
    val channels by viewModel.shelves.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val play: (GuideRow) -> Unit = { navigator.push(Route.Player(it.pick.channel)) }
    val favorite: (GuideRow) -> Unit = { container.preferences.toggleFavoriteEntry(it.key) }

    Column(Modifier.fillMaxSize().padding(top = TopBarInset - 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenH),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Live TV", style = Type.Title2, color = Palette.TextPrimary)
            Spacer(Modifier.weight(1f))
            if (channels.total > 0) Text("${channels.total} channels", style = Type.Callout, color = Palette.TextSecondary)
            FocusPill(onClick = onBrowse, height = 56.dp) {
                Row(Modifier.padding(horizontal = 26.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.Apps, null, tint = Palette.TextPrimary, modifier = Modifier.size(24.dp))
                    Text("Browse All", style = Type.BodyStrong, color = Palette.TextPrimary)
                }
            }
            FocusPill(onClick = { navigator.push(Route.Multiview(emptyList())) }, height = 56.dp) {
                Row(Modifier.padding(horizontal = 26.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.ViewModule, null, tint = Palette.TextPrimary, modifier = Modifier.size(24.dp))
                    Text("Multiview", style = Type.BodyStrong, color = Palette.TextPrimary)
                }
            }
            FocusPill(onClick = { navigator.select(Tab.Guide) }, height = 56.dp, selected = true) {
                Row(Modifier.padding(horizontal = 26.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.GridView, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text("TV Guide", style = Type.BodyStrong, color = Color.White)
                }
            }
        }
        when {
            !channels.ready -> CenteredSpinner(Modifier.weight(1f), "Getting your channels ready…")
            channels.isEmpty -> EmptyState(
                Icons.Rounded.Tv, "No channels to show yet",
                subtitle = "Your playlist loaded, but none of its channels could be grouped. You can still browse the whole list.",
                modifier = Modifier.weight(1f),
                action = { FocusPill(onClick = onBrowse) { Text("Browse All Channels", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 30.dp).align(Alignment.Center)) } },
            )
            else -> PagePivot(anchorFraction = 0.25f) {
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(top = 12.dp, bottom = Spacing.ScreenBottom + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (channels.recent.isNotEmpty()) item(key = "recent") {
                        Shelf("Continue Watching", icon = Icons.Rounded.PlayCircle) {
                            items(channels.recent, key = { it.key }) { row -> LineupChannelCard(row, onClick = { play(row) }, onLongClick = { favorite(row) }) }
                        }
                    }
                    items(channels.shelves, key = { it.key }) { shelf ->
                        Shelf(shelf.title, icon = if (shelf.key == "sports") Icons.Rounded.Sensors else null) {
                            items(shelf.rows, key = { it.key }) { row -> LineupChannelCard(row, onClick = { play(row) }, onLongClick = { favorite(row) }) }
                        }
                    }
                }
            }
        }
    }
}
