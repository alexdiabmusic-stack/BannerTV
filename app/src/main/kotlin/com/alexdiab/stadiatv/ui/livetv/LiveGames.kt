package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.LiveBadge
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

private class GameChannel(val label: String, val channel: Channel, val query: String)

/**
 * Games on now or starting soon, each with the channels from your lineup that carry it. Playing a channel opens the
 * stream; "Guide" jumps to that channel in the on-now list so you can see what else is on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveGamesView(viewModel: GuideViewModel, ui: GuideUi, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val sections by container.matchFeed.sections.collectAsStateWithLifecycle()
    val sources by container.streams.sources.collectAsStateWithLifecycle()
    val lineup by container.lineup.lineup.collectAsStateWithLifecycle()
    val prefs by container.lineup.prefs.collectAsStateWithLifecycle()

    val horizon = remember(sections) { System.currentTimeMillis() + 3 * 3600_000L }
    val games = remember(sections, sources, lineup, prefs) {
        sections.all
            .filter { it.state == GameState.LIVE || it.state == GameState.PRE && it.startMillis <= horizon }
            .sortedWith(compareBy({ it.state != GameState.LIVE }, { it.startMillis }))
            .map { match ->
                val channels = container.streams.sourcesFor(match).confirmed.map { source ->
                    val entry = lineup.entryFor(source.channel.key)
                    // Keep the exact EPG-backed feed. A preferred mirror can have a different guide.
                    GameChannel(entry?.name ?: source.channel.name.trim(), source.channel, entry?.name ?: source.channel.name)
                }.distinctBy { it.channel.key }
                match to channels
            }
            .filter { it.second.isNotEmpty() }
    }

    if (games.isEmpty()) {
        EmptyState(
            Icons.Rounded.SportsScore, "No games on your channels right now",
            subtitle = "Games appear here when one is live or about to start and a channel in your lineup carries it.",
            modifier = modifier,
        )
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(games, key = { it.first.key }) { (match, channels) ->
            GameCard(match, channels, onPlay = { option ->
                val current = container.streams.sourcesFor(match).confirmed.firstOrNull { it.channel.key == option.channel.key }
                if (current != null) navigator.push(Route.Player(current.channel, match)) else navigator.push(Route.MatchDetail(match))
            }, onGuide = {
                viewModel.query.value = it.query
                viewModel.select(GuideFilter.All)
                viewModel.setView(GuideView.OnNow)
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameCard(match: Match, channels: List<GameChannel>, onPlay: (GameChannel) -> Unit, onGuide: (GameChannel) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(20.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (match.state == GameState.LIVE) LiveBadge()
            Text(match.league.name, style = Type.Overline, color = Palette.AccessibleAccent)
            Text(
                if (match.state == GameState.LIVE) match.statusDetail else Dates.time(match.startMillis),
                style = Type.Callout, color = Palette.TextSecondary, maxLines = 1,
            )
        }
        val score = if (match.hasDisplayScore) "  ${match.away.score ?: ""} - ${match.home.score ?: ""}" else ""
        Text("${match.away.displayName} at ${match.home.displayName}$score", style = Type.Title3, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            channels.forEach { option ->
                FocusPill(onClick = { onPlay(option) }, selected = true) {
                    Text(option.label, style = Type.BodyStrong, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 26.dp).align(Alignment.Center), maxLines = 1)
                }
                FocusPill(onClick = { onGuide(option) }) {
                    Text("Guide", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 22.dp).align(Alignment.Center), maxLines = 1)
                }
            }
        }
    }
}
