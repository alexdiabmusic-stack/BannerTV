package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.SportGroup

/**
 * What "favourite sports, leagues and teams" means in the data. Only leagues and teams are stored; a sport counts as
 * followed while any of its leagues is, so choosing a sport is just choosing all of its leagues at once.
 */
object Favourites {
    fun leaguesOf(group: SportGroup): Set<String> = League.inGroup(group).map { it.path }.toSet()

    fun sportsOf(leaguePaths: Set<String>): Set<SportGroup> = leaguePaths.mapNotNull { League.find(it)?.group }.toSet()

    /** Turns a sport on (selecting every league in it) or off (clearing them), leaving other sports as they were. */
    fun toggleSport(selected: Set<String>, group: SportGroup): Set<String> {
        val own = leaguesOf(group)
        return if (selected.any { it in own }) selected - own else selected + own
    }

    /** Teams whose league is no longer followed, dropped; used when setup narrows the leagues after teams were starred. */
    fun pruneTeams(teams: List<FavoriteTeam>, leaguePaths: Set<String>): List<FavoriteTeam> = teams.filter { it.leaguePath in leaguePaths }

    /** One line for Settings: "2 sports · 3 leagues · 4 teams", or an invitation when nothing is chosen. */
    fun summary(settings: UserSettings): String {
        val sports = sportsOf(settings.selectedLeagueIds).size
        val leagues = settings.explicitlyFollowedLeagues.size
        val teams = settings.favoriteTeams.size
        if (sports == 0 && leagues == 0 && teams == 0) return "Choose your sports, leagues and teams"
        fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
        return listOf(count(sports, "sport", "sports"), count(leagues, "league", "leagues"), count(teams, "team", "teams")).joinToString(" · ")
    }
}
