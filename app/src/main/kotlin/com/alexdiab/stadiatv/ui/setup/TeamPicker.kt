package com.alexdiab.stadiatv.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import com.alexdiab.stadiatv.data.Favourites
import com.alexdiab.stadiatv.data.FavoriteTeam
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.ui.common.AsyncContent
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.common.rememberAsync
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.components.TeamLogo
import com.alexdiab.stadiatv.ui.components.icon
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

/** Leagues whose followable unit is a team (golf and racing follow the event, not a club). */
val League.hasTeams: Boolean get() = group != SportGroup.Golf && group != SportGroup.Racing && group != SportGroup.Tennis

/** Star teams across leagues. One league at a time keeps the remote travel short even for 30+ team leagues. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TeamPickerContent(leagues: List<League>, modifier: Modifier = Modifier, focusFirstTeam: Boolean = false) {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val teamLeagues = remember(leagues) { leagues.filter { it.hasTeams } }
    if (teamLeagues.isEmpty()) {
        Text("None of the selected leagues have teams to follow.", style = Type.Headline, color = Palette.TextSecondary, modifier = modifier.padding(horizontal = Spacing.ScreenH, vertical = 24.dp))
        return
    }
    var selectedPath by remember { mutableStateOf(teamLeagues.first().path) }
    val league = teamLeagues.firstOrNull { it.path == selectedPath } ?: teamLeagues.first()
    val loader = rememberAsync(league.path) { container.sports.teams(league) }

    Column(modifier.fillMaxSize()) {
        ChipRow {
            items(teamLeagues, key = { it.path }) { item ->
                val count = settings.favoriteTeams.count { it.leaguePath == item.path }
                FilterChip(item.shortName, item.path == league.path, { selectedPath = item.path }, icon = item.group.icon, count = count.takeIf { it > 0 })
            }
        }
        AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "No teams found for ${league.name}") { teams ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(380.dp),
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 16.dp, bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                items(teams, key = { it.id }) { team ->
                    val favorite = settings.isFavorite(team)
                    FocusCard(
                        onClick = { container.preferences.toggleFavorite(team) },
                        modifier = Modifier.fillMaxWidth().height(84.dp).then(if (focusFirstTeam && team === teams.first()) Modifier.initialFocus() else Modifier),
                        shape = RoundedCornerShape(Shapes.Md),
                        focusedScale = 1.03f,
                        background = if (favorite) Brush.horizontalGradient(listOf(Palette.Accent.copy(alpha = 0.22f), Palette.Accent.copy(alpha = 0.08f))) else Brush.verticalGradient(listOf(Palette.Surface, Palette.Surface)),
                        restingBorder = if (favorite) Palette.Accent.copy(alpha = 0.55f) else Palette.Hairline,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TeamLogo(team.logoUrl, 48.dp)
                            Text(team.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (favorite) Palette.AccessibleAccent else Palette.TextSecondary, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Favourites: the one place to add or remove sports, leagues and teams, at any time. Home, My Banner and the featured
 * game are built from these, so a change here is reflected as soon as the screen is closed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TeamPickerScreen() {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    OverlayScaffold(title = "Favourites", subtitle = "Sports, leagues and teams") {
        Column(Modifier.fillMaxSize()) {
            Text(
                if (settings.followsExplicitly) Favourites.summary(settings)
                else "Nothing chosen yet, so every league is shown. Pick sports or leagues to make Home yours.",
                style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = Spacing.ScreenH),
            )
            val chosenSports = Favourites.sportsOf(settings.selectedLeagueIds)
            ChipRow {
                item("label-sports") { RowLabel("Sports") }
                items(League.groups, key = { it.name }) { group ->
                    FilterChip(
                        group.label, group in chosenSports, { container.preferences.toggleSport(group) }, icon = group.icon,
                        modifier = if (group == League.groups.first()) Modifier.initialFocus() else Modifier,
                    )
                }
            }
            ChipRow {
                item("label-leagues") { RowLabel("Leagues") }
                items(League.ALL, key = { it.path }) { league ->
                    FilterChip(league.shortName, league.path in settings.selectedLeagueIds, { container.preferences.toggleLeague(league) }, icon = league.group.icon)
                }
            }
            YourTeams()
            if (settings.explicitlyFollowedLeagues.isEmpty()) {
                Text("Choose a league above to pick teams from it.", style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = Spacing.ScreenH, vertical = 24.dp))
            } else {
                TeamPickerContent(leagues = settings.explicitlyFollowedLeagues, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowLabel(text: String) {
    // As tall as the chips beside it, so the label sits on their centre line.
    Box(Modifier.width(120.dp).height(52.dp), contentAlignment = Alignment.CenterStart) {
        Text(text.uppercase(), style = Type.Overline, color = Palette.TextTertiary)
    }
}

/**
 * Every team currently starred, so one can be removed without finding its league first (a team can outlive the league
 * it was starred in). A removed team keeps its chip until the screen closes, so the row doesn't shift under the cursor
 * and a slip can be undone by pressing the chip again.
 */
@Composable
private fun YourTeams() {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val roster = remember { mutableStateListOf<FavoriteTeam>().apply { addAll(settings.favoriteTeams) } }
    LaunchedEffect(settings.favoriteTeams) {
        settings.favoriteTeams.forEach { team -> if (roster.none { it.key == team.key }) roster += team }
    }
    if (roster.isEmpty()) {
        Text("YOUR TEAMS   None yet. Star teams below and their games lead Home.", style = Type.Callout, color = Palette.TextTertiary, modifier = Modifier.padding(horizontal = Spacing.ScreenH, vertical = 16.dp))
        return
    }
    ChipRow {
        item("label-teams") { RowLabel("Your teams") }
        items(roster, key = { it.key }) { team ->
            val starred = settings.favoriteTeams.any { it.key == team.key }
            FilterChip(
                team.abbreviation.ifBlank { team.name.ifBlank { team.teamId } }, starred,
                { if (starred) container.preferences.removeFavorite(team) else container.preferences.addFavorite(team) },
                logoUrl = team.logoUrl,
            )
        }
    }
}
