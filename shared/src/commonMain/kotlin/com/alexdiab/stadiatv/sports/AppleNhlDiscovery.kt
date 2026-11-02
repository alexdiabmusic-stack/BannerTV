package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.net.HttpStatusException
import com.alexdiab.stadiatv.foundation.SportsTime
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned Swift boundary. Only the native NHL provider supplies identities for this feed. */
class AppleNhlDiscovery internal constructor(
    private val provider: SportsProvider,
    private val scope: CoroutineScope,
) {
    fun scores(atMillis: Long, current: Boolean, completion: (String?, SportsFailure?) -> Unit): SportsRequest =
        request(completion) { provider.scores(if (current) null else atMillis) }

    fun schedule(startMillis: Long, days: Int, completion: (String?, SportsFailure?) -> Unit): SportsRequest =
        request(completion) {
            val games = mutableMapOf<String, Match>()
            var remaining = days.coerceAtLeast(0)
            var cursor = SportsTime.startOfDay(startMillis)
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val count = minOf(remaining, 366)
                for (game in provider.schedule(cursor, count)) games[game.key] = game
                remaining -= count
                if (remaining > 0) cursor = SportsTime.addDays(cursor, count)
            }
            games.values.sortedBy { it.startMillis }
        }

    private fun request(completion: (String?, SportsFailure?) -> Unit, load: suspend () -> List<Match>): SportsRequest =
        SportsRequest(scope.launch {
            val payload = try {
                withContext(Dispatchers.Default) {
                    val games = withTimeoutOrNull(45_000) { load() } ?: error("Discovery timed out")
                    NhlDiscoveryWire.encode(games)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                completion(null, SportsFailure("NHL discovery is unavailable.", (failure as? HttpStatusException)?.code))
                return@launch
            }
            ensureActive()
            completion(payload, null)
        })
}

internal object NhlDiscoveryWire {
    @Serializable
    private data class Feed(val version: Int, val games: List<Game>)

    @Serializable
    private data class Side(val id: String, val name: String, val abbreviation: String, val score: String?, val winner: Boolean)

    @Serializable
    private data class Game(
        val id: String, val startMillis: Long, val name: String, val shortName: String,
        val state: String, val status: String, val home: Side, val away: Side, val venue: String?,
        val broadcasts: List<String>, val clock: String?, val seconds: Int?, val running: Boolean?,
        val period: Int?, val periodLabel: String?,
        val phase: String?, val intermission: Boolean = false, val extraPeriod: Boolean = false,
    )

    private fun side(team: TeamSide): Side {
        val id = requireNotNull(team.teamId)
        require(id.startsWith("nhl:") && id.removePrefix("nhl:").toIntOrNull()?.let { it > 0 } == true)
        return Side(id.removePrefix("nhl:"), team.displayName, team.abbreviation, team.score, team.isWinner)
    }

    fun encode(matches: List<Match>): String {
        val games = matches.map { match ->
            val reference = requireNotNull(match.nativeReference)
            require(reference.provider == "NHL" && match.league.path == "hockey/nhl")
            require(match.id == "nhl:${reference.gameId}" && reference.gameId.matches(Regex("[0-9]{10}")))
            Game(reference.gameId, match.startMillis, match.name, match.shortName, match.state.name.lowercase(),
                match.statusDetail, side(match.home), side(match.away), match.venue, match.broadcasts, match.live.timing?.clock,
                match.live.timing?.secondsRemaining, match.live.timing?.running, match.live.timing?.period, match.live.timing?.periodLabel,
                match.live.timing?.phase, match.live.timing?.intermission == true, match.live.timing?.extraPeriod == true)
        }
        return Json.encodeToString(Feed(1, games))
    }
}
