package com.alexdiab.stadiatv.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.live.Playlist
import com.alexdiab.stadiatv.live.PlaylistKind
import com.alexdiab.stadiatv.ui.components.FieldGroup
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.Spinner
import com.alexdiab.stadiatv.ui.components.TvTextField
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * Add or replace a playlist. The address and credentials are checked against the provider before anything is saved,
 * so a typo shows up here, with a plain explanation, instead of as an empty Live TV screen later.
 */
@Composable
fun PlaylistForm(
    editing: Playlist?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    saveLabel: String = if (editing == null) "Check & Add" else "Check & Save",
) {
    val container = LocalContainer.current
    val store = container.playlists
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(editing?.kind ?: PlaylistKind.M3U) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var url by remember { mutableStateOf(editing?.m3uUrl.orEmpty()) }
    var host by remember { mutableStateOf(editing?.host.orEmpty()) }
    val savedCredentials = remember(editing?.id) { editing?.let { store.credentials(it.id) } }
    var username by remember { mutableStateOf(savedCredentials?.username.orEmpty()) }
    var password by remember { mutableStateOf(savedCredentials?.password.orEmpty()) }
    var userAgent by remember { mutableStateOf(editing?.userAgent.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError

    fun save() {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = if (kind == PlaylistKind.M3U) store.validateM3u(url, userAgent) else store.validateXtream(host, username, password, userAgent)
            result.onSuccess { count ->
                message = "Found $count channels." to false
                if (editing == null) {
                    if (kind == PlaylistKind.M3U) store.addM3u(name, url, userAgent) else store.addXtream(name, host, username, password, userAgent)
                } else {
                    if (kind == PlaylistKind.M3U) store.replaceM3u(editing.id, name, url, userAgent) else store.replaceXtream(editing.id, name, host, username, password, userAgent)
                }
                busy = false
                onSaved()
            }.onFailure {
                message = (it.message ?: "Couldn't load this playlist.") to true
                busy = false
            }
        }
    }

    FieldGroup(modifier) {
        if (editing == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FilterChip("M3U Link", kind == PlaylistKind.M3U, { kind = PlaylistKind.M3U }, icon = Icons.Rounded.Link)
                FilterChip("Stream Login", kind == PlaylistKind.XTREAM, { kind = PlaylistKind.XTREAM }, icon = Icons.Rounded.VpnKey)
            }
        }
        TvTextField(name, { name = it }, "Name", placeholder = if (kind == PlaylistKind.M3U) "My Playlist" else "Stream Login")
        if (kind == PlaylistKind.M3U) {
            TvTextField(url, { url = it }, "Playlist URL", placeholder = "http://example.com/playlist.m3u", keyboardType = KeyboardType.Uri)
        } else {
            TvTextField(host, { host = it }, "Server address", placeholder = "http://example.com:8080", keyboardType = KeyboardType.Uri)
            TvTextField(username, { username = it }, "Username")
            TvTextField(password, { password = it }, "Password", secret = true)
        }
        TvTextField(userAgent, { userAgent = it }, "User-Agent (optional)", placeholder = "Only if your provider requires one")

        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FocusPill(onClick = ::save, selected = true, height = 60.dp) {
                Row(Modifier.padding(horizontal = 36.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) Spinner(size = 26.dp, color = androidx.compose.ui.graphics.Color.White)
                    Text(if (busy) "Checking…" else saveLabel, style = Type.Headline, color = androidx.compose.ui.graphics.Color.White)
                }
            }
            message?.let { (text, isError) ->
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle, null, tint = if (isError) Palette.Live else Palette.Upcoming, modifier = Modifier.size(28.dp))
                    Text(text, style = Type.Body, color = if (isError) Palette.Live else Palette.Upcoming, maxLines = 3)
                }
            }
        }
    }
}
