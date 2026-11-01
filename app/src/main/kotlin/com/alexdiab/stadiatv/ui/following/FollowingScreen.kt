package com.alexdiab.stadiatv.ui.following

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.ui.components.FocusPill
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.EmptyState
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.LiveBadge
import com.alexdiab.stadiatv.ui.components.LocalSpoilerFree
import com.alexdiab.stadiatv.ui.components.MatchCard
import com.alexdiab.stadiatv.ui.components.PagePivot
import com.alexdiab.stadiatv.ui.components.Shelf
import com.alexdiab.stadiatv.ui.components.SkeletonShelf
import com.alexdiab.stadiatv.ui.components.TeamLogo
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import com.alexdiab.stadiatv.util.Dates

@Composable
fun FollowingScreen() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val feed by container.matchFeed.sections.collectAsStateWithLifecycle()
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("all") }
    val open: (Match) -> Unit = { navigator.push(Route.MatchDetail(it)) }

    val followedLeagues = settings.explicitlyFollowedLeagues
    val hasContent = settings.favoriteTeams.isNotEmpty() || followedLeagues.isNotEmpty()
    if (!hasContent) {
        Box(Modifier.fillMaxSize().padding(top = LocalTopInset.current), contentAlignment = Alignment.Center) {
            EmptyState(
                Icons.Rounded.StarBorder,
                "Nothing followed yet",
                subtitle = "Pick your sports, leagues and teams to see their games here.",
                action = {
                    FocusPill(onClick = { navigator.push(Route.TeamPicker) }, selected = true, height = 60.dp) {
                        Text("Choose favourites", style = Type.Headline, color = Color.White, modifier = Modifier.padding(horizontal = 36.dp).align(Alignment.Center))
                    }
                },
            )
        }
        return
    }

    val followedPaths = remember(followedLeagues) { followedLeagues.map { it.path }.toSet() }
    val followedMatches = remember(feed.all, followedPaths, settings.favoriteTeams) {
        feed.all.filter { it.league.path in followedPaths || settings.involvesFavorite(it) }
    }
    val selectedMatches = remember(followedMatches, selected, settings.favoriteTeams) {
        when {
            selected == "all" -> followedMatches
            selected.startsWith("team-") -> {
                val team = settings.favoriteTeams.firstOrNull { "team-${it.key}" == selected }
                if (team == null) followedMatches else followedMatches.filter { team.matches(it.league, it.home) || team.matches(it.league, it.away) }
            }
            selected.startsWith("league-") -> followedMatches.filter { "league-${it.league.path}" == selected }
            else -> followedMatches
        }
    }
    val now = System.currentTimeMillis()
    val live = remember(selectedMatches) { selectedMatches.filter { it.state == GameState.LIVE } }
    val upcoming = remember(selectedMatches) {
        selectedMatches.filter { it.state == GameState.PRE && it.startMillis >= now && !it.isTbd() }.sortedBy { it.startMillis }
    }
    val recent = remember(selectedMatches) { selectedMatches.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis } }
    val today = remember(selectedMatches) { selectedMatches.count { Dates.isToday(it.startMillis) && it.state != GameState.FINAL } }
    val thisWeek = remember(selectedMatches) {
        val end = Dates.addDays(now, 7)
        selectedMatches.count { it.state == GameState.PRE && it.startMillis in now..end }
    }
    val nextUp = upcoming.firstOrNull()
    val remaining = if (live.isEmpty() && nextUp != null) upcoming.drop(1) else upcoming

    PagePivot(0.3f) {
        LazyColumn(
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = Spacing.ScreenBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "chips") {
                EntityChips(
                    settings.favoriteTeams, followedLeagues.filter { l -> settings.favoriteTeams.none { it.leaguePath == l.path } }.take(6), selected,
                    onEdit = { navigator.push(Route.TeamPicker) },
                ) { selected = it }
            }
            item(key = "summary") {
                SummaryRow(live.size, today, thisWeek, Modifier.padding(horizontal = Spacing.ScreenH))
            }
            if (feed.isLoading && followedMatches.isEmpty()) {
                item(key = "skeleton") { SkeletonShelf("Coming Up") }
            } else {
                if (live.isNotEmpty()) item(key = "live") {
                    Column(Modifier.padding(horizontal = Spacing.ScreenH), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Sensors, null, tint = Palette.Live, modifier = Modifier.size(28.dp))
                            Text("Live Now", style = Type.Title3, color = Palette.Live)
                        }
                        LiveHero(live.first()) { open(live.first()) }
                    }
                } else if (nextUp != null) item(key = "next") {
                    UpNextHero(nextUp, Modifier.padding(horizontal = Spacing.ScreenH)) { open(nextUp) }
                }
                if (remaining.isNotEmpty()) item(key = "coming") {
                    Shelf("Coming Up", icon = Icons.Rounded.CalendarMonth, tint = Palette.Upcoming) {
                        items(remaining.take(12), key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                    }
                }
                if (recent.isNotEmpty()) item(key = "recent") {
                    Shelf("Recent Results", icon = Icons.Rounded.Flag, tint = Palette.TextSecondary) {
                        items(recent.take(10), key = { it.key }) { MatchCard(it, onClick = { open(it) }) }
                    }
                }
                if (selectedMatches.isEmpty()) item(key = "none") {
                    EmptyState(
                        Icons.Rounded.EventBusy,
                        "No matches found",
                        subtitle = "No upcoming or recent matches for this selection.",
                    )
                }
            }
        }
    }
}

private fun Match.isTbd(): Boolean {
    fun tbd(name: String) = name.isBlank() || name.equals("tbd", ignoreCase = true)
    return tbd(home.displayName) && tbd(away.displayName)
}

@Composable
private fun EntityChips(
    teams: List<FavoriteTeam>,
    leagues: List<League>,
    selected: String,
    onEdit: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ChipRow {
        item(key = "all") { FilterChip("All", selected == "all", { onSelect("all") }) }
        items(teams, key = { "team-${it.key}" }) { team ->
            val label = team.abbreviation.ifBlank { team.name.take(3).uppercase() }
            FilterChip(label, selected == "team-${team.key}", { onSelect("team-${team.key}") }, logoUrl = team.logoUrl)
        }
        items(leagues, key = { "league-${it.path}" }) { league ->
            FilterChip(league.shortName, selected == "league-${league.path}", { onSelect("league-${league.path}") })
        }
        item(key = "edit") { FilterChip("Edit favourites", false, onEdit, icon = Icons.Rounded.Edit) }
    }
}

@Composable
private fun SummaryRow(live: Int, today: Int, week: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(Shapes.Lg))
            .border(1.dp, Palette.Hairline, RoundedCornerShape(Shapes.Lg))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryItem(if (live == 0) Icons.Rounded.Circle else Icons.Rounded.Sensors, live, "Live", if (live == 0) Palette.TextTertiary else Palette.Live, live > 0)
        Divider()
        SummaryItem(Icons.Rounded.CalendarMonth, today, "Today", Palette.Upcoming, false)
        Divider()
        SummaryItem(Icons.Rounded.NotificationsNone, week, "This Week", Palette.Starting, false)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(40.dp).background(Palette.Hairline))
}

@Composable
private fun SummaryItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: Int, label: String, tint: Color, prominent: Boolean) {
    Row(
        Modifier.padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = if (prominent) tint else Palette.TextTertiary, modifier = Modifier.size(26.dp))
        Column {
            Text("$value", style = Type.ScoreMd, color = if (prominent) tint else Palette.TextPrimary)
            Text(label, style = Type.Caption, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun LiveHero(match: Match, onClick: () -> Unit) {
    val spoilerFree = LocalSpoilerFree.current
    val racing = match.league.group == SportGroup.Racing
    FocusCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(300.dp),
        shape = RoundedCornerShape(Shapes.Xl),
        focusedScale = 1.02f,
        // Live is shown by the badge alone; the card stays as quiet as every other.
        background = Brush.linearGradient(listOf(Palette.SurfaceElevated, Palette.Surface, Palette.Surface)),
        restingBorder = Palette.Hairline,
    ) {
        Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveBadge()
                Text(match.league.shortName.uppercase(), style = Type.Overline, color = Palette.TextSecondary)
            }
            if (racing) {
                Text(match.name, style = Type.Title2, color = Palette.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(match.statusDetail, style = Type.Headline, color = Palette.Live)
            } else {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HeroSide(match.away.logoUrl, match.away.shortName, Modifier.weight(1f))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (spoilerFree) "• – •" else "${match.away.score ?: "-"}  –  ${match.home.score ?: "-"}",
                            style = Type.ScoreXl,
                            color = Palette.TextPrimary,
                        )
                        Text(match.statusDetail, style = Type.BodyStrong, color = Palette.Live, maxLines = 1)
                    }
                    HeroSide(match.home.logoUrl, match.home.shortName, Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open Match Centre", style = Type.BodyStrong, color = Palette.Live.copy(alpha = 0.85f))
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.Live.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun HeroSide(logo: String?, name: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TeamLogo(logo, 96.dp)
        Text(name, style = Type.Headline, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UpNextHero(match: Match, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(300.dp),
        shape = RoundedCornerShape(Shapes.Xl),
        focusedScale = 1.02f,
        background = Brush.linearGradient(listOf(Palette.Surface, Palette.SurfaceElevated)),
        restingBorder = Palette.Accent.copy(alpha = 0.28f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            UpNextTeam(match.away.logoUrl, match.away.displayName, match.away.record, Modifier.weight(1f))
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("UP NEXT", style = Type.Overline, color = Palette.AccessibleAccent)
                Text("VS", style = Type.Title1, color = Color.White.copy(alpha = 0.4f))
                Text(match.league.shortName.uppercase(), style = Type.Overline, color = Palette.TextSecondary)
                Text(Dates.dateTime(match.startMillis), style = Type.BodyStrong, color = Palette.TextPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.background(Palette.ActionFill, RoundedCornerShape(50)).padding(horizontal = 26.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Match Centre", style = Type.Headline, color = Color.White)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            UpNextTeam(match.home.logoUrl, match.home.displayName, match.home.record, Modifier.weight(1f))
        }
    }
}

@Composable
private fun UpNextTeam(logo: String?, name: String, record: String?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TeamLogo(logo, 104.dp)
        Text(name, style = Type.Title3, color = Palette.TextPrimary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!record.isNullOrBlank()) Text(record, style = Type.Callout, color = Palette.TextSecondary)
    }
}
