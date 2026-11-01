package com.alexdiab.stadiatv.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.alexdiab.stadiatv.AppContainer
import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.domain.Athlete
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.Team
import com.alexdiab.stadiatv.live.Channel

/**
 * The five places a viewer goes: tonight's picks, the sports hub (live, schedule, standings, news), channels, the
 * programme guide, and what they follow. Settings is reachable from every screen but is not a destination of its own.
 */
enum class Tab(val label: String, val icon: ImageVector, val secondary: Boolean = false) {
    Home("Home", Icons.Rounded.Home),
    Sports("Sports", Icons.Rounded.Sensors),
    LiveTv("Live TV", Icons.Rounded.LiveTv),
    Guide("Guide", Icons.Rounded.CalendarMonth),
    MyBanner("My Banner", Icons.Rounded.Star),
    Settings("Settings", Icons.Rounded.Settings, secondary = true),
    ;

    companion object {
        val primary: List<Tab> = entries.filter { !it.secondary }
    }
}

/** Screens pushed over the tab content. Each keeps its own state; Back pops one. */
sealed interface Route {
    data class MatchDetail(val match: Match) : Route
    data class ArticleReader(val article: Article) : Route
    data class Roster(val team: Team) : Route
    data class AthleteProfile(val league: League, val athlete: Athlete) : Route
    data class Player(val channel: Channel, val match: Match? = null) : Route
    data class Multiview(val channels: List<Channel>) : Route
    data object Fantasy : Route
    data object FantasyConnect : Route
    data object TeamPicker : Route
    data object Guide : Route
    data object AddPlaylist : Route
    data class EditPlaylist(val playlistId: String) : Route
}

@Stable
class Navigator {
    var tab by mutableStateOf(Tab.Home)
        private set
    val stack = mutableStateListOf<Route>()

    val top: Route? get() = stack.lastOrNull()

    fun select(tab: Tab) {
        this.tab = tab
    }

    fun push(route: Route) {
        stack.add(route)
    }

    /** Swaps the top screen, for hand-offs such as the player becoming multiview without stacking decoders. */
    fun replaceTop(route: Route) {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        stack.add(route)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot() = stack.clear()
}

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalNavigator = compositionLocalOf<Navigator> { error("Navigator not provided") }
