package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class NativeFantasyPoolSource { Native, ESPN }
data class NativeFantasyPoolKey(val sport: String, val source: NativeFantasyPoolSource)
data class NativeFantasyPool(val players: List<NativeFantasyPlayer>, val unavailableTeams: List<String> = emptyList(), val ambiguousPlayers: Int = 0,
    val loadedAt: Long = 0, val retainedPrevious: Boolean = false) {
    val complete: Boolean get() = unavailableTeams.isEmpty() && ambiguousPlayers == 0
}

/** No renumbering saved drafts: an existing namespace owns its pool; empty new leagues use iOS's provider choice. */
object NativeFantasyPoolPolicy {
    private val nativeSports = setOf("nhl", "mlb", "nba")
    private val number = Regex("[1-9][0-9]{0,11}")
    fun key(league: NativeFantasyLeague): NativeFantasyPoolKey {
        require(league.sport in NativeFantasyEngine.sports) { "Unsupported fantasy sport." }
        val ids = (league.teams.flatMap { it.roster }.map { it.player.id } + league.picks.mapNotNull { it.player }).distinct()
        val kinds = ids.map { id -> when {
            id.startsWith("espn-${league.sport}-") && number.matches(id.removePrefix("espn-${league.sport}-")) -> NativeFantasyPoolSource.ESPN
            league.sport in nativeSports && id.startsWith("${league.sport}:") && number.matches(id.substringAfter(':')) -> NativeFantasyPoolSource.Native
            else -> throw IllegalArgumentException("This saved draft has unsupported player identities. Its roster has not been changed.")
        } }.toSet()
        require(kinds.size <= 1) { "This saved draft mixes player providers. Choose no new players until its identities can be reconciled safely." }
        return NativeFantasyPoolKey(league.sport, kinds.singleOrNull() ?: if (league.sport in nativeSports) NativeFantasyPoolSource.Native else NativeFantasyPoolSource.ESPN)
    }
    fun teamIdValid(key: NativeFantasyPoolKey, id: String): Boolean = if (key.source == NativeFantasyPoolSource.Native)
        key.sport in nativeSports && id.startsWith("${key.sport}:") && number.matches(id.substringAfter(':')) else number.matches(id)
    fun playerId(key: NativeFantasyPoolKey, raw: String): String? = if (key.source == NativeFantasyPoolSource.Native)
        raw.takeIf { key.sport in nativeSports && it.startsWith("${key.sport}:") && number.matches(it.substringAfter(':')) }
        else raw.takeIf(number::matches)?.let { "espn-${key.sport}-$it" }
    fun athlete(player: NativeFantasyPlayer, sport: String): Athlete? {
        val id = when {
            player.id.startsWith("espn-$sport-") -> player.id.removePrefix("espn-$sport-").takeIf(number::matches)
            sport in nativeSports && player.id.startsWith("$sport:") && number.matches(player.id.substringAfter(':')) -> player.id
            else -> null
        } ?: return null
        return Athlete(id, player.name, position = player.position, headshotUrl = player.image, injured = player.injury != null)
    }
}

/** Bounded public roster adapter; callbacks retain each provider's own IDs and access/cooldown policy. */
class NativeFantasyPoolLoader(private val now: () -> Long = ::nowMillis) {
    private val rosterGate = Semaphore(2)
    private val completePools = linkedMapOf<NativeFantasyPoolKey, NativeFantasyPool>()
    private val poolLock = SportsLock()
    private data class Loaded(val team: Team, val players: List<NativeFantasyPlayer>, val failed: Boolean)
    suspend fun load(key: NativeFantasyPoolKey, teams: suspend () -> List<Team>, roster: suspend (Team) -> List<RosterGroup>): NativeFantasyPool = coroutineScope {
        val path = NativeFantasyFixtures.leaguePath(key.sport) ?: throw SportsIOException("Unsupported fantasy player pool")
        val clubs = teams()
        if (clubs.isEmpty() || clubs.size > 64 || clubs.any { it.league.path != path || !NativeFantasyPoolPolicy.teamIdValid(key, it.id) } || clubs.distinctBy { it.id }.size != clubs.size)
            throw SportsIOException("Player-pool team identities are unavailable or inconsistent")
        val loaded = clubs.map { team -> async {
            rosterGate.withPermit {
                try {
                    val people = roster(team).flatMap { it.athletes }
                    if (people.isEmpty() || people.size > 256) throw SportsIOException("Player roster is empty or exceeds safe bounds")
                    val players = people.mapNotNull { person ->
                        val id = NativeFantasyPoolPolicy.playerId(key, person.id) ?: throw SportsIOException("Player roster provider identity mismatch")
                        val name = person.name.trim().takeIf { it.isNotEmpty() && it.length <= 256 } ?: return@mapNotNull null
                        NativeFantasyPlayer(id, name, team.abbreviation, person.position,
                            NativeFantasyEngine.eligible(person.position, key.sport), if (person.injured) "Reported injury" else null, person.headshotUrl)
                    }
                    if (players.isEmpty()) throw SportsIOException("No identified players in this roster")
                    Loaded(team, players, false)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { Loaded(team, emptyList(), true) }
            }
        } }.awaitAll()
        if (loaded.all { it.failed }) throw SportsIOException("No team rosters could be checked. Saved drafts are unchanged.")
        val groups = loaded.flatMap { club -> club.players.map { club.team.id to it } }.groupBy { it.second.id }
        if (groups.size > 4096) throw SportsIOException("Player pool exceeds safe bounds")
        val ambiguousIds = groups.filterValues { rows -> rows.map { it.first }.distinct().size > 1 }.keys
        val conflicts = ambiguousIds.size
        val players = groups.values.mapNotNull { rows ->
            if (rows.map { it.first }.distinct().size > 1) null else rows.first().second.copy(
                eligible = rows.flatMap { it.second.eligible }.toSet(), image = rows.firstNotNullOfOrNull { it.second.image }, injury = rows.firstNotNullOfOrNull { it.second.injury })
        }.sortedWith(compareBy<NativeFantasyPlayer> { it.name.lowercase() }.thenBy { it.id })
        val result = NativeFantasyPool(players, loaded.filter { it.failed }.map { it.team.name }, conflicts, now())
        poolLock.locked {
            if (result.complete) {
                completePools.remove(key)
                completePools[key] = result
                while (completePools.size > 4) completePools.remove(completePools.keys.first())
                result
            } else completePools[key]?.let { previous -> completePools.remove(key); completePools[key] = previous; previous.copy(players = previous.players.filterNot { it.id in ambiguousIds },
                unavailableTeams = result.unavailableTeams, ambiguousPlayers = conflicts, retainedPrevious = true) } ?: result
        }
    }
}
