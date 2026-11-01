package com.alexdiab.stadiatv.ui.fantasy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.data.fantasy.SlotKind
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.player.PanelFrame
import com.alexdiab.stadiatv.ui.theme.Type
import kotlinx.coroutines.delay

/** Your matchup score and starting lineup, beside the game you're watching. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FantasyPanel() {
    val container = LocalContainer.current
    val state by container.fantasy.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    LaunchedEffect(Unit) {
        while (true) {
            container.fantasy.refresh()
            delay(PANEL_REFRESH_MILLIS)
        }
    }
    val starters = snapshot?.roster?.filter { it.kind == SlotKind.Starter }.orEmpty()
    PanelFrame("Fantasy", subtitle = snapshot?.leagueName ?: state.connection?.leagueName) {
        if (snapshot == null) {
            Text(state.error ?: "Loading your league…", style = Type.Body, color = Color.White.copy(alpha = 0.6f))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(bottom = 80.dp, start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                snapshot.matchup?.let { item("matchup") { MatchupCard(it) } }
                if (starters.isEmpty()) item("empty") {
                    Text("Add your team ID in Settings to see your lineup here.", style = Type.Callout, color = Color.White.copy(alpha = 0.6f))
                }
                items(starters, key = { it.id }) { player ->
                    PlayerRow(player, if (player.id == starters.first().id) Modifier.initialFocus() else Modifier)
                }
            }
        }
    }
}

private const val PANEL_REFRESH_MILLIS = 30_000L
