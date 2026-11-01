package com.alexdiab.stadiatv.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.LeaderBoard
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.domain.StandingGroup
import com.alexdiab.stadiatv.domain.StandingRow
import com.alexdiab.stadiatv.domain.Team
import com.alexdiab.stadiatv.ui.common.AsyncContent
import com.alexdiab.stadiatv.ui.common.rememberAsync
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.ChipStyle
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.Headshot
import com.alexdiab.stadiatv.ui.components.TeamLogo
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

private val SectionPadding = PaddingValues(start = Spacing.ScreenH - 32.dp, end = Spacing.ScreenH, top = 16.dp, bottom = 96.dp)

// region Standings

private val StandingColumns: Map<SportGroup, List<Pair<String, String>>> = mapOf(
    SportGroup.Football to listOf("wins" to "W", "losses" to "L", "ties" to "T", "winpercent" to "PCT", "pointsfor" to "PF", "pointsagainst" to "PA", "streak" to "STRK"),
    SportGroup.Basketball to listOf("wins" to "W", "losses" to "L", "winpercent" to "PCT", "gamesbehind" to "GB", "streak" to "STRK"),
    SportGroup.Baseball to listOf("wins" to "W", "losses" to "L", "winpercent" to "PCT", "gamesbehind" to "GB", "streak" to "STRK"),
    SportGroup.Hockey to listOf("gamesplayed" to "GP", "wins" to "W", "losses" to "L", "otlosses" to "OTL", "points" to "PTS", "streak" to "STRK"),
    SportGroup.Soccer to listOf("gamesplayed" to "GP", "wins" to "W", "ties" to "D", "losses" to "L", "pointdifferential" to "GD", "points" to "PTS"),
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun StandingsSection(league: League) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(league.path) { container.sports.standings(league) }
    AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "No standings yet", emptySubtitle = "Standings appear once the season is under way.") { groups ->
        LazyColumn(
            contentPadding = SectionPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            groups.forEach { group ->
                val columns = standingColumns(league, group)
                item(key = "h-${group.name}") { StandingHeader(group.name, columns) }
                items(group.rows.size, key = { "${group.name}-${group.rows[it].teamId}" }) { index ->
                    val row = group.rows[index]
                    StandingRowItem(index + 1, row, columns) {
                        navigator.push(
                            Route.Roster(Team(row.teamId, row.teamName, row.teamName, row.abbreviation, row.logoUrl, league)),
                        )
                    }
                }
            }
        }
    }
}

private fun standingColumns(league: League, group: StandingGroup): List<Pair<String, String>> {
    val preferred = StandingColumns[league.group].orEmpty()
    val present = preferred.filter { (key, _) -> group.rows.any { !it.values[key].isNullOrBlank() } }
    if (present.isNotEmpty()) return present
    val firstKeys = group.rows.firstOrNull()?.values?.keys.orEmpty().take(5)
    return firstKeys.map { it to it.take(4).uppercase() }
}

@Composable
private fun StandingHeader(name: String, columns: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 24.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        columns.forEach { (_, label) ->
            Text(label, style = Type.Overline, color = Palette.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.width(StatColumnWidth))
        }
    }
}

private val StatColumnWidth = 96.dp

@Composable
private fun StandingRowItem(rank: Int, row: StandingRow, columns: List<Pair<String, String>>, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(Shapes.Md),
        focusedScale = 1.012f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", style = Type.Digits, color = if (rank == 1) Palette.Gold else Palette.TextSecondary, modifier = Modifier.width(48.dp))
            TeamLogo(row.logoUrl, 40.dp)
            Text(
                row.teamName,
                style = Type.BodyStrong,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
            )
            if (columns.isEmpty()) {
                Text(row.record, style = Type.Digits, color = Palette.TextPrimary, textAlign = TextAlign.End, modifier = Modifier.width(StatColumnWidth))
            }
            columns.forEachIndexed { index, (key, _) ->
                Text(
                    row.values[key] ?: "–",
                    style = Type.Digits,
                    color = if (index == columns.lastIndex || key == "points") Palette.TextPrimary else Palette.TextSecondary,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(StatColumnWidth),
                )
            }
        }
    }
}

// endregion

// region Leaders

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LeadersSection(league: League) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(league.path) { container.sports.leaders(league) }
    AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "No leaders yet", emptySubtitle = "Leaderboards appear once games have been played.") { boards ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(560.dp),
            contentPadding = SectionPadding,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            items(boards, key = { it.title }) { board -> LeaderBoardCard(board) { row -> navigator.push(Route.AthleteProfile(league, row.athlete)) } }
        }
    }
}

@Composable
private fun LeaderBoardCard(board: LeaderBoard, onOpen: (com.alexdiab.stadiatv.domain.LeaderRow) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Shapes.Xl))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(board.title, style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp), maxLines = 1)
        board.rows.take(5).forEach { row ->
            FocusCard(
                onClick = { onOpen(row) },
                modifier = Modifier.fillMaxWidth().height(70.dp),
                shape = RoundedCornerShape(Shapes.Md),
                focusedScale = 1.02f,
                restingBorder = Color.Transparent,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${row.rank}", style = Type.Digits, color = if (row.rank == 1) Palette.Gold else Palette.TextSecondary, modifier = Modifier.width(40.dp))
                    Headshot(row.athlete.headshotUrl, 48.dp)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(row.athlete.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.team.isNotBlank()) Text(row.team, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1)
                    }
                    Text(row.value, style = Type.ScoreMd, color = Palette.AccessibleAccent, maxLines = 1)
                }
            }
        }
    }
}

// endregion

// region Teams

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TeamsSection(league: League) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(league.path) { container.sports.teams(league) }
    AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "Teams not available for ${league.name}") { teams ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            contentPadding = SectionPadding,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            items(teams, key = { it.id }) { team ->
                FocusCard(
                    onClick = { navigator.push(Route.Roster(team)) },
                    modifier = Modifier.fillMaxWidth().height(190.dp),
                    shape = RoundedCornerShape(Shapes.Lg),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TeamLogo(team.logoUrl, 80.dp)
                        Text(
                            team.name,
                            style = Type.Callout,
                            color = Palette.TextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

// endregion

// region Injuries

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun InjuriesSection(league: League) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(league.path) { container.sports.injuries(league) }
    AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "No injuries reported") { injuries ->
        LazyColumn(
            contentPadding = SectionPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            items(injuries.size, key = { "${injuries[it].athlete.id}-$it" }) { index ->
                val injury = injuries[index]
                FocusCard(
                    onClick = { navigator.push(Route.AthleteProfile(league, injury.athlete)) },
                    modifier = Modifier.fillMaxWidth().height(86.dp),
                    shape = RoundedCornerShape(Shapes.Md),
                    focusedScale = 1.012f,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Headshot(injury.athlete.headshotUrl, 56.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                            Text(injury.athlete.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(injury.team, injury.athlete.position).filter { it.isNotBlank() }.joinToString(" · "),
                                style = Type.Caption,
                                color = Palette.TextSecondary,
                                maxLines = 1,
                            )
                        }
                        if (injury.detail.isNotBlank()) {
                            Text(
                                injury.detail,
                                style = Type.Caption,
                                color = Palette.TextTertiary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(520.dp).padding(end = 20.dp),
                            )
                        }
                        Chip(injury.status, style = if (injury.status.lowercase().contains("out")) ChipStyle.Live else ChipStyle.Warning)
                    }
                }
            }
        }
    }
}

// endregion

// region Field

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FieldSection(league: League) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(league.path) { container.sports.field(league) }
    AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "No field available", emptySubtitle = "The field appears when an event is scheduled or under way.") { racers ->
        LazyColumn(
            contentPadding = SectionPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            items(racers.size, key = { "${racers[it].athlete.id}-$it" }) { index ->
                val racer = racers[index]
                FocusCard(
                    onClick = { navigator.push(Route.AthleteProfile(league, racer.athlete)) },
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                    shape = RoundedCornerShape(Shapes.Md),
                    focusedScale = 1.012f,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(racer.rank, style = Type.Digits, color = if (racer.rank == "1") Palette.Gold else Palette.TextSecondary, modifier = Modifier.width(56.dp))
                        Headshot(racer.athlete.headshotUrl, 48.dp)
                        Column(Modifier.weight(1f).padding(start = 18.dp)) {
                            Text(racer.athlete.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(racer.manufacturer, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1)
                        }
                        if (racer.score.isNotBlank()) Text(racer.score, style = Type.ScoreMd, color = Palette.AccessibleAccent, maxLines = 1)
                    }
                }
            }
        }
    }
}

// endregion
