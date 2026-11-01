package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.SportsHttp
import com.alexdiab.stadiatv.net.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Independent native surfaces; a blocked native feed never delays or removes the universal summary. */
class NativeGameCentres(http: SportsHttp, private val football: NativeFootball = NativeFootball(http), private val hockey: NativeHockey = NativeHockey(http), private val baseball: NativeBaseball = NativeBaseball(http),
    private val baseballExtras: NativeBaseballExtras = NativeBaseballExtras(http), private val basketball: NativeBasketball = NativeBasketball(http),
    private val soccer: NativeSoccer = NativeSoccer(http), private val now: () -> Long = ::nowMillis,
    private val mls: NativeMls = NativeMls(NativeMlsClient(http)), private val laLiga: NativeLaLiga? = null,
    private val laLigaProvider: NativeLaLigaProvider? = null) {
    private data class Cooldown(val until: Long, val transportOnly: Boolean)
    private val cooldown = SportsMemo<String, Cooldown>()

    /** Validated connectivity may recover an I/O outage, never an HTTP/rate-limit/provider rejection. */
    fun onNetworkRestored() {
        cooldown.transaction { cooldown.removeWhere { it.transportOnly } }
        soccer.onNetworkRestored()
        mls.onNetworkRestored()
        laLiga?.onNetworkRestored()
    }
    private fun backoff(path: String, millis: Long, transportOnly: Boolean) {
        val time = now()
        cooldown.transaction {
            val old = cooldown[path]
            cooldown[path] = if (old != null && old.until > time) Cooldown(maxOf(old.until, time + millis), old.transportOnly && transportOnly)
            else Cooldown(time + millis, transportOnly)
        }
    }

    suspend fun olderSoccerComments(match: Match, cursor: String, gameId: String? = null) = withTimeout(15000) {
        if (match.nativeReference?.provider == "MLS") mls.olderComments(match, cursor)
        else soccer.olderComments(requireNotNull(gameId ?: match.nativeReference?.gameId ?: NativePremierLeagueMapping.rawId(match.id)), cursor)
    }
    suspend fun baseballHighlights(gameId: String) = baseballExtras.highlightsSurface(gameId)
    suspend fun baseballProbability(gameId: String) = baseballExtras.probabilitySurface(gameId)
    suspend fun soccerTable(match: Match) = when (match.nativeReference?.provider) {
        "MLS" -> mls.table(match)
        "LaLiga" -> requireNotNull(laLigaProvider).table(match)
        else -> soccer.table(match)
    }
    suspend fun cached(match: Match): GameCentre? = when (match.league.path) { "football/nfl" -> football.cachedGame(match); "basketball/nba", "basketball/wnba" -> basketball.cached(match); "soccer/eng.1" -> soccer.cached(match); "soccer/usa.1" -> if (match.nativeReference?.provider == "MLS") mls.cached(match) else null; "soccer/esp.1" -> if (match.nativeReference?.provider == "LaLiga") laLiga?.cached(match) else null; else -> null }

    suspend fun load(match: Match, previous: GameCentre?, force: Boolean = false, tab: String = "Overview"): GameCentre? {
        if ((cooldown[match.league.path]?.until ?: 0) > now()) return null
        return try {
            withTimeout(if (match.nativeReference?.provider == "LaLiga") 30_000 else 15_000) { when (match.league.path) { "baseball/mlb" -> baseball.load(match, previous); "hockey/nhl" -> hockey.load(match, previous); "soccer/eng.1" -> soccer.load(match, previous, force, tab); "soccer/usa.1" -> if (match.nativeReference?.provider == "MLS") mls.load(match, previous, force, tab) else null; "soccer/esp.1" -> if (match.nativeReference?.provider == "LaLiga") laLiga?.load(match, previous, force, tab) else null; "football/nfl" -> football.load(match, previous, force); "basketball/nba", "basketball/wnba" -> basketball.load(match, previous, force, tab); else -> null } }
        } catch (_: TimeoutCancellationException) { backoff(match.league.path, 120_000, true); null }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) { backoff(match.league.path, NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds), false); null }
        catch (error: Exception) { backoff(match.league.path, 60_000, error is IOException); null }
    }

    companion object {
        fun overlay(base: GameCentre, native: GameCentre?): GameCentre {
            native ?: return base
            return base.copy(
                lineScore = native.lineScore ?: base.lineScore, teamStats = native.teamStats.ifEmpty { base.teamStats },
                leaders = native.leaders.ifEmpty { base.leaders }, plays = if (native.playsSource != null) native.plays else native.plays.ifEmpty { base.plays }, boxScore = native.boxScore.ifEmpty { base.boxScore },
                playsSource = if (native.playsSource != null || native.plays.isNotEmpty()) native.playsSource ?: native.providerStamp?.provider else base.playsSource,
                teamStatsSource = if (native.teamStats.isNotEmpty()) native.teamStatsSource ?: native.providerStamp?.provider else base.teamStatsSource,
                gameInfo = (base.gameInfo + native.gameInfo).associate { it.first to it.second }.toList(), drives = native.drives.ifEmpty { base.drives },
                winProbability = if ("probability" in native.baseballExtras.checkedAt) native.winProbability else native.winProbability.ifEmpty { base.winProbability },
                winProbabilitySource = if (native.winProbability.isNotEmpty() || "probability" in native.baseballExtras.checkedAt) native.winProbabilitySource ?: native.providerStamp?.provider else base.winProbabilitySource,
                baseballProbability = if (native.winProbability.isNotEmpty() || "probability" in native.baseballExtras.checkedAt) native.baseballProbability else base.baseballProbability,
                baseballExtras = native.baseballExtras,
                shots = if (native.basketball?.playsLoaded == true) native.shots else native.shots.ifEmpty { base.shots }, shotSource = native.shotSource ?: base.shotSource, liveMatch = native.liveMatch, providerStamp = native.providerStamp,
                baseball = native.baseball ?: base.baseball, football = native.football ?: base.football, soccer = native.soccer ?: base.soccer,
                hockey = native.hockey ?: base.hockey, basketball = native.basketball ?: base.basketball, videos = if ("highlights" in native.baseballExtras.checkedAt) native.videos else (native.videos + base.videos).distinctBy { it.id },
            )
        }
    }
}
