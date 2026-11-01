package com.alexdiab.stadiatv.ui.fantasy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.data.fantasy.EspnCookies
import com.alexdiab.stadiatv.data.fantasy.FantasyConnection
import com.alexdiab.stadiatv.data.fantasy.FantasyException
import com.alexdiab.stadiatv.data.fantasy.FantasyMatchup
import com.alexdiab.stadiatv.data.fantasy.FantasyPlayer
import com.alexdiab.stadiatv.data.fantasy.FantasySnapshot
import com.alexdiab.stadiatv.data.fantasy.FantasySport
import com.alexdiab.stadiatv.data.fantasy.FantasyTeam
import com.alexdiab.stadiatv.data.fantasy.SlotKind
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.components.Chip
import com.alexdiab.stadiatv.ui.components.ChipStyle
import com.alexdiab.stadiatv.ui.components.FieldGroup
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.OverlayContentTop
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.components.SectionHeader
import com.alexdiab.stadiatv.ui.components.Spinner
import com.alexdiab.stadiatv.ui.components.TvTextField
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// region Connect

/**
 * Connect an ESPN Fantasy league the way the tvOS app does: sport, league id and team id, plus the
 * espn_s2 and SWID cookies only when the league is private. The league is read before anything is saved.
 */
@Composable
fun FantasyConnectScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var sport by remember { mutableStateOf(FantasySport.Football) }
    var leagueId by remember { mutableStateOf("") }
    var season by remember { mutableStateOf(FantasySport.Football.currentSeason().toString()) }
    var seasonEdited by remember { mutableStateOf(false) }
    var teamId by remember { mutableStateOf("") }
    var espnS2 by remember { mutableStateOf("") }
    var swid by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (busy) return
        val seasonYear = season.trim().toIntOrNull()
        val league = leagueId.trim()
        val cookiesGiven = espnS2.isNotBlank() || swid.isNotBlank()
        error = when {
            league.isEmpty() || !league.all(Char::isDigit) -> "Enter your league ID: the number after leagueId= in the ESPN Fantasy address."
            seasonYear == null -> "Enter the season as a year, like ${sport.currentSeason()}."
            teamId.isNotBlank() && teamId.trim().toIntOrNull() == null -> "Your team ID is a number."
            cookiesGiven && (espnS2.isBlank() || swid.isBlank()) -> "A private league needs both espn_s2 and SWID."
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                val connection = FantasyConnection(sport.code, seasonYear!!, league, teamId.trim().toIntOrNull())
                container.fantasy.connect(connection, if (cookiesGiven) EspnCookies(espnS2.trim(), swid.trim()) else null)
                navigator.pop()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Throwable) {
                error = (failure as? FantasyException)?.message ?: "Couldn't reach ESPN. Check your connection and try again."
                busy = false
            }
        }
    }

    OverlayScaffold(
        title = "Connect ESPN Fantasy",
        subtitle = "Fantasy",
        scrollFade = true,
        trailing = {
            FocusPill(onClick = { navigator.pop() }) {
                Text("Cancel", style = Type.BodyStrong, color = Palette.TextPrimary, modifier = Modifier.padding(horizontal = 36.dp).align(Alignment.Center))
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                FieldGroup(Modifier.widthIn(max = 1100.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        FantasySport.entries.forEach { option ->
                            FilterChip(
                                option.label, option == sport,
                                {
                                    sport = option
                                    if (!seasonEdited) season = option.currentSeason().toString()
                                },
                                modifier = if (option == FantasySport.Football) Modifier.initialFocus() else Modifier,
                            )
                        }
                    }
                    TvTextField(leagueId, { leagueId = it }, "League ID", placeholder = "The number after leagueId= in your league's web address", keyboardType = KeyboardType.Number)
                    TvTextField(season, { season = it; seasonEdited = true }, "Season", keyboardType = KeyboardType.Number)
                    TvTextField(teamId, { teamId = it }, "Your team ID (optional)", placeholder = "The number after teamId= on your team page", keyboardType = KeyboardType.Number)
                    Text("Private league", style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Public leagues need nothing more. For a private one, paste the espn_s2 and SWID cookie values from a signed-in ESPN page " +
                            "on a computer. They're stored encrypted on this device and only ever sent to ESPN. Never enter your ESPN password.",
                        style = Type.Callout, color = Palette.TextSecondary,
                    )
                    TvTextField(espnS2, { espnS2 = it }, "espn_s2", secret = true)
                    TvTextField(swid, { swid = it }, "SWID", placeholder = "{XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}", secret = true)

                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FocusPill(onClick = ::connect, selected = true, height = 60.dp) {
                            Row(Modifier.padding(horizontal = 36.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (busy) Spinner(size = 26.dp, color = Color.White)
                                Text(if (busy) "Checking…" else "Connect", style = Type.Headline, color = Color.White)
                            }
                        }
                        error?.let { message ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Live, modifier = Modifier.size(28.dp))
                                Text(message, style = Type.Body, color = Palette.Live, maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

// endregion

// region Dashboard

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FantasyScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val state by container.fantasy.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot

    // Fresh numbers on open, then every minute while the screen is up.
    LaunchedEffect(Unit) {
        while (true) {
            container.fantasy.refresh()
            delay(REFRESH_MILLIS)
        }
    }
    LaunchedEffect(state.isConnected) { if (!state.isConnected) navigator.pop() }

    OverlayScaffold(
        title = snapshot?.leagueName ?: state.connection?.leagueName ?: "Fantasy",
        scrollFade = true,
        subtitle = "ESPN Fantasy" + (state.connection?.sport?.let { " · ${it.label}" } ?: ""),
        trailing = {
            FocusPill(onClick = { container.fantasy.refresh() }, modifier = Modifier.initialFocus()) {
                Row(Modifier.padding(horizontal = 28.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.isLoading) Spinner(size = 24.dp) else Icon(Icons.Rounded.Refresh, null, tint = Palette.TextPrimary, modifier = Modifier.size(24.dp))
                    Text("Refresh", style = Type.BodyStrong, color = Palette.TextPrimary)
                }
            }
        },
    ) {
        if (snapshot == null) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (state.isLoading) Spinner() else Text(state.error ?: "Nothing to show yet.", style = Type.Headline, color = Palette.TextSecondary, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                if (state.isStale) item("stale") {
                    Text("Showing saved data. ${state.error.orEmpty()}", style = Type.Callout, color = Palette.TextSecondary)
                }
                snapshot.matchup?.let { item("matchup") { MatchupCard(it) } }
                snapshot.myTeam?.let { item("team") { TeamSummary(it, snapshot.standings.size) } }
                rosterSection("Starters", snapshot.roster.filter { it.kind == SlotKind.Starter })
                rosterSection("Bench", snapshot.roster.filter { it.kind == SlotKind.Bench })
                rosterSection("Injured reserve", snapshot.roster.filter { it.kind == SlotKind.Reserve })
                if (snapshot.myTeam == null) item("no-team") {
                    Text(
                        "Add your team ID in the connection to see your roster and matchup. It's the number after teamId= on your ESPN team page.",
                        style = Type.Callout, color = Palette.TextSecondary,
                    )
                }
                if (snapshot.standings.isNotEmpty()) {
                    item("h-standings") { SectionHeader("Standings", Modifier.padding(top = 18.dp)) }
                    items(snapshot.standings, key = { "team-${it.id}" }) { StandingRow(it, isMine = it.id == snapshot.myTeam?.id) }
                }
                item("updated") {
                    Text("Updated ${Dates.relative(snapshot.refreshedAtMillis)}", style = Type.Caption, color = Palette.TextTertiary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

private fun LazyListScope.rosterSection(title: String, players: List<FantasyPlayer>) {
    if (players.isEmpty()) return
    item("h-$title") { SectionHeader(title, Modifier.padding(top = 14.dp)) }
    items(players, key = { "$title-${it.id}" }) { PlayerRow(it) }
}

@Composable
internal fun MatchupCard(matchup: FantasyMatchup) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Shapes.Lg))
            .padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("WEEK ${matchup.week} MATCHUP", style = Type.Overline, color = Palette.AccessibleAccent)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ScoreColumn(matchup.me.name, matchup.me.points, highlighted = matchup.isWinning, modifier = Modifier.weight(1f))
            Text("vs", style = Type.Headline, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = 24.dp))
            val opponent = matchup.opponent
            if (opponent != null) ScoreColumn(opponent.name, opponent.points, highlighted = !matchup.isWinning && matchup.margin != 0.0, modifier = Modifier.weight(1f))
            else Text("Bye week", style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        }
        if (matchup.opponent != null) {
            val margin = formatPoints(kotlin.math.abs(matchup.margin))
            Text(
                when {
                    matchup.margin > 0 -> "You're ahead by $margin"
                    matchup.margin < 0 -> "You're behind by $margin"
                    else -> "All square"
                },
                style = Type.Callout, color = if (matchup.isWinning) Palette.Upcoming else Palette.TextSecondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ScoreColumn(name: String, points: Double, highlighted: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(name, style = Type.BodyStrong, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatPoints(points), style = Type.ScoreLg, color = if (highlighted) Palette.TextPrimary else Palette.TextSecondary)
    }
}

@Composable
private fun TeamSummary(team: FantasyTeam, leagueSize: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(team.name, style = Type.Title3, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Chip(team.record, style = ChipStyle.Accent)
        team.rank?.let { Chip("Rank $it of $leagueSize") }
        team.pointsFor?.let { Chip("${formatPoints(it)} PF") }
    }
}

@Composable
internal fun PlayerRow(player: FantasyPlayer, modifier: Modifier = Modifier) {
    FocusCard(onClick = {}, modifier = modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(14.dp), focusedScale = 1.01f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(player.slot, style = Type.BodyStrong, color = Palette.AccessibleAccent, modifier = Modifier.width(72.dp))
            Text(player.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            player.injuryStatus?.let { Chip(it.replace('_', ' ').lowercase(Locale.US).replaceFirstChar(Char::titlecase), style = ChipStyle.Warning) }
            Text(player.points?.let(::formatPoints) ?: "–", style = Type.Headline, color = Palette.TextPrimary)
        }
    }
}

@Composable
private fun StandingRow(team: FantasyTeam, isMine: Boolean) {
    FocusCard(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(14.dp),
        focusedScale = 1.01f,
        restingBorder = if (isMine) Palette.Accent.copy(alpha = 0.6f) else Palette.Hairline,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(team.rank?.toString() ?: "–", style = Type.BodyStrong, color = Palette.TextSecondary, modifier = Modifier.width(40.dp))
            Text(team.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(team.record, style = Type.Body, color = Palette.TextSecondary)
            team.pointsFor?.let { Text(formatPoints(it), style = Type.Body, color = Palette.TextPrimary, modifier = Modifier.width(110.dp), textAlign = TextAlign.End) }
        }
    }
}

// endregion

/** Fantasy points print with one decimal, the way ESPN shows them. */
internal fun formatPoints(points: Double): String = String.format(Locale.US, "%.1f", points)

private const val REFRESH_MILLIS = 60_000L
