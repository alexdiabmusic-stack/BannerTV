package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.live.RankedSource
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

val ScoreTileWidth = 330.dp
val ScoreTileHeight = 140.dp

private fun shownScore(side: TeamSide, hidden: Boolean): String = if (hidden) "•" else side.score?.takeIf { it.isNotBlank() } ?: "–"

/**
 * A game in two lines: who is playing, the score and where it stands. The tile for scanning many games at once, so
 * it carries only what a glance needs; the Match Centre is one press away for the rest.
 */
@Composable
fun ScoreTile(
    match: Match,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    streamCount: Int = LocalMatchSources.current[match.key]?.confirmed?.size ?: 0,
) {
    val spoilerFree = LocalSpoilerFree.current
    val live = match.state == GameState.LIVE && !spoilerFree
    val showScore = match.state != GameState.PRE
    FocusCard(onClick = onClick, modifier = modifier.size(ScoreTileWidth, ScoreTileHeight)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(match.league.shortName.uppercase(), style = Type.Overline, color = Palette.AccessibleAccent, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (streamCount > 0 && match.state != GameState.FINAL) {
                    Icon(Icons.Rounded.PlayArrow, "Can be watched", tint = Palette.AccessibleAccent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                }
                when {
                    live -> LiveBadge(label = match.live.clock?.takeIf { it.isNotBlank() }?.let { "LIVE · $it" } ?: "LIVE")
                    match.state == GameState.FINAL -> Text("Final", style = Type.Caption, color = Palette.TextSecondary)
                    else -> Text(Dates.time(match.startMillis), style = Type.Caption, color = Palette.TextSecondary)
                }
            }
            TileTeam(match.away, if (showScore) shownScore(match.away, spoilerFree) else null, dimmed = match.state == GameState.FINAL && match.home.isWinner && !spoilerFree)
            TileTeam(match.home, if (showScore) shownScore(match.home, spoilerFree) else null, dimmed = match.state == GameState.FINAL && match.away.isWinner && !spoilerFree)
        }
    }
}

@Composable
private fun TileTeam(side: TeamSide, score: String?, dimmed: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TeamLogo(side.logoUrl, 30.dp, dimmed = dimmed)
        Text(
            side.shortName.ifBlank { side.abbreviation },
            style = Type.BodyStrong,
            color = if (dimmed) Palette.TextSecondary else Palette.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (score != null) Text(score, style = Type.Headline, color = if (dimmed) Palette.TextSecondary else Palette.TextPrimary, maxLines = 1)
        else if (!side.record.isNullOrBlank()) Text(side.record.orEmpty(), style = Type.Caption, color = Palette.TextTertiary, maxLines = 1)
    }
}

/** The last tile of a row of games: the way into the full list. */
@Composable
fun ViewAllTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 220.dp, height: androidx.compose.ui.unit.Dp = ScoreTileHeight) {
    FocusCard(onClick = onClick, modifier = modifier.size(width, height)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.ArrowForward, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = Type.BodyStrong, color = Palette.TextPrimary, textAlign = TextAlign.Center)
        }
    }
}

/** Why a game has, or does not have, a stream to start: the card says which, so "nothing yet" is never a guess. */
enum class WatchState { Ready, Checking, NoMatch, NoPlaylist }

/**
 * The game to lead Home with, as a scoreboard rather than a billboard: both teams, the score, the clock, and the two
 * things a viewer does next. "Watch Live" is offered only when a channel is confirmed for this game; otherwise the
 * button says what is true ("Find a stream") and the card says why nothing is playing yet.
 */
@Composable
fun FeaturedGameCard(
    match: Match,
    source: RankedSource?,
    sourceName: String?,
    possibleCount: Int,
    watch: WatchState,
    onWatch: () -> Unit,
    onMatchCentre: () -> Unit,
    modifier: Modifier = Modifier,
    /** Attached to the main button, so a screen can send the remote there first. */
    primaryFocus: FocusRequester? = null,
) {
    val spoilerFree = LocalSpoilerFree.current
    val live = match.state == GameState.LIVE && !spoilerFree
    val tint = remember(match.home.colorHex, match.away.colorHex) {
        val away = parseTeamColor(match.away.colorHex)?.let { lerp(Palette.Surface, it, 0.34f) } ?: Palette.SurfaceElevated
        val home = parseTeamColor(match.home.colorHex)?.let { lerp(Palette.Surface, it, 0.34f) } ?: Palette.SurfaceElevated
        Brush.horizontalGradient(0f to away, 0.5f to Palette.Surface, 1f to home)
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(232.dp)
            .background(tint, RoundedCornerShape(Shapes.Xl))
            .padding(horizontal = 44.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Column(Modifier.width(660.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(match.league.name.uppercase(), style = Type.Overline, color = Palette.AccessibleAccent, maxLines = 1)
                when {
                    live -> LiveBadge(label = match.statusDetail.uppercase().ifBlank { "LIVE" })
                    match.state == GameState.PRE -> Text(Dates.kickoff(match.startMillis), style = Type.Callout, color = Palette.TextSecondary)
                    else -> Text(match.statusDetail, style = Type.Callout, color = Palette.TextSecondary)
                }
            }
            FeaturedTeamRow(match.away, match, spoilerFree)
            FeaturedTeamRow(match.home, match, spoilerFree)
        }
        // What is going on in the game, or where it is being played: the room between the score and the buttons.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lines = remember(match) {
                buildList {
                    if (match.state == GameState.LIVE) addAll(match.live.situation.take(2))
                    if (match.broadcasts.isNotEmpty()) add(match.broadcasts.take(3).joinToString(" · "))
                    match.venue?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            lines.take(3).forEach { Text(it, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(Modifier.width(430.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusPill(
                onClick = if (source != null) onWatch else onMatchCentre, selected = source != null, height = 60.dp,
                modifier = Modifier.fillMaxWidth().let { if (primaryFocus != null) it.focusRequester(primaryFocus) else it },
            ) {
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (source != null) Icons.Rounded.PlayArrow else Icons.Rounded.SportsScore, null, tint = Palette.TextPrimary, modifier = Modifier.size(28.dp))
                    Text(
                        when {
                            source != null && live -> "Watch Live"
                            source != null -> "Watch"
                            watch == WatchState.NoMatch -> "Find a stream"
                            else -> "Match Centre"
                        },
                        style = Type.Headline, color = Palette.TextPrimary,
                    )
                }
            }
            if (source != null) {
                Text("on ${sourceName ?: source.channel.name}", style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
            } else {
                Text(
                    when (watch) {
                        WatchState.Checking -> "Checking your channels for this game…"
                        WatchState.NoPlaylist -> "Add a playlist in Settings to watch"
                        else -> if (possibleCount > 0) "No confirmed channel yet · $possibleCount possible" else "No channel confirmed for this game yet"
                    },
                    style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (source != null) {
                FocusPill(onClick = onMatchCentre, height = 52.dp, modifier = Modifier.fillMaxWidth()) {
                    Text("Match Centre", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun FeaturedTeamRow(side: TeamSide, match: Match, spoilerFree: Boolean) {
    val showScore = match.state != GameState.PRE
    val dimmed = match.state == GameState.FINAL && !side.isWinner && !spoilerFree
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        TeamLogo(side.logoUrl, 54.dp, dimmed = dimmed)
        Text(
            side.displayName, style = Type.Title3,
            color = if (dimmed) Palette.TextSecondary else Palette.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (showScore) Text(shownScore(side, spoilerFree), style = Type.Title1, color = if (dimmed) Palette.TextSecondary else Color.White, maxLines = 1)
        else if (!side.record.isNullOrBlank()) Text(side.record.orEmpty(), style = Type.Callout, color = Palette.TextSecondary, maxLines = 1)
    }
}
