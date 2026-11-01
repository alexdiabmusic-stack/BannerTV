package com.alexdiab.stadiatv.ui.stats

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.Athlete
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Team
import com.alexdiab.stadiatv.ui.common.AsyncContent
import com.alexdiab.stadiatv.ui.common.initialFocus
import com.alexdiab.stadiatv.ui.common.rememberAsync
import com.alexdiab.stadiatv.ui.components.ArticleCard
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.Headshot
import com.alexdiab.stadiatv.ui.components.OverlayContentTop
import com.alexdiab.stadiatv.ui.components.OverlayScaffold
import com.alexdiab.stadiatv.ui.components.SectionHeader
import com.alexdiab.stadiatv.ui.components.TeamLogo
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalNavigator
import com.alexdiab.stadiatv.ui.nav.Route
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RosterScreen(team: Team) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(team.id) { container.sports.roster(team.league, team.id) }
    OverlayScaffold(
        title = team.name,
        subtitle = team.league.name,
        scrollFade = true,
        leading = { TeamLogo(team.logoUrl, 110.dp) },
    ) {
        AsyncContent(loader, isEmpty = { it.isEmpty() }, emptyTitle = "Roster not available") { groups ->
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                val firstId = groups.firstOrNull()?.athletes?.firstOrNull()?.id
                groups.forEach { group ->
                    item(key = "h-${group.title}") {
                        Text(group.title, style = Type.Title3, color = Palette.TextPrimary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                    }
                    items(group.athletes.size, key = { "${group.title}-${group.athletes[it].id}" }) { index ->
                        val athlete = group.athletes[index]
                        AthleteRow(
                            athlete,
                            onClick = { navigator.push(Route.AthleteProfile(team.league, athlete)) },
                            modifier = if (athlete.id == firstId) Modifier.initialFocus() else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AthleteRow(athlete: Athlete, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(82.dp),
        shape = RoundedCornerShape(Shapes.Md),
        focusedScale = 1.01f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                athlete.jersey.takeIf { it.isNotBlank() }?.let { "#$it" } ?: "",
                style = Type.Digits,
                color = Palette.TextSecondary,
                modifier = Modifier.width(72.dp),
            )
            Headshot(athlete.headshotUrl, 56.dp)
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text(athlete.name, style = Type.BodyStrong, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (athlete.details.isNotBlank()) Text(athlete.details, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (athlete.position.isNotBlank()) {
                Text(athlete.position, style = Type.Callout, color = Palette.AccessibleAccent, textAlign = TextAlign.End)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AthleteScreen(league: League, athlete: Athlete) {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val loader = rememberAsync(athlete.id) { container.sports.athlete(league, athlete) }
    OverlayScaffold(
        title = athlete.name,
        scrollFade = true,
        subtitle = listOf(league.shortName, athlete.position, athlete.jersey.takeIf { it.isNotBlank() }?.let { "#$it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
        leading = { Headshot(athlete.headshotUrl, 132.dp) },
    ) {
        AsyncContent(loader) { overview ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(210.dp),
                contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, top = OverlayContentTop, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().focusRestorer(),
            ) {
                if (athlete.details.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }, key = "details") {
                    Text(athlete.details, style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (overview.stats.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "stats-title") { SectionHeader("Season Stats", Modifier.padding(top = 8.dp)) }
                    items(overview.stats.entries.toList(), key = { it.key }) { (label, value) ->
                        FocusCard(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().height(112.dp).then(if (label == overview.stats.keys.first()) Modifier.initialFocus() else Modifier),
                            shape = RoundedCornerShape(Shapes.Lg),
                            focusedScale = 1.04f,
                        ) {
                            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
                                Text(value, style = Type.ScoreMd, color = Palette.TextPrimary, maxLines = 1)
                                Text(label, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (overview.news.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "news-title") {
                        SectionHeader("In the News", Modifier.padding(top = 24.dp), icon = Icons.Rounded.Newspaper)
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "news") {
                        LazyRow(
                            contentPadding = PaddingValues(vertical = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            modifier = Modifier.focusRestorer(),
                        ) {
                            items(overview.news, key = { it.id }) { article -> ArticleCard(article, 380.dp, onClick = { navigator.push(Route.ArticleReader(article)) }) }
                        }
                    }
                }
                if (overview.stats.isEmpty() && overview.news.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        Text("No season statistics are available yet.", style = Type.Headline, color = Palette.TextSecondary, modifier = Modifier.fillMaxWidth().padding(top = 32.dp))
                    }
                }
            }
        }
    }
}
