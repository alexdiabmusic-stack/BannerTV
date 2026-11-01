package com.alexdiab.stadiatv.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.BoxGroup
import com.alexdiab.stadiatv.domain.GameCentre
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.LeaderComparison
import com.alexdiab.stadiatv.domain.LeaderEntry
import com.alexdiab.stadiatv.domain.LineScore
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.Play
import com.alexdiab.stadiatv.domain.StatComparison
import com.alexdiab.stadiatv.ui.common.Async
import com.alexdiab.stadiatv.ui.common.rememberAsync
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.Headshot
import com.alexdiab.stadiatv.ui.components.LocalSpoilerFree
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import kotlinx.coroutines.delay

private enum class CentreTab(val label: String) { Overview("Overview"), Plays("Plays"), Box("Box Score") }

/**
 * Live detail for a match: line score, team stats, leaders, play-by-play and box score. Refreshes every 20 seconds while
 * the game is live. Hidden entirely in spoiler-free mode and before the game starts.
 */
@Composable
fun GameCentreSection(match: Match) {
    if (LocalSpoilerFree.current || match.state == GameState.PRE) return
    val container = LocalContainer.current
    val loader = rememberAsync("centre-${match.key}") { container.sports.gameCentre(match) }
    LaunchedEffect(match.key, match.state) {
        if (match.state == GameState.LIVE) while (true) { delay(20_000); loader.reload() }
    }
    val state = loader.state.value
    val centre = (state as? Async.Success)?.value ?: return
    if (centre.isEmpty) return

    var tabName by rememberSaveable { mutableStateOf(CentreTab.Overview.name) }
    val tabs = remember(centre) {
        buildList {
            add(CentreTab.Overview)
            if (centre.plays.isNotEmpty()) add(CentreTab.Plays)
            if (centre.boxScore.isNotEmpty()) add(CentreTab.Box)
        }
    }
    val tab = tabs.firstOrNull { it.name == tabName } ?: CentreTab.Overview

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Match Centre", style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = Spacing.ScreenH))
        ChipRow {
            tabs.forEach { item -> item(key = item.name) { FilterChip(item.label, item == tab, { tabName = item.name }) } }
        }
        Column(Modifier.padding(horizontal = Spacing.ScreenH), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                CentreTab.Overview -> Overview(centre)
                CentreTab.Plays -> centre.plays.take(40).forEach { PlayRow(it, match) }
                CentreTab.Box -> centre.boxScore.forEach { team ->
                    Text(team.teamName, style = Type.Title3, color = Palette.AccessibleAccent, modifier = Modifier.padding(top = 10.dp))
                    team.groups.forEach { BoxGroupTable(it) }
                }
            }
        }
    }
}

@Composable
private fun Overview(centre: GameCentre) {
    centre.lineScore?.let { LineScoreTable(it) }
    if (centre.leaders.isNotEmpty()) {
        Text("Top Performers", style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(top = 10.dp))
        centre.leaders.forEach { LeaderRow(it) }
    }
    if (centre.teamStats.isNotEmpty()) {
        Text("Team Stats", style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(top = 10.dp))
        centre.teamStats.forEach { StatBar(it) }
    }
}

// region Rows

@Composable
private fun RowCard(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 72.dp, maxWidth: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Unspecified, content: @Composable () -> Unit) {
    FocusCard(
        onClick = {},
        modifier = modifier.widthIn(max = maxWidth).fillMaxWidth().height(height),
        shape = RoundedCornerShape(Shapes.Md),
        focusedScale = 1.01f,
    ) { Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) { content() } }
}

@Composable
private fun LineScoreTable(line: LineScore) {
    RowCard(height = 148.dp, maxWidth = (190 + 68 * line.periods.size + 90).coerceAtLeast(520).dp) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            ScoreLine("", line.periods, "T", header = true)
            ScoreLine(line.awayLabel, line.away, line.awayTotal)
            ScoreLine(line.homeLabel, line.home, line.homeTotal)
        }
    }
}

@Composable
private fun ScoreLine(label: String, cells: List<String>, total: String, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.width(120.dp), maxLines = 1)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.forEach { Text(it, style = if (header) Type.Overline else Type.Digits, color = if (header) Palette.TextSecondary else Palette.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp), maxLines = 1) }
        }
        Text(total, style = if (header) Type.Overline else Type.ScoreMd, color = if (header) Palette.TextSecondary else Palette.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.width(96.dp))
    }
}

@Composable
private fun StatBar(stat: StatComparison) {
    RowCard(height = 76.dp) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stat.away, style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.width(140.dp), maxLines = 1)
                Text(stat.label, style = Type.Callout, color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stat.home, style = Type.BodyStrong, color = Palette.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.width(140.dp), maxLines = 1)
            }
            stat.awayShare?.let { share ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).background(Palette.SurfaceHigh, RoundedCornerShape(3.dp))) {
                    Box(Modifier.weight(share.coerceAtLeast(0.02f)).fillMaxHeight().background(Palette.Accent, RoundedCornerShape(3.dp)))
                    Box(Modifier.weight((1f - share).coerceAtLeast(0.02f)).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun LeaderRow(leader: LeaderComparison) {
    RowCard(height = 96.dp) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            LeaderSide(leader.away, Modifier.weight(1f), alignEnd = false)
            Text(leader.category, style = Type.Callout, color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(190.dp).padding(horizontal = 8.dp), maxLines = 2)
            LeaderSide(leader.home, Modifier.weight(1f), alignEnd = true)
        }
    }
}

@Composable
private fun LeaderSide(entry: LeaderEntry?, modifier: Modifier, alignEnd: Boolean) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignEnd) Arrangement.spacedBy(14.dp, Alignment.End) else Arrangement.spacedBy(14.dp)) {
        if (entry == null) { Text("—", style = Type.Body, color = Palette.TextTertiary); return@Row }
        if (!alignEnd) Headshot(entry.headshotUrl, 56.dp)
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            Text(entry.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(entry.value, entry.detail).filter { it.isNotBlank() }.joinToString(" · "), style = Type.Caption, color = Palette.AccessibleAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (alignEnd) Headshot(entry.headshotUrl, 56.dp)
    }
}

@Composable
private fun PlayRow(play: Play, match: Match) {
    val team = when (play.teamId) { match.home.teamId -> match.home.abbreviation; match.away.teamId -> match.away.abbreviation; else -> "" }
    RowCard(height = 84.dp, modifier = if (play.isScoring) Modifier.border(1.dp, Palette.Accent.copy(alpha = 0.4f), RoundedCornerShape(Shapes.Md)) else Modifier) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(120.dp)) {
                Text(play.clock.ifBlank { play.period }, style = Type.Digits, color = Palette.AccessibleAccent, maxLines = 1)
                if (play.clock.isNotBlank() && play.period.isNotBlank()) Text(play.period, style = Type.Caption, color = Palette.TextTertiary, maxLines = 1)
            }
            Text(play.text, style = if (play.isScoring) Type.BodyStrong else Type.Body, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (team.isNotEmpty()) Text(team, style = Type.Caption, color = Palette.TextSecondary)
            if (play.awayScore != null && play.homeScore != null) Text("${play.awayScore}–${play.homeScore}", style = Type.Digits, color = Palette.TextPrimary)
        }
    }
}

@Composable
private fun BoxGroupTable(group: BoxGroup) {
    Text(group.title, style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
    RowCard(height = 56.dp) { BoxLine("", group.labels, header = true) }
    group.rows.take(14).forEach { row ->
        RowCard(height = 64.dp) { BoxLine(row.name, row.stats, detail = row.detail) }
    }
    if (group.totals.isNotEmpty()) RowCard(height = 60.dp) { BoxLine("Totals", group.totals, emphasized = true) }
}

@Composable
private fun BoxLine(name: String, cells: List<String>, header: Boolean = false, detail: String = "", emphasized: Boolean = false) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(360.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = if (emphasized) Type.BodyStrong else Type.Body, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (detail.isNotBlank()) Text(detail, style = Type.Caption, color = Palette.TextTertiary, maxLines = 1)
        }
        cells.forEach {
            Text(it, style = if (header) Type.Overline else if (emphasized) Type.BodyStrong else Type.Digits, color = if (header) Palette.TextSecondary else Palette.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
        }
    }
}

// endregion
