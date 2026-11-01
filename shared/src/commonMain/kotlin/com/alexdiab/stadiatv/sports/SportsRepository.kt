package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.Athlete
import com.alexdiab.stadiatv.domain.AthleteOverview
import com.alexdiab.stadiatv.domain.GameCentre
import com.alexdiab.stadiatv.domain.Injury
import com.alexdiab.stadiatv.domain.LeaderBoard
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.Racer
import com.alexdiab.stadiatv.domain.RosterGroup
import com.alexdiab.stadiatv.domain.SportsCapability
import com.alexdiab.stadiatv.domain.StandingGroup
import com.alexdiab.stadiatv.domain.Team
import com.alexdiab.stadiatv.net.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Routes each request to the league's native provider and falls back to ESPN when the native source
 * fails or is blocked on this network. A circuit breaker stops retrying a dead native source for a
 * few minutes, so one blocked host never makes every screen wait on a timeout.
 */
class SportsRepository(
    private val native: Map<String, SportsProvider>,
    private val espn: EspnApi,
    private val now: () -> Long = ::nowMillis,
) {
    private val espnProviders = League.ALL.associate { it.path to EspnProvider(it, espn) }
    private val health = HashMap<Pair<String, SportsCapability>, Health>()
    private val healthLock = SportsLock()

    private class Health {
        var failures = 0
        var openUntil = 0L
        var transportOnly = true
    }

    fun espnProvider(league: League): SportsProvider = espnProviders[league.path] ?: EspnProvider(league, espn)

    fun hasNativeProvider(league: League) = native.containsKey(league.path)

    /** True when the best source for this league supports the capability. */
    fun supports(league: League, capability: SportsCapability): Boolean =
        native[league.path]?.capabilities?.contains(capability) == true ||
            espnProvider(league).capabilities.contains(capability)

    private suspend fun <T> route(
        league: League,
        capability: SportsCapability,
        call: suspend (SportsProvider) -> T,
    ): T {
        val primary = native[league.path]
        if (primary != null && capability in primary.capabilities && !isOpen(league, capability)) {
            try {
                return call(primary).also { recordSuccess(league, capability) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: UnsupportedCapabilityException) {
                // Not a failure: this source simply doesn't serve it.
            } catch (error: Exception) {
                recordFailure(league, capability, error)
            }
        }
        return call(espnProvider(league))
    }

    suspend fun scoreboard(league: League, date: Long? = null): List<Match> =
        route(league, SportsCapability.Scores) { it.scores(date) }

    suspend fun schedule(league: League, start: Long, days: Int): List<Match> =
        route(league, SportsCapability.Schedule) { it.schedule(start, days) }

    suspend fun teams(league: League): List<Team> = route(league, SportsCapability.Teams) { it.teams() }

    suspend fun standings(league: League): List<StandingGroup> = route(league, SportsCapability.Standings) { it.standings() }

    suspend fun roster(league: League, teamId: String): List<RosterGroup> = when {
        ':' in teamId -> nativeIdentity(league, teamId, SportsCapability.Rosters).roster(teamId)
        // Old saved/search/ESPN pages retain their original identity; never reinterpret the number as native.
        league.path in setOf("hockey/nhl", "baseball/mlb", "football/nfl", "basketball/nba", "basketball/wnba", "soccer/eng.1", "soccer/usa.1", "soccer/esp.1") -> espnProvider(league).roster(teamId)
        else -> route(league, SportsCapability.Rosters) { it.roster(teamId) }
    }

    suspend fun athlete(league: League, athlete: Athlete): AthleteOverview = when {
        ':' in athlete.id -> nativeIdentity(league, athlete.id, SportsCapability.Players).athlete(athlete)
        league.path in setOf("hockey/nhl", "baseball/mlb", "football/nfl", "basketball/nba", "basketball/wnba", "soccer/eng.1", "soccer/usa.1", "soccer/esp.1") -> espnProvider(league).athlete(athlete)
        else -> route(league, SportsCapability.Players) { it.athlete(athlete) }
    }

    private fun nativeIdentity(league: League, id: String, capability: SportsCapability): SportsProvider {
        val provider = native[league.path]
        require(provider != null && provider.providerId.equals(id.substringBefore(':'), true) && capability in provider.capabilities) {
            "No configured provider for this ${capability.name.lowercase()} identity"
        }
        return provider
    }

    suspend fun leaders(league: League): List<LeaderBoard> = route(league, SportsCapability.Leaders) { it.leaders() }

    suspend fun injuries(league: League): List<Injury> = route(league, SportsCapability.Injuries) { it.injuries() }

    suspend fun field(league: League): List<Racer> = route(league, SportsCapability.Field) { it.field() }

    suspend fun gameCentre(match: Match): GameCentre {
        val identity = match.nativeReference ?: return route(match.league, SportsCapability.GameDetails) { it.gameCentre(match) }
        val provider = native[match.league.path]?.takeIf { it.providerId.equals(identity.provider, true) && SportsCapability.GameDetails in it.capabilities }
        return provider?.gameCentre(match) ?: GameCentre.EMPTY // A native game never falls through to ESPN.
    }

    /** Live detail from the native source for a single match; falls back to the match unchanged. */
    suspend fun enrich(match: Match): Match {
        val provider = native[match.league.path] ?: return match
        if (isOpen(match.league, SportsCapability.GameDetails)) return match
        return try {
            provider.enrich(match)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            match
        }
    }

    /**
     * Today's scoreboard for many leagues, emitted league by league as each finishes so screens fill
     * in progressively instead of waiting on the slowest provider.
     */
    fun liveScores(leagues: List<League>, concurrency: Int = 6): Flow<LeagueResult> = channelFlow {
        val gate = Semaphore(concurrency)
        leagues.forEach { league ->
            launch {
                gate.withPermit {
                    val result = runCatching { scoreboard(league) }
                    if (result.exceptionOrNull() is CancellationException) throw result.exceptionOrNull()!!
                    send(LeagueResult(league, result.getOrDefault(emptyList()), result.exceptionOrNull()))
                }
            }
        }
        // channelFlow already waits for its children. awaitClose here would never finish, so
        // MatchFeed.load's lock/finally would remain stuck after every league had responded.
    }

    data class LeagueResult(val league: League, val matches: List<Match>, val error: Throwable?)

    // region Circuit breaker

    private fun isOpen(league: League, capability: SportsCapability): Boolean = healthLock.locked {
        (health[league.path to capability]?.openUntil ?: 0) > now()
    }

    private fun recordSuccess(league: League, capability: SportsCapability) = healthLock.locked { health.remove(league.path to capability); Unit }

    private fun recordFailure(league: League, capability: SportsCapability, error: Exception) = healthLock.locked {
        val state = health.getOrPut(league.path to capability) { Health() }
        if (state.openUntil > 0 && state.openUntil <= now()) { state.failures = 0; state.openUntil = 0; state.transportOnly = true }
        state.transportOnly = state.transportOnly && error is IOException && error !is HttpStatusException
        state.failures++
        if (state.failures >= FAILURE_THRESHOLD) {
            state.openUntil = now() + OPEN_MILLIS
            state.failures = 0
        }
    }

    /** A validated network change clears I/O outages only, never HTTP/rate-limit or non-I/O failures.
     * Provider-local 403/429 cooldowns remain authoritative. One broken surface cannot disable another. */
    fun onNetworkRestored() = healthLock.locked { health.entries.removeAll { it.value.transportOnly }; Unit }

    // endregion

    private companion object {
        const val FAILURE_THRESHOLD = 3
        const val OPEN_MILLIS = 5 * 60_000L
    }
}
