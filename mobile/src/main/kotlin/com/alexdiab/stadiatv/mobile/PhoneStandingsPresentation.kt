package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*

/** Use provider ranks where supplied; never infer tiebreakers from formatted percentages or team names. */
enum class PhoneStandingColumn(val header: String, val title: String, val keys: List<String>) {
    Wins("W", "Wins", listOf("wins")), Losses("L", "Losses", listOf("losses")),
    Ties("T", "Ties", listOf("ties", "draws")),
    Percentage("PCT", "Win percentage", listOf("winpercent", "winpctg", "winpct", "winningpercentage")),
    GamesBack("GB", "Games back", listOf("gamesbehind", "gamesback", "gb")),
    Streak("STK", "Streak", listOf("streak")), Points("PTS", "Points", listOf("points", "leaguepoints")),
    Played("GP", "Games played", listOf("gamesplayed", "games")),
    Differential("GD", "Goal difference", listOf("goaldifferential", "pointdifferential", "differential"));

    fun header(league: League) = if (this == Ties) when (league.group) { SportGroup.Hockey -> "OTL"; SportGroup.Soccer -> "D"; else -> header } else header
    fun title(league: League) = if (this == Ties) when (league.group) { SportGroup.Hockey -> "Overtime losses"; SportGroup.Soccer -> "Draws"; else -> title } else title
    fun value(row: StandingRow, league: League): String {
        val aliases = if (this == Ties && league.group == SportGroup.Hockey) listOf("otlosses", "overtimelosses", "otl") else keys
        if (this == Streak) row.values["streakcode"]?.takeIf(String::isNotBlank)?.let { code -> return code + row.values["streakcount"].orEmpty() }
        return aliases.firstNotNullOfOrNull { key -> row.values[key]?.takeIf(String::isNotBlank) } ?: "–"
    }
    companion object {
        fun rank(group: StandingGroup, row: StandingRow, index: Int): String {
            val key = when (group.source) { "NFL" -> "divisionrank"; "NBA", "WNBA", "EPL" -> "leaguerank"; else -> return "${index + 1}" }
            return row.values[key]?.toIntOrNull()?.takeIf { it > 0 }?.toString() ?: "–"
        }
        fun columns(league: League) = when (league.group) {
            SportGroup.Baseball, SportGroup.Basketball -> listOf(Wins, Losses, Percentage, GamesBack, Streak)
            SportGroup.Football -> listOf(Wins, Losses, Ties, Percentage, Streak)
            SportGroup.Hockey -> listOf(Played, Wins, Losses, Ties, Points, Streak)
            SportGroup.Soccer -> listOf(Played, Wins, Ties, Losses, Differential, Points)
            else -> listOf(Wins, Points)
        }
        /** Only show a switch when BOTH views exist; division-only providers must not show an empty conference tab. */
        fun hasGroupingChoice(groups: List<StandingGroup>) = groups.any { it.name.contains("division", true) } && groups.any { !it.name.contains("division", true) }
        fun displayed(groups: List<StandingGroup>, division: Boolean) = if (hasGroupingChoice(groups)) groups.filter { it.name.contains("division", true) == division } else groups
    }
}
