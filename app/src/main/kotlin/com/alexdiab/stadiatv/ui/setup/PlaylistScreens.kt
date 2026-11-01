package com.alexdiab.stadiatv.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.components.OverlayContentTop
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.components.SettingsHeading
import com.alexdiab.stadiatv.ui.components.SettingsRow
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.theme.Spacing

@Composable
fun AddPlaylistScreen() {
    val navigator = LocalNavigator.current
    OverlayScaffold(title = "Add Playlist", subtitle = "Live TV", scrollFade = true) {
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PlaylistForm(editing = null, onSaved = { navigator.pop() }, modifier = Modifier.widthIn(max = 1100.dp)) }
        }
    }
}

@Composable
fun EditPlaylistScreen(playlistId: String) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val state by container.playlists.state.collectAsStateWithLifecycle()
    val playlist = state.playlist(playlistId)
    var confirmRemove by remember { mutableStateOf(false) }

    if (playlist == null) {
        navigator.pop()
        return
    }
    OverlayScaffold(title = playlist.name, subtitle = "Playlist", scrollFade = true) {
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.widthIn(max = 1100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsRow(
                        "Refresh channels", onClick = { container.playlists.refresh(playlist.id) },
                        subtitle = "${state.counts[playlist.id] ?: 0} channels", icon = Icons.Rounded.Refresh,
                        modifier = Modifier.initialFocus(),
                    )
                    if (state.defaultId != playlist.id) {
                        SettingsRow("Make default", onClick = { container.playlists.setDefault(playlist.id) }, subtitle = "Its channels appear first", icon = Icons.Rounded.Star)
                    }
                    SettingsRow(
                        if (confirmRemove) "Press again to remove" else "Remove playlist",
                        onClick = {
                            if (confirmRemove) { container.playlists.remove(playlist.id); navigator.pop() } else confirmRemove = true
                        },
                        subtitle = if (confirmRemove) "This can't be undone" else null,
                        icon = Icons.Rounded.Delete, destructive = true,
                    )
                    SettingsHeading("Replace address or login")
                    PlaylistForm(editing = playlist, onSaved = { navigator.pop() })
                }
            }
        }
    }
}
