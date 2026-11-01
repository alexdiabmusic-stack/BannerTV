package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*

/** Null means not fetched/unavailable; an empty list is a successfully checked resource. */
data class NativeSoccerUpdate(
    val match: Match, val gameId: String, val requestedAt: Long,
    val providerId: String = "EPL", val provider: String = "Premier League", val full: Boolean = false,
    val events: List<Play>? = null, val lineups: List<SoccerLineup>? = null,
    val statistics: List<StatComparison>? = null, val officials: List<SoccerOfficial>? = null,
    val commentary: List<SoccerComment>? = null, val nextCursor: String? = null,
    val playerNames: Map<String, String> = emptyMap(), val gameInfo: List<Pair<String, String>> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val playerStats: List<SoccerPlayerMatchStats>? = null,
    val shots: List<SoccerShot>? = null, val pressure: List<SoccerPressureSample>? = null,
    val scoreUpdatedAt: Long = requestedAt, val scoreSource: String? = null,
    val resourceTimes: Map<String, Long> = emptyMap(), val resourceSources: Map<String, String> = emptyMap(),
)

/** One canonical merge point for soccer. Only the match endpoint supplies score/clock/status. */
object NativeSoccerReducer {
    fun apply(previous: GameCentre?, update: NativeSoccerUpdate): GameCentre? {
        if (update.match.league.group != SportGroup.Soccer || update.gameId.isBlank() || update.requestedAt <= 0) return null
        update.match.nativeReference?.let { if (it.provider != update.providerId || it.gameId != update.gameId) return null }
        val old = previous?.takeIf { it.providerStamp?.provider == update.provider && it.providerStamp.gameId == update.gameId &&
            (it.liveMatch == null || it.liveMatch.key == update.match.key) }
        val stamp = old?.providerStamp
        if (stamp != null && (stamp.requestedAt > update.requestedAt || stamp.final && update.match.state != GameState.FINAL)) return old
        val soccer = old?.soccer ?: SoccerDetail()
        if (update.scoreUpdatedAt <= 0 || update.scoreUpdatedAt > update.requestedAt + 300_000 ||
            update.resourceTimes.values.any { it <= 0 || it > update.requestedAt + 300_000 }) return old
        val times = soccer.surfaceUpdatedAt.toMutableMap().apply { put("overview", update.scoreUpdatedAt) }
        val announced = update.lineups?.let { fresh ->
            listOf(true, false).mapNotNull { home -> fresh.firstOrNull { it.home == home } ?: soccer.lineups.firstOrNull { it.home == home } }
        } ?: soccer.lineups
        if (update.lineups != null && (update.lineups.isNotEmpty() || soccer.lineups.isEmpty())) times["lineups"] = update.requestedAt
        val statistics = update.statistics?.takeUnless { it.isEmpty() && old?.teamStats?.isNotEmpty() == true }
        if (statistics != null) times["statistics"] = update.requestedAt
        val officials = update.officials?.takeUnless { it.isEmpty() && soccer.officials.isNotEmpty() }
        if (officials != null) times["officials"] = update.requestedAt
        if (update.events != null) times["events"] = update.requestedAt
        if (update.commentary != null) times["commentary"] = update.requestedAt
        if (update.playerStats != null) times["playerStats"] = update.requestedAt
        if (update.shots != null) times["shots"] = update.requestedAt
        if (update.pressure != null) times["pressure"] = update.requestedAt
        // Only accepted resources get a timestamp; an error/retry cannot re-date retained data.
        times.keys.toList().forEach { key -> update.resourceTimes[key]?.let { times[key] = it } }
        val comments = (soccer.commentary + update.commentary.orEmpty()).groupBy { it.id }.values.map { versions ->
            versions.reduce { accepted, incoming -> if (incoming.version >= accepted.version) incoming else accepted }
        }
            .sortedByDescending { it.timestamp ?: Long.MIN_VALUE }.take(500)
        val names = (soccer.playerNames + update.playerNames).entries.toList().takeLast(256).associate { it.key to it.value }
        return (old ?: GameCentre()).copy(liveMatch = update.match,
            providerStamp = ProviderStamp(update.provider, update.gameId, update.requestedAt, final = update.match.state == GameState.FINAL),
            // EPL incidents are complete team buckets: replacing them also removes a withdrawn/VAR-cancelled goal.
            plays = update.events ?: old?.plays.orEmpty(),
            playsSource = if (update.events != null) "${update.resourceSources["events"] ?: update.provider} · native match events" else old?.playsSource,
            teamStats = statistics ?: old?.teamStats.orEmpty(),
            teamStatsSource = if (statistics != null) "${update.resourceSources["statistics"] ?: update.provider} · native match statistics" else old?.teamStatsSource,
            gameInfo = (old?.gameInfo.orEmpty() + update.gameInfo).associate { it.first to it.second }.toList().take(32),
            soccer = soccer.copy(lineups = announced, commentary = comments,
                nextCursor = if (update.commentary != null && soccer.commentaryPages == 0) update.nextCursor else soccer.nextCursor,
                officials = officials ?: soccer.officials, surfaceUpdatedAt = times, playerNames = names,
                lastFullRefresh = if (update.full) update.requestedAt else soccer.lastFullRefresh,
                playerStats = update.playerStats ?: soccer.playerStats, shots = update.shots ?: soccer.shots,
                pressure = update.pressure ?: soccer.pressure, scoreUpdatedAt = update.scoreUpdatedAt,
                scoreSource = update.scoreSource ?: soccer.scoreSource,
                surfaceSources = soccer.surfaceSources + update.resourceSources,
                overviewNotPublished = false, savedSnapshot = false, errors = update.errors))
    }
}
