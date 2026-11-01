package com.alexdiab.stadiatv.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.components.FocusCard
import com.alexdiab.stadiatv.ui.components.SideRow
import com.alexdiab.stadiatv.ui.components.SidePanel
import com.alexdiab.stadiatv.ui.components.icon
import com.alexdiab.stadiatv.ui.nav.LocalContainer
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.theme.Palette
import com.alexdiab.stadiatv.ui.theme.Shapes
import com.alexdiab.stadiatv.ui.theme.Spacing
import com.alexdiab.stadiatv.ui.theme.Type
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.text.style.TextOverflow

internal enum class StatsSection(val label: String, val icon: ImageVector, val blurb: String) {
    Overview("Overview", Icons.Rounded.Dashboard, ""),
    Standings("Standings", Icons.Rounded.FormatListNumbered, "Tables & standings"),
    Leaders("Leaders", Icons.Rounded.BarChart, "Top performers"),
    Teams("Teams", Icons.Rounded.Groups, "Rosters & profiles"),
    Injuries("Injuries", Icons.Rounded.HealthAndSafety, "Injury report"),
    Field("Field", Icons.Rounded.Flag, "Race field");

    companion object {
        fun available(league: League): List<StatsSection> = when (league.group) {
            SportGroup.Racing, SportGroup.Golf -> listOf(Overview, Field, Leaders)
            else -> listOf(Overview, Standings, Leaders, Teams, Injuries)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun StatsScreen() {
    val container = LocalContainer.current
    val settings by container.preferences.settings.collectAsStateWithLifecycle()
    val leagues = settings.followedLeagues
    var leaguePath by rememberSaveable { mutableStateOf(leagues.firstOrNull()?.path) }
    var sectionName by rememberSaveable { mutableStateOf(StatsSection.Overview.name) }
    val league = leagues.firstOrNull { it.path == leaguePath } ?: leagues.first()
    val sections = remember(league) { StatsSection.available(league) }
    val section = sections.firstOrNull { it.name == sectionName } ?: StatsSection.Overview

    LaunchedEffect(leagues) {
        if (leagues.none { it.path == leaguePath }) leaguePath = leagues.first().path
    }

    Row(Modifier.fillMaxSize().padding(top = LocalTopInset.current - 12.dp, start = Spacing.ScreenH - 8.dp)) {
        SidePanel("LEAGUES", Modifier.width(320.dp).fillMaxHeight().padding(bottom = 40.dp)) {
            leagues.forEach { item ->
                SideRow(
                    title = item.name,
                    subtitle = item.shortName,
                    selected = item.path == league.path,
                    icon = item.group.icon,
                    onFocused = {
                        if (leaguePath != item.path) {
                            leaguePath = item.path
                            sectionName = StatsSection.Overview.name
                        }
                    },
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            ChipRow(startPadding = 24.dp) {
                items(sections, key = { it.name }) { item ->
                    FilterChip(item.label, item == section, { sectionName = item.name }, icon = item.icon)
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    StatsSection.Overview -> Overview(sections.filter { it != StatsSection.Overview }) { sectionName = it.name }
                    StatsSection.Standings -> StandingsSection(league)
                    StatsSection.Leaders -> LeadersSection(league)
                    StatsSection.Teams -> TeamsSection(league)
                    StatsSection.Injuries -> InjuriesSection(league)
                    StatsSection.Field -> FieldSection(league)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Overview(sections: List<StatsSection>, onOpen: (StatsSection) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(340.dp),
        contentPadding = PaddingValues(start = Spacing.ScreenH - 32.dp, end = Spacing.ScreenH, top = 24.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize().focusRestorer(),
    ) {
        items(sections, key = { it.name }) { section ->
            FocusCard(
                onClick = { onOpen(section) },
                modifier = Modifier.fillMaxWidth().height(116.dp),
                shape = RoundedCornerShape(Shapes.Lg),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(
                        Modifier.size(64.dp).background(Palette.SurfaceHigh, RoundedCornerShape(Shapes.Md)),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Icon(section.icon, null, tint = Palette.AccessibleAccent, modifier = Modifier.size(32.dp)) }
                    Column {
                        Text(section.label, style = Type.Title3, color = Palette.TextPrimary)
                        Text(section.blurb, style = Type.Callout, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
