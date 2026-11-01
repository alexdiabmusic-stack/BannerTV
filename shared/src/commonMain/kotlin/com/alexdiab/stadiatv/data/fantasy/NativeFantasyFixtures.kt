package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.data.MatchFeedMerger
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.foundation.SportsTime

data class NativeFantasyFixture(val match: Match, val home: Boolean) {
    val opponent: TeamSide get() = if (home) match.away else match.home
}

/** iOS BannerFantasyStore/FantasyEventLinker context, with league-scoped aliases and no player-ID joins. */
object NativeFantasyFixtures {
    fun leaguePath(sport: String): String? = when (sport) {
        "nfl" -> "football/nfl"; "nhl" -> "hockey/nhl"; "nba" -> "basketball/nba"; "mlb" -> "baseball/mlb"; else -> null
    }
    private fun home(player: NativeFantasyPlayer, path: String, match: Match): Boolean? {
        val team = TeamIdentity.abbreviation(path, player.team)
        if (team in setOf("", "TBD", "TBA", "UNKNOWN", "NONE")) return null
        val h = TeamIdentity.sameAbbreviation(path, team, match.home.abbreviation)
        val a = TeamIdentity.sameAbbreviation(path, team, match.away.abbreviation)
        return if (h == a) null else h // Missing/ambiguous ownership never becomes a guessed home side.
    }
    private fun validPlayer(player: NativeFantasyPlayer, sport: String): Boolean {
        return NativeFantasyPoolPolicy.athlete(player, sport) != null
    }
    fun games(player: NativeFantasyPlayer, sport: String, matches: List<Match>, now: Long, days: Int = 8): List<NativeFantasyFixture> {
        val path = leaguePath(sport) ?: return emptyList()
        if (days !in 1..14 || !validPlayer(player, sport)) return emptyList()
        val start = SportsTime.startOfDay(now); val end = SportsTime.addDays(start, days)
        val scoped = matches.filter { it.league.path == path && (it.startMillis in start until end ||
            it.state == GameState.LIVE && it.startMillis in (start - 12 * 3_600_000) until start) }
        return MatchFeedMerger.merge(scoped, now).mapNotNull { game -> home(player, path, game)?.let { NativeFantasyFixture(game, it) } }
            .sortedWith(compareBy<NativeFantasyFixture> { when (it.match.state) { GameState.LIVE -> 0; GameState.PRE -> 1; GameState.FINAL -> 2 } }
                .thenBy { if (it.match.state == GameState.FINAL) -it.match.startMillis else it.match.startMillis }.thenBy { it.match.key })
    }
    /** A first completed doubleheader game still locks today's lineup, even when the next fixture is upcoming. */
    fun lockedPlayers(players: List<NativeFantasyPlayer>, sport: String, matches: List<Match>, scoringDay: Long): Set<String> {
        val path = leaguePath(sport) ?: return emptySet()
        val start = SportsTime.startOfDay(scoringDay); val end = SportsTime.addDays(start, 1)
        val started = matches.filter { it.league.path == path && it.state != GameState.PRE && it.startMillis in start until end }
        return players.filter { validPlayer(it, sport) && started.any { match -> home(it, path, match) != null } }.mapTo(LinkedHashSet()) { it.id }
    }
}
