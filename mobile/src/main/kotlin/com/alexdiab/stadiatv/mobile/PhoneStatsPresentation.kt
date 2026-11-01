package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.SportGroup

/** StatsView's sport-aware architecture, distinct from the scores/calendar hub. */
enum class PhoneStatsSection(val label: String) {
    Overview("Overview"), Standings("Standings"), Leaders("Leaders"), Teams("Teams"), Injuries("Injuries"), Field("Field");
    val requiresPremium: Boolean get() = this in setOf(Standings, Leaders, Injuries)
    fun detail(league: League): String = when (this) {
        Overview -> league.shortName
        Standings -> if (league.group == SportGroup.Soccer) "Table and form" else "Division tables"
        Leaders -> if (league.group == SportGroup.Racing) "Drivers and results" else "Top performers"
        Teams -> "Rosters and profiles"
        Injuries -> "Availability report"
        Field -> if (league.group == SportGroup.Golf) "Tournament field" else "Race entrants"
    }
    companion object {
        fun available(league: League) = when (league.group) {
            SportGroup.Racing, SportGroup.Golf -> listOf(Overview, Field, Leaders)
            SportGroup.Tennis -> listOf(Overview, Standings, Leaders)
            SportGroup.Soccer -> listOf(Overview, Standings, Leaders, Teams)
            else -> listOf(Overview, Standings, Leaders, Teams, Injuries)
        }
        fun selectedLeague(path: String?, followed: List<League>) = (followed.ifEmpty { League.ALL }).let { leagues -> leagues.firstOrNull { it.path == path } ?: leagues.first() }
        /** An explicit in-app/deep-link destination may be outside follows; don't silently open another sport. */
        fun leaguesForVisit(followed: List<League>, initial: String?): List<League> =
            (followed.ifEmpty { League.ALL } + listOfNotNull(League.find(initial))).distinctBy { it.path }
    }
}
