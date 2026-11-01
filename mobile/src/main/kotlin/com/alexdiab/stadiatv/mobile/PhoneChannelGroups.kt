@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alexdiab.stadiatv.live.Channel
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class PhoneChannelGroup(val id: String, val name: String, val entries: List<String>)

/** Stable lineup identities survive changing HD/SD/provider stream selections. */
fun channelGroupKey(model: MobileViewModel, channel: Channel): String = model.container.lineup.lineup.value.entryFor(channel.key)?.key ?: channel.key

fun groupedChannels(base: List<Channel>, group: PhoneChannelGroup?, model: MobileViewModel): List<Channel> {
    return groupedChannels(base, group) { channelGroupKey(model, it) }
}

fun groupedChannels(base: List<Channel>, group: PhoneChannelGroup?, keyFor: (Channel) -> String): List<Channel> {
    group ?: return base
    val order = group.entries.withIndex().associate { it.value to it.index }
    return base.mapNotNull { channel -> val rank = order[keyFor(channel)] ?: order[channel.key]; rank?.let { channel to it } }.sortedBy { it.second }.map { it.first }
}

@Composable
fun ChannelGroupsScreen(model: MobileViewModel) {
    val prefs by model.library.state.collectAsStateWithLifecycle()
    val playlist by model.container.playlists.state.collectAsStateWithLifecycle()
    val lineup by model.container.lineup.lineup.collectAsStateWithLifecycle()
    val choices by model.container.lineup.prefs.collectAsStateWithLifecycle()
    val channels = remember(playlist.revision, lineup.revision, choices) { if (lineup.entries.isEmpty()) playlist.channels else lineup.entries.map { it.pick(choices).channel.copy(canonicalName = it.name) } }
    var editing by remember { mutableStateOf<PhoneChannelGroup?>(null) }
    var deleting by remember { mutableStateOf<PhoneChannelGroup?>(null) }
    ScreenList {
        item { Text("Your channel collections", style = MaterialTheme.typography.headlineMedium); Text("Create groups for sports nights, family favorites or any mix of channels. Groups work in both Channels and Guide.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(enabled = prefs.channelGroups.size < 20, onClick = { editing = PhoneChannelGroup(UUID.randomUUID().toString(), "", emptyList()) }) { Icon(Icons.Rounded.Add, null); Text("Create group") } }
        items(prefs.channelGroups, key = { it.id }) { group -> PhoneCard {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(group.name, style = MaterialTheme.typography.titleMedium); Text("${group.entries.size} saved channels", style = MaterialTheme.typography.labelMedium) }; TextButton(onClick = { editing = group }) { Text("Edit") }; IconButton(onClick = { deleting = group }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${group.name}") } }
            val members = groupedChannels(channels, group, model)
            members.take(8).forEachIndexed { index, channel -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.canonicalName ?: channel.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(enabled = index > 0, onClick = { val key = channelGroupKey(model, channel); model.library.update { pref -> pref.copy(channelGroups = pref.channelGroups.map { item -> if (item.id != group.id) item else { val changed = item.entries.toMutableList(); val at = changed.indexOf(key); if (at > 0) changed.add(at - 1, changed.removeAt(at)); item.copy(entries = changed) } }) } }) { Icon(Icons.Rounded.ArrowUpward, "Move channel up") }
                IconButton(enabled = index < members.lastIndex, onClick = { val key = channelGroupKey(model, channel); model.library.update { pref -> pref.copy(channelGroups = pref.channelGroups.map { item -> if (item.id != group.id) item else { val changed = item.entries.toMutableList(); val at = changed.indexOf(key); if (at >= 0 && at < changed.lastIndex) changed.add(at + 1, changed.removeAt(at)); item.copy(entries = changed) } }) } }) { Icon(Icons.Rounded.ArrowDownward, "Move channel down") }
            } }
            if (members.size > 8) Text("${members.size - 8} more channels · edit membership", style = MaterialTheme.typography.labelSmall)
        } }
    }
    editing?.let { original ->
        var name by remember(original.id) { mutableStateOf(original.name) }
        var selected by remember(original.id) { mutableStateOf(original.entries.toSet()) }
        var order by remember(original.id) { mutableStateOf(original.entries) }
        var reordering by remember(original.id) { mutableStateOf(false) }
        var query by rememberSaveable(original.id) { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val visible = remember(channels, query) { channels.filter { query.isBlank() || "${it.canonicalName} ${it.name} ${it.group}".contains(query, true) } }
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxHeight(.9f).padding(horizontal = 20.dp)) {
                Text("Edit channel group", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Group name") }, singleLine = true)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Find channels") }, singleLine = true)
                Text("${selected.size} selected · choices are saved when you tap Done", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { order = order.filter { it in selected } + selected.filter { it !in order }; reordering = !reordering }) { Text(if (reordering) "Choose membership" else "Reorder selected channels") }
                val listed = if (reordering) groupedChannels(channels, original.copy(entries = order), model) else visible
                LazyColumn(Modifier.weight(1f)) { items(listed, key = { it.key }) { channel -> val id = channelGroupKey(model, channel); Row(Modifier.fillMaxWidth().clickable(enabled = !reordering) { selected = if (id in selected) selected - id else selected + id }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!reordering) Checkbox(id in selected, null)
                    Spacer(Modifier.width(8.dp)); Text(channel.canonicalName ?: channel.name, Modifier.weight(1f))
                    if (reordering) {
                        val index = order.indexOf(id)
                        IconButton(enabled = index > 0, onClick = { order = order.toMutableList().apply { add(index - 1, removeAt(index)) } }) { Icon(Icons.Rounded.ArrowUpward, "Move channel up") }
                        IconButton(enabled = index >= 0 && index < order.lastIndex, onClick = { order = order.toMutableList().apply { add(index + 1, removeAt(index)) } }) { Icon(Icons.Rounded.ArrowDownward, "Move channel down") }
                    }
                } } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    val clean = name.trim()
                    if (clean.isEmpty() || clean.lowercase() in setOf("all", "all channels", "favorites", "sports") || prefs.channelGroups.any { it.id != original.id && it.name.equals(clean, true) }) error = "Choose a unique group name other than All, All channels, Favorites or Sports."
                    else { val oldOrder = order.filter { it in selected }; val added = selected.filter { it !in oldOrder }; val group = original.copy(name = clean, entries = (oldOrder + added).take(5000)); model.library.update { it.copy(channelGroups = it.channelGroups.filterNot { old -> old.id == group.id } + group) }; editing = null }
                }, Modifier.fillMaxWidth().navigationBarsPadding()) { Text("Done") }
            }
        }
    }
    deleting?.let { group -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${group.name}?") }, text = { Text("Only this custom group is removed. Playlists, channels and favorites are unchanged.") }, confirmButton = { TextButton(onClick = { model.library.update { it.copy(channelGroups = it.channelGroups.filterNot { item -> item.id == group.id }) }; deleting = null }) { Text("Delete group") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
fun ChannelGroupMenu(channel: Channel, model: MobileViewModel) {
    val prefs by model.library.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Channel group actions") }
        DropdownMenu(open, { open = false }) {
            val key = channelGroupKey(model, channel)
            prefs.channelGroups.forEach { group -> DropdownMenuItem(text = { Text(if (key in group.entries) "Remove from ${group.name}" else "Add to ${group.name}") }, onClick = { model.library.update { it.copy(channelGroups = it.channelGroups.map { item -> if (item.id == group.id) item.copy(entries = if (key in item.entries) item.entries - key else (item.entries + key).take(5000)) else item }) }; open = false }) }
            DropdownMenuItem(text = { Text("Manage channel groups") }, onClick = { open = false; model.open(PhonePage.ChannelGroups) })
        }
    }
}
