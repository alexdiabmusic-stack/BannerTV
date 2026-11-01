package com.alexdiab.stadiatv.ui.sports

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.alexdiab.stadiatv.ui.components.ChipRow
import com.alexdiab.stadiatv.ui.components.FilterChip
import com.alexdiab.stadiatv.ui.live.LiveScreen
import com.alexdiab.stadiatv.ui.nav.LocalTopInset
import com.alexdiab.stadiatv.ui.nav.TopBarInset
import com.alexdiab.stadiatv.ui.news.NewsScreen
import com.alexdiab.stadiatv.ui.schedule.ScheduleScreen
import com.alexdiab.stadiatv.ui.stats.StatsScreen

/** What the Sports hub is made of: everything about the games themselves in one place. */
enum class SportsSection(val label: String, val icon: ImageVector) {
    Live("Live", Icons.Rounded.Sensors),
    Schedule("Schedule", Icons.Rounded.CalendarMonth),
    Standings("Standings & Stats", Icons.Rounded.BarChart),
    News("News", Icons.Rounded.Newspaper),
}

/**
 * Live scores, the schedule, standings and stats, and sports news were four tabs about one subject. Here they are one
 * destination with a row of sections, so the top bar stays short and a viewer never has to guess which tab holds what.
 */
@Composable
fun SportsHubScreen() {
    var sectionName by rememberSaveable { mutableStateOf(SportsSection.Live.name) }
    val section = SportsSection.entries.firstOrNull { it.name == sectionName } ?: SportsSection.Live
    Column(Modifier.fillMaxSize().padding(top = TopBarInset - 20.dp)) {
        ChipRow {
            SportsSection.entries.forEach { item ->
                item(item.name) { FilterChip(item.label, section == item, { sectionName = item.name }, icon = item.icon) }
            }
        }
        Box(Modifier.weight(1f)) {
            // The sections were written to sit directly under the top bar; here the hub's own row is already above them.
            CompositionLocalProvider(LocalTopInset provides 28.dp) {
                AnimatedContent(targetState = section, transitionSpec = { sectionTransition() }, label = "sportsSection") { current ->
                    when (current) {
                        SportsSection.Live -> LiveScreen()
                        SportsSection.Schedule -> ScheduleScreen()
                        SportsSection.Standings -> StatsScreen()
                        SportsSection.News -> NewsScreen()
                    }
                }
            }
        }
    }
}

private fun sectionTransition(): ContentTransform = fadeIn(tween(160, delayMillis = 30)) togetherWith fadeOut(tween(100))
