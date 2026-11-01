package com.alexdiab.stadiatv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.domain.SoccerCommentaryWindow
import com.alexdiab.stadiatv.domain.SoccerDetail
import kotlinx.coroutines.launch

/** iOS narrative feed: newest first, explicit return to latest, no opaque IncidentCode headings. */
@Composable
internal fun SoccerCommentaryFeed(detail: SoccerDetail?, gameKey: String, loadOlder: () -> Unit) {
    val incoming = detail?.commentary.orEmpty()
    var window by remember(gameKey) { mutableStateOf(SoccerCommentaryWindow()) }
    var shown by rememberSaveable(gameKey) { mutableIntStateOf(40) }
    val listState = rememberSaveable(gameKey, saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val atLatest by remember(listState) { derivedStateOf {
        listState.firstVisibleItemIndex == 0 || listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset == 0
    } }
    LaunchedEffect(incoming, atLatest) { window = window.update(incoming, atLatest) }
    val comments = if (window.initialized) window.displayed else incoming
    Column(Modifier.fillMaxSize()) {
        if (incoming.isNotEmpty() && (!atLatest || window.deferredIds.isNotEmpty())) {
            val pending = window.deferredIds.size
            FilledTonalButton(onClick = {
                window = window.update(incoming, true)
                scope.launch { listState.scrollToItem(0) }
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).heightIn(min = 48.dp)
                .semantics { if (pending > 0) liveRegion = LiveRegionMode.Polite }) {
                Icon(Icons.Rounded.ArrowUpward, null)
                Spacer(Modifier.width(8.dp))
                Text(if (pending == 0) "Jump to latest" else "$pending new ${if (pending == 1) "update" else "updates"} · Jump to latest")
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("source-time") {
                val source = detail?.surfaceSources?.get("commentary")
                val at = detail?.surfaceUpdatedAt?.get("commentary")
                Text(listOfNotNull(source, "Commentary", at?.let(::snapshotTime)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (incoming.isEmpty()) item("empty") {
                EmptyState("No commentary yet", "Live commentary appears when supplied by the match feed.")
            }
            items(comments.take(shown), key = { it.id }) { entry ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entry.clock.isNotBlank()) Text(entry.clock, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (shown < comments.size || detail?.nextCursor != null && incoming.size < 500) item("older") {
                OutlinedButton(onClick = {
                    if (shown < comments.size) shown += 40 else { loadOlder(); shown += 50 }
                }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Load older commentary") }
            }
        }
    }
}
