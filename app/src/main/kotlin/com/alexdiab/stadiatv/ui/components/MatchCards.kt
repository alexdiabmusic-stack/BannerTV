package com.alexdiab.stadiatv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.live.MatchSources
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Type

/** When true, scores and live status are hidden everywhere a match is drawn. */
val LocalSpoilerFree = compositionLocalOf { false }

/** Playlist streams linked to each match (by [Match.key]), shown as the play badge on cards. */
val LocalMatchSources = compositionLocalOf<Map<String, MatchSources>> { emptyMap() }

internal fun parseTeamColor(hex: String?): Color? {
    val clean = hex?.trim()?.removePrefix("#") ?: return null
    if (clean.length != 6) return null
    return clean.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** Splits "Today · 7:30 PM" into ("Today", "7:30 PM"). Anything else stays whole. */
private fun splitKickoff(detail: String): Pair<String, String> {
    val parts = detail.split(" · ", limit = 2)
    return if (parts.size == 2) parts[0] to parts[1] else "" to detail
}

private fun scoreText(match: Match, spoilerFree: Boolean): String {
    if (spoilerFree) return "• – •"
    return "${match.away.score?.takeIf { it.isNotBlank() } ?: "–"} – ${match.home.score?.takeIf { it.isNotBlank() } ?: "–"}"
}

// region Shelf card

val MatchCardWidth = 360.dp
val MatchCardHeight = 204.dp

/** Focusable match card for horizontal shelves. */
@Composable
fun MatchCard(
    match: Match,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    streamCount: Int = LocalMatchSources.current[match.key]?.count ?: 0,
) {
    val spoilerFree = LocalSpoilerFree.current
    val live = match.state == GameState.LIVE && !spoilerFree
    FocusCard(
        onClick = onClick,
        modifier = modifier.size(MatchCardWidth, MatchCardHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            // Fixed height: a badge in the row must not push the team row down on only some cards.
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    match.league.shortName.uppercase(),
                    style = Type.Overline,
                    color = Palette.AccessibleAccent,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (streamCount > 0 && match.state != GameState.FINAL) {
                    Chip("$streamCount", style = ChipStyle.Accent, icon = Icons.Rounded.PlayArrow)
                    Spacer(Modifier.width(8.dp))
                }
                when {
                    live -> LiveBadge()
                    match.state == GameState.FINAL && !spoilerFree -> Chip("FINAL")
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TeamColumn(match.away, dimmed = match.state == GameState.FINAL && match.home.isWinner && !spoilerFree, Modifier.weight(1f))
                CenterColumn(match, spoilerFree, Modifier.weight(1.25f))
                TeamColumn(match.home, dimmed = match.state == GameState.FINAL && match.away.isWinner && !spoilerFree, Modifier.weight(1f))
            }
            Text(
                text = when {
                    match.broadcasts.isNotEmpty() -> match.broadcasts.take(3).joinToString(" · ")
                    match.venue != null -> match.venue.orEmpty()
                    else -> ""
                },
                style = Type.Caption,
                color = Palette.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TeamColumn(side: TeamSide, dimmed: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TeamLogo(side.logoUrl, 60.dp, dimmed = dimmed)
        Text(
            side.abbreviation.ifBlank { side.shortName },
            style = Type.Caption,
            color = if (dimmed) Palette.TextSecondary else Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenterColumn(match: Match, spoilerFree: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (match.state != GameState.PRE) {
            Text(scoreText(match, spoilerFree), style = Type.ScoreMd, color = Color.White, maxLines = 1)
            if (match.state == GameState.LIVE && !spoilerFree) {
                Text(
                    match.statusDetail,
                    style = Type.Caption,
                    color = Palette.Live,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                match.live.situation.firstOrNull()?.let {
                    Text(it, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            val (day, time) = remember(match.statusDetail) { splitKickoff(match.statusDetail) }
            if (day.isNotEmpty()) Text(day, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1)
            Text(time, style = Type.Title3, color = Palette.TextPrimary, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

// endregion

// region Hero

/** Full-width featured match. Gradient is tinted by the two team colours. */
@Composable
fun HeroCard(
    match: Match,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 400.dp,
) {
    val spoilerFree = LocalSpoilerFree.current
    val live = match.state == GameState.LIVE && !spoilerFree
    val brush = remember(match.home.colorHex, match.away.colorHex) {
        val base = Palette.Abyss
        val away = parseTeamColor(match.away.colorHex)?.let { lerp(base, it, 0.62f) } ?: Palette.Navy
        val home = parseTeamColor(match.home.colorHex)?.let { lerp(base, it, 0.62f) } ?: Palette.Navy
        Brush.horizontalGradient(
            0f to away,
            0.5f to lerp(base, Palette.Background, 0.6f),
            1f to home,
        )
    }
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(Shapes.Xl),
        focusedScale = 1.02f,
        background = brush,
        restingBorder = Palette.Hairline,
    ) {
        // Legibility scrim.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x66000000)))))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroTeam(match.away, dimmed = match.state == GameState.FINAL && match.home.isWinner && !spoilerFree, Modifier.weight(1f))
            Column(
                Modifier.weight(1.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    match.league.name.uppercase(),
                    style = Type.Overline,
                    color = Palette.AccessibleAccent,
                    maxLines = 1,
                )
                if (match.state != GameState.PRE) {
                    Text(scoreText(match, spoilerFree), style = Type.ScoreXl, color = Color.White, maxLines = 1)
                } else {
                    Text("VS", style = Type.Display, color = Color.White.copy(alpha = 0.42f))
                }
                if (live) LiveBadge(label = match.statusDetail.uppercase().ifBlank { "LIVE" })
                else Text(
                    if (match.state == GameState.FINAL && spoilerFree) "Final" else match.statusDetail,
                    style = Type.BodyStrong,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier
                        .background(Palette.ActionFill, RoundedCornerShape(50))
                        .padding(horizontal = 26.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(if (match.state == GameState.FINAL) "Match Centre" else "View Streams", style = Type.Headline, color = Color.White)
                }
            }
            HeroTeam(match.home, dimmed = match.state == GameState.FINAL && match.away.isWinner && !spoilerFree, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroTeam(side: TeamSide, dimmed: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TeamLogo(side.logoUrl, 132.dp, dimmed = dimmed)
        Text(
            side.displayName,
            style = Type.Title3,
            color = if (dimmed) Color.White.copy(alpha = 0.55f) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        side.record?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Type.Callout, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
        }
    }
}

// endregion

// region Row

/** Compact full-width fixture row, used for "Your Sports Day" and the schedule. */
@Composable
fun ScheduleRow(match: Match, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val spoilerFree = LocalSpoilerFree.current
    val live = match.state == GameState.LIVE && !spoilerFree
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(84.dp),
        shape = RoundedCornerShape(Shapes.Md),
        focusedScale = 1.015f,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterStart) {
                when {
                    live -> LiveBadge()
                    match.state == GameState.FINAL -> Text("FINAL", style = Type.Overline, color = Palette.TextSecondary)
                    else -> Text(match.league.shortName.uppercase(), style = Type.Overline, color = Palette.AccessibleAccent)
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TeamLogo(match.away.logoUrl, 44.dp)
                Text(match.away.shortName, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.width(210.dp), contentAlignment = Alignment.Center) {
                if (match.state != GameState.PRE) {
                    Text(
                        scoreText(match, spoilerFree),
                        style = Type.ScoreMd,
                        color = if (live) Palette.Live else Palette.TextPrimary,
                        maxLines = 1,
                    )
                } else {
                    Text(match.statusDetail, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
            ) {
                Text(match.home.shortName, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TeamLogo(match.home.logoUrl, 44.dp)
            }
        }
    }
}

// endregion
