package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates

/** MatchesViewModel's team-first scope, deliberately different from Home's discovery fallback. */
fun followingMatches(all: List<Match>, settings: UserSettings, entity: String = "All"): List<Match> {
    val teamLeagues = settings.favoriteTeams.map { it.leaguePath }.toSet()
    val competitionLeagues = settings.selectedLeagueIds - teamLeagues
    val team = settings.favoriteTeams.firstOrNull { it.key == entity }
    val league = settings.explicitlyFollowedLeagues.firstOrNull { it.path == entity }
    return all.filter { match ->
        (settings.involvesFavorite(match) || match.league.path in competitionLeagues) && when {
            team != null -> team.matches(match.league, match.home) || team.matches(match.league, match.away)
            league != null -> match.league == league
            else -> true // A removed selection safely returns to the remaining followed entities.
        }
    }.distinctBy { it.key }.sortedBy { it.startMillis }
}

data class FollowingOverview(val live: List<Match>, val today: Int, val thisWeek: Int, val upcoming: List<Match>)

fun followingOverview(matches: List<Match>, now: Long): FollowingOverview {
    val today = Dates.startOfDay(now) until Dates.addDays(Dates.startOfDay(now), 1)
    val weekEnd = Dates.addDays(now, 7)
    fun announced(match: Match): Boolean = match.league.group in setOf(SportGroup.Racing, SportGroup.Golf) ||
        listOf(match.away.displayName, match.home.displayName).any { it.isNotBlank() && !it.trim().equals("TBD", true) }
    return FollowingOverview(
        live = matches.filter { it.state == GameState.LIVE },
        today = matches.count { it.state != GameState.FINAL && it.startMillis in today },
        thisWeek = matches.count { it.state == GameState.PRE && it.startMillis in now..weekEnd },
        upcoming = matches.filter { it.state == GameState.PRE && it.startMillis >= now && announced(it) }.sortedBy { it.startMillis },
    )
}

data class FollowingStandingsTarget(val league: League, val team: FavoriteTeam?)

fun followingStandingsTarget(settings: UserSettings, entity: String): FollowingStandingsTarget? {
    val selectedTeam = settings.favoriteTeams.firstOrNull { it.key == entity }
    val selectedLeague = settings.explicitlyFollowedLeagues.firstOrNull { it.path == entity }
    val team = selectedTeam ?: if (selectedLeague == null) settings.favoriteTeams.firstOrNull() else null
    val league = selectedLeague ?: team?.league ?: return null
    return if (league.group in setOf(SportGroup.Tennis, SportGroup.Golf)) null else FollowingStandingsTarget(league, team)
}

data class FollowingStanding(val rank: Int, val row: StandingRow, val highlighted: Boolean)
data class FollowingStandingSnapshot(val name: String, val rows: List<FollowingStanding>)

/** Keep the group's real row positions, including when the five-row window starts below first place. */
fun followingStandings(groups: List<StandingGroup>, team: FavoriteTeam?): FollowingStandingSnapshot? {
    fun matches(row: StandingRow): Boolean = team != null && (
        team.teamId.isNotBlank() && row.teamId == team.teamId ||
            TeamIdentity.sameAbbreviation(team.leaguePath, row.abbreviation, team.abbreviation) ||
            team.name.isNotBlank() && row.teamName.equals(team.name, true))
    val group = groups.firstOrNull { it.rows.any(::matches) } ?: groups.firstOrNull { it.rows.isNotEmpty() } ?: return null
    val index = group.rows.indexOfFirst(::matches)
    val start = if (index < 0) 0 else (index - 2).coerceIn(0, (group.rows.size - 5).coerceAtLeast(0))
    return FollowingStandingSnapshot(group.name, group.rows.withIndex().drop(start).take(5).map { (i, row) -> FollowingStanding(i + 1, row, matches(row)) })
}

fun followingNewsLeagues(settings: UserSettings, entity: String): List<League> {
    settings.favoriteTeams.firstOrNull { it.key == entity }?.league?.let { return listOf(it) }
    settings.explicitlyFollowedLeagues.firstOrNull { it.path == entity }?.let { return listOf(it) }
    return (settings.favoriteTeams.mapNotNull { it.league } + settings.explicitlyFollowedLeagues).distinctBy { it.path }.take(3)
}

fun sportsDayMatches(matches: List<Match>): List<Match> = matches.distinctBy { it.key }.sortedWith(
    compareBy<Match> { when (it.state) { GameState.LIVE -> 0; GameState.PRE -> 1; GameState.FINAL -> 2 } }.thenBy { it.startMillis })

/** Winner badges, not just the score text, must respect final-score spoiler protection. */
fun sportsDayShowsWinner(match: Match, side: TeamSide, spoilerFree: Boolean): Boolean =
    match.state == GameState.FINAL && !spoilerFree && side.isWinner
