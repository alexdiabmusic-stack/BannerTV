package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.foundation.SportsTime

/** Full immutable roster evidence, not a count/hash revision which can alias a different roster. */
data class NativeFantasyRosterStamp(val leagueId: String, val leagueName: String, val sport: String, val scoringDay: Long,
    val entries: List<NativeFantasyEntry>, val assignments: Map<String, String>)
data class NativeFantasyContextPlayer(val entry: NativeFantasyEntry, val slot: String?) {
    val lineupLabel: String get() = when (slot) { null -> "Unassigned"; "BN" -> "Bench"; "IR", "IL" -> "Reserve · $slot"; else -> "Fantasy lineup · $slot" }
}
data class NativeFantasyEventContext(val match: Match, val players: List<NativeFantasyContextPlayer>)
data class NativeFantasyEventIndex(val owner: NativeFantasyRosterStamp? = null, val contexts: List<NativeFantasyEventContext> = emptyList()) {
    /** Corrected opponents/kickoff invalidate old associations immediately; score/clock updates do not. */
    fun contextFor(match: Match): NativeFantasyEventContext? = contexts.firstOrNull { stored ->
        val old = stored.match
        old.key == match.key && old.league.path == match.league.path && old.startMillis == match.startMillis &&
            TeamIdentity.sameAbbreviation(match.league.path, old.home.abbreviation, match.home.abbreviation) &&
            TeamIdentity.sameAbbreviation(match.league.path, old.away.abbreviation, match.away.abbreviation)
    }?.copy(match = match)
}

/** iOS's selected-league event index. Team fixtures are not proof of participation or individual fantasy points. */
object NativeFantasyContexts {
    fun selected(library: NativeFantasyLibrary): NativeFantasyLeague? = library.leagues.firstOrNull { it.id == library.selected } ?: library.leagues.firstOrNull()
    fun stamp(league: NativeFantasyLeague?, now: Long): NativeFantasyRosterStamp? {
        if (league == null || runCatching { NativeFantasyPoolPolicy.key(league) }.isFailure) return null
        val entries = league.teams.singleOrNull { !it.cpu }?.roster ?: return null
        if (entries.size > 60 || entries.map { it.id }.distinct().size != entries.size || entries.map { it.player.id }.distinct().size != entries.size) return null
        val day = SportsTime.format("yyyy-MM-dd", now)
        val assignments = league.lineups[day].orEmpty().filter { (entry, slot) -> entries.any { it.id == entry } && (league.slots[slot] ?: 0) > 0 }
        return NativeFantasyRosterStamp(league.id, league.name, league.sport, SportsTime.startOfDay(now), entries, assignments)
    }
    fun build(league: NativeFantasyLeague?, matches: List<Match>, now: Long): NativeFantasyEventIndex {
        val owner = stamp(league, now) ?: return NativeFantasyEventIndex()
        val games = LinkedHashMap<String, NativeFantasyEventContext>()
        for (entry in owner.entries) for (fixture in NativeFantasyFixtures.games(entry.player, owner.sport, matches, now)) {
            val slot = owner.assignments[entry.id].takeIf { SportsTime.startOfDay(fixture.match.startMillis) == owner.scoringDay }
            val player = NativeFantasyContextPlayer(entry, slot)
            val previous = games[fixture.match.key]
            games[fixture.match.key] = NativeFantasyEventContext(fixture.match, previous?.players.orEmpty() + player)
        }
        val contexts = games.values.sortedWith(compareBy<NativeFantasyEventContext> { when (it.match.state) { GameState.LIVE -> 0; GameState.PRE -> 1; GameState.FINAL -> 2 } }
            .thenBy { if (it.match.state == GameState.FINAL) -it.match.startMillis else it.match.startMillis }.thenBy { it.match.key })
        return NativeFantasyEventIndex(owner, contexts)
    }
}
