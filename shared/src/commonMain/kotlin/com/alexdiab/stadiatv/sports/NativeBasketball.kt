package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.abs

/** Shared NBA/WNBA mapping and reducer; only NBA is allowed to use stats-host PBP fallback. */
class NativeBasketball(http: SportsHttp,
    private val nba: NativeBasketballClient = NativeBasketballClient(NativeBasketballLeague.NBA, http),
    private val wnba: NativeBasketballClient = NativeBasketballClient(NativeBasketballLeague.WNBA, http),
    private val snapshots: NativeBasketballSnapshotCache? = null,
    private val now: () -> Long = ::nowMillis) {
    private val locks = NativeBasketballLeague.entries.associateWith { Mutex() }
    private val memoryLock = SportsLock()
    private val memory = LinkedHashMap<String, NativeBasketballSnapshotCache.Snapshot>()
    private fun config(match: Match) = NativeBasketballLeague.entries.firstOrNull { it.league == match.league }
    private fun client(config: NativeBasketballLeague) = if (config == NativeBasketballLeague.NBA) nba else wnba
    private fun retain(key: String, snapshot: NativeBasketballSnapshotCache.Snapshot) = memoryLock.locked {
        memory.remove(key)
        if (snapshot.characters <= 2 * 1024 * 1024) memory[key] = snapshot
        while (memory.size > 2) memory.remove(memory.keys.first())
    }
    suspend fun cached(match: Match): GameCentre? {
        val config = config(match) ?: return null
        val id = nativeId(match, config) ?: return null
        return (memoryLock.locked { memory["${config.prefix}:$id"] } ?: snapshots?.load(config, id))?.details(match, config)
    }
    suspend fun cachedMatch(config: NativeBasketballLeague, id: String): Match? =
        (memoryLock.locked { memory["${config.prefix}:$id"] } ?: snapshots?.load(config, id))?.match(config)

    suspend fun load(match: Match, previous: GameCentre?, force: Boolean = false, tab: String = "Overview"): GameCentre? {
        val config = config(match) ?: return null
        if (match.nativeReference != null && match.nativeReference.provider != config.name) return null
        if ((match.nativeReference != null || match.id.startsWith("${config.prefix}:")) && nativeId(match, config) == null) return null
        return locks.getValue(config).withLock {
            val client = client(config)
            val id = nativeId(match, config) ?: previous?.takeIf { it.providerStamp?.provider == config.name && it.liveMatch?.key == match.key }?.providerStamp?.gameId ?: run {
                val rows = client.scoreboard(match.startMillis)
                rows.filter { validTeams(it, match, config) && NativeBasketballMapping.start(it)?.let { time -> abs(time - match.startMillis) < 3 * 3_600_000L } == true }
                    .singleOrNull()?.get("gameId").stringOrNull()
            }
            if (id == null || !id.matches(Regex("[0-9]{10}"))) return@withLock null
            val key = "${config.prefix}:$id"
            val saved = memoryLock.locked { memory[key] } ?: snapshots?.load(config, id)
            val old = previous?.takeIf { it.providerStamp?.provider == config.name && it.providerStamp.gameId == id && it.liveMatch?.key == match.key }
                ?: saved?.details(match, config)
            val requested = now()
            val full = force || old?.basketball?.boxLoaded != true || requested - (old.basketball.surfaceUpdatedAt["box"] ?: 0) !in 0 until 120_000 ||
                tab in setOf("Box score", "Box Score", "Shot chart", "Shot Chart") || old.liveMatch?.state == GameState.FINAL
            val errors = SportsMemo<String, String>()
            suspend fun <T> attempt(surface: String, operation: suspend () -> T): T? = try { operation() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { errors[surface] = if (error is HttpStatusException && error.code in setOf(403, 429)) "${config.name} isn't available on this network right now; accepted data is retained."
                    else "${config.name} $surface couldn't refresh; accepted data is retained."; null }
            val fresh = coroutineScope {
                val game = async { attempt(if (full) "box" else "score") {
                    val raw = if (full) client.box(id)["game"] ?: throw IOException("No basketball game")
                        else client.scoreboard(match.startMillis).singleOrNull { it["gameId"].string() == id } ?: throw IOException("Different basketball game")
                    if (raw["gameId"].string() != id || !validTeams(raw, match, config)) throw IOException("Basketball identity mismatch")
                    raw
                } }
                val plays = async { if (full || tab in setOf("Overview", "Plays", "Play-by-Play", "Shot chart", "Shot Chart")) attempt("plays") { client.plays(id) } else null }
                game.await() to plays.await()
            }
            val game = fresh.first ?: saved?.latestGame()
            if (game == null) return@withLock old?.copy(basketball = old.basketball?.copy(errors = errors.snapshot(), savedSnapshot = true))
            val incoming = update(old, game, fresh.second?.game, match, requested, config,
                scoreFresh = fresh.first != null, boxFresh = full && fresh.first != null, playsSource = fresh.second?.source)
            if (incoming == null) return@withLock old
            if (incoming === old && (fresh.first != null || fresh.second != null)) errors["score"] = "An older ${config.name} update was ignored; accepted data is unchanged."
            val result = incoming.copy(basketball = incoming.basketball?.copy(errors = errors.snapshot(), savedSnapshot = fresh.first == null && fresh.second == null))
            val accepted = (saved ?: NativeBasketballSnapshotCache.Snapshot()).accept(game.takeIf { fresh.first != null && incoming !== old }, fresh.second?.takeIf { incoming !== old }, full, requested, result)
            retain(key, accepted); snapshots?.save(config, id, accepted)
            result
        }
    }

    companion object {
        fun nativeId(match: Match, config: NativeBasketballLeague): String? =
            (match.nativeReference?.takeIf { it.provider == config.name }?.gameId ?: match.id.takeIf { it.startsWith("${config.prefix}:") }?.removePrefix("${config.prefix}:"))
                ?.takeIf { it.matches(Regex("[0-9]{10}")) }

        fun validTeams(game: JsonElement, match: Match, config: NativeBasketballLeague): Boolean {
            fun side(raw: JsonElement?, side: TeamSide): Boolean = TeamIdentity.sameAbbreviation(match.league.path, raw["teamTricode"].string(), side.abbreviation) &&
                (side.teamId?.takeIf { ':' in it }?.let { it == "${config.prefix}:${NativeBasketballMapping.id(raw["teamId"])}" } ?: true)
            return side(game["awayTeam"], match.away) && side(game["homeTeam"], match.home)
        }

        /** Independent score/box/play freshness: accepting one surface never re-dates another. */
        fun update(previous: GameCentre?, game: JsonElement, plays: JsonElement?, match: Match, requested: Long, config: NativeBasketballLeague,
            scoreFresh: Boolean = true, boxFresh: Boolean = true, playsSource: String? = null): GameCentre? {
            val parsed = parse(game, plays, match, requested, config == NativeBasketballLeague.WNBA) ?: return null
            val old = previous?.takeIf { it.providerStamp?.provider == config.name && it.providerStamp.gameId == parsed.providerStamp?.gameId }
            val times = old?.basketball?.surfaceUpdatedAt.orEmpty().toMutableMap()
            if (scoreFresh) times["score"] = requested
            val hasBox = boxFresh && game["awayTeam"]["players"] is JsonArray && game["homeTeam"]["players"] is JsonArray
            if (hasBox) times["box"] = requested
            if (plays != null) times["plays"] = requested
            val enriched = parsed.copy(boxScore = if (hasBox) parsed.boxScore else emptyList(), teamStats = if (hasBox) parsed.teamStats else emptyList(), leaders = if (hasBox) parsed.leaders else emptyList(),
                playsSource = if (plays != null) playsSource ?: config.name else null,
                teamStatsSource = if (hasBox) config.name else null,
                basketball = parsed.basketball?.copy(boxLoaded = hasBox,
                    playsLoaded = plays != null, surfaceUpdatedAt = times))
            return reduce(old, enriched)
        }

        fun clock(raw: String): String {
            return NativeBasketballDuration.clock(raw).orEmpty()
        }
        fun parse(game: JsonElement, pbp: JsonElement?, match: Match, requested: Long, wnba: Boolean = false): GameCentre? {
            val config = if (wnba) NativeBasketballLeague.WNBA else NativeBasketballLeague.NBA
            val id = game["gameId"].stringOrNull()?.takeIf { it.matches(Regex("[0-9]{10}")) } ?: return null
            val known = nativeId(match, config)
            if (match.nativeReference != null && (match.nativeReference.provider != config.name || known != id)) return null
            if (match.id.startsWith("${config.prefix}:") && known != id) return null
            if (pbp != null && (pbp["gameId"].string() != id || pbp["actions"] !is JsonArray || pbp["actions"].array.size > 8192 ||
                    pbp["actions"].array.any { it["gameId"].stringOrNull()?.let { returned -> returned != id } == true })) return null
            val away = game["awayTeam"]; val home = game["homeTeam"]
            if (!validTeams(game, match, config)) return null
            val (state, label) = NativeBasketballMapping.state(game)
            val period = game["period"].int(); val gameClock = clock(game["gameClock"].string())
            val aScore = away["score"].stringOrNull()?.toIntOrNull()?.takeIf { state != GameState.PRE && it >= 0 }
            val hScore = home["score"].stringOrNull()?.toIntOrNull()?.takeIf { state != GameState.PRE && it >= 0 }
            val mapped = NativeBasketballMapping.game(game, config)
            val nativeMatch = match.copy(state = state, away = match.away.copy(score = aScore?.toString(), isWinner = state == GameState.FINAL && aScore != null && hScore != null && aScore > hScore),
                home = match.home.copy(score = hScore?.toString(), isWinner = state == GameState.FINAL && aScore != null && hScore != null && hScore > aScore), statusDetail = NativeBasketballMapping.statusLabel(game, state, label),
                live = if (state == GameState.LIVE) LiveContext(gameClock, "$period") else LiveContext.EMPTY,
                startMillis = mapped?.startMillis ?: match.startMillis,
                venue = mapped?.venue ?: match.venue, broadcasts = mapped?.broadcasts?.ifEmpty { match.broadcasts } ?: match.broadcasts)
            val columns = listOf("MIN" to "minutes", "PTS" to "points", "REB" to "reboundsTotal", "AST" to "assists", "FG" to "fieldGoals", "3PT" to "threePointers", "FT" to "freeThrows", "STL" to "steals", "BLK" to "blocks", "TO" to "turnovers", "PF" to "foulsPersonal", "+/-" to "plusMinusPoints")
            fun value(stats: JsonElement?, key: String): String = when (key) {
                "fieldGoals", "threePointers", "freeThrows" -> {
                    val made = NativeBasketballPresentation.number(stats["${key}Made"]); val attempted = NativeBasketballPresentation.number(stats["${key}Attempted"])
                    if (made == null || attempted == null) "–" else "$made-$attempted"
                }
                "minutes" -> clock(stats[key].string()).substringBefore(':').toIntOrNull()?.toString() ?: "–"
                else -> NativeBasketballPresentation.number(stats[key]) ?: "–"
            }
            fun name(row: JsonElement) = row["name"].stringOrNull() ?: listOf(row["firstName"].string(), row["familyName"].string()).filter(String::isNotBlank).joinToString(" ")
            fun head(id: String) = "https://cdn.nba.com/headshots/${if (wnba) "wnba" else "nba"}/latest/260x190/$id.png"
            fun box(raw: JsonElement?, side: TeamSide): TeamBox? {
                val rows = raw["players"].array.sortedBy { it["order"].int(Int.MAX_VALUE) }.mapNotNull { row ->
                    val personId = NativeBasketballMapping.id(row["personId"]) ?: return@mapNotNull null
                    val stats = row["statistics"]
                    val played = row["played"].bool((NativeBasketballDuration.seconds(stats["minutes"].string()) ?: 0.0) > 0)
                    BoxRow(name(row).ifBlank { "Unknown player" },
                        listOf(row["position"].string(), row["notPlayingDescription"].stringOrNull() ?: row["notPlayingReason"].string()).filter(String::isNotBlank).joinToString(" · "),
                        columns.map { (_, key) -> value(stats, key) }, athleteId = "${config.prefix}:$personId", headshot = head(personId),
                        starter = row["starter"].bool(), didNotPlay = !played, profileUrl = "https://www.${config.prefix}.com/player/$personId")
                }
                return if (rows.isEmpty()) null else TeamBox(side.displayName, side.abbreviation, listOf(BoxGroup("Players", columns.map { it.first }, rows, columns.map { (_, key) -> value(raw["statistics"], key) })))
            }
            val boxes = listOfNotNull(box(away, match.away), box(home, match.home))
            val stats = listOf("fieldGoalsMade" to "fieldGoalsAttempted", "threePointersMade" to "threePointersAttempted", "freeThrowsMade" to "freeThrowsAttempted").mapIndexedNotNull { index, (made, attempted) ->
                if (!away.path("statistics", made).isPresent || !home.path("statistics", made).isPresent) return@mapIndexedNotNull null
                if (!away.path("statistics", attempted).isPresent || !home.path("statistics", attempted).isPresent) return@mapIndexedNotNull null
                StatComparison(listOf("Field goals", "Three pointers", "Free throws")[index], "${away.path("statistics", made).string()}/${away.path("statistics", attempted).string()}", "${home.path("statistics", made).string()}/${home.path("statistics", attempted).string()}", id = made)
            } + listOf("reboundsTotal" to "Rebounds", "assists" to "Assists", "steals" to "Steals", "blocks" to "Blocks", "turnovers" to "Turnovers", "foulsPersonal" to "Personal fouls", "pointsInThePaint" to "Points in paint").mapNotNull { (key, label) ->
                val a = NativeBasketballPresentation.number(away.path("statistics", key)) ?: return@mapNotNull null
                val h = NativeBasketballPresentation.number(home.path("statistics", key)) ?: return@mapNotNull null
                StatComparison(label, a, h, id = key, lowerIsBetter = key in setOf("turnovers", "foulsPersonal"))
            } + listOf("fieldGoalsPercentage" to "FG%", "threePointersPercentage" to "3PT%", "freeThrowsPercentage" to "FT%").mapNotNull { (key, label) ->
                fun percentage(team: JsonElement?): String? = team["statistics"][key].stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { decimal(it * 100, 1) + "%" }
                val a = percentage(away) ?: return@mapNotNull null; val h = percentage(home) ?: return@mapNotNull null
                StatComparison(label, a, h, id = key)
            }
            val leaders = listOf("points" to "Points", "reboundsTotal" to "Rebounds", "assists" to "Assists").mapNotNull { (key, title) ->
                fun leader(team: JsonElement?): LeaderEntry? { val row = team["players"].array.filter { it["played"].bool(true) }.maxByOrNull { it.path("statistics", key).int() } ?: return null; val value = row.path("statistics", key).int(); return if (value <= 0) null else LeaderEntry(name(row), "$value", row["position"].string(), head(row["personId"].string())) }
                val a = leader(away); val h = leader(home); if (a == null && h == null) null else LeaderComparison(title, a, h)
            }
            val actions = pbp["actions"].array
            val ordered = actions.associateBy { (it["actionId"] ?: it["actionNumber"] ?: it["orderNumber"]).string() }.values.filter { (it["actionNumber"] ?: it["orderNumber"]).isPresent }.sortedWith(compareBy({ it["period"].int() }, { (it["orderNumber"] ?: it["actionNumber"]).double() }))
            val plays = ordered.map { raw ->
                val owner = raw["teamTricode"].string()
                val playId = "${config.prefix}:$id:${(raw["actionId"] ?: raw["actionNumber"] ?: raw["orderNumber"]).string()}"
                val event = NativeBasketballPresentation.play(raw)
                val teamId = when {
                    TeamIdentity.sameAbbreviation(match.league.path, owner, match.home.abbreviation) -> match.home.teamId
                    TeamIdentity.sameAbbreviation(match.league.path, owner, match.away.abbreviation) -> match.away.teamId
                    else -> null
                }
                Play(playId, raw["period"].string(), clock(raw["clock"].string()), event.title, teamId,
                    raw["scoreAway"].stringOrNull(), raw["scoreHome"].stringOrNull(), raw["shotResult"].string().equals("Made", true),
                    listOf(raw["actionType"].string(), raw["subType"].string()).filter(String::isNotBlank).joinToString(" · "), raw["period"].int(),
                    shot = if (teamId == null) null else NativeGameInsights.nbaShots(listOf(raw), owner).firstOrNull()?.copy(id = playId, home = teamId == match.home.teamId),
                    sortOrder = (raw["orderNumber"] ?: raw["actionNumber"]).double(), basketballEvent = event)
            }.asReversed()
            val shots = plays.mapNotNull { it.shot }
            val onCourt = listOf(away, home).flatMap { team -> team["players"].array.filter { it["oncourt"].bool() }.takeIf { it.size <= 5 }.orEmpty() }
                .mapNotNull { row -> NativeBasketballMapping.id(row["personId"])?.let { SportPlayer("${config.prefix}:$it", name(row), row["jerseyNum"].string(), row["position"].string()) } }
            val aPeriods = away["periods"].array; val hPeriods = home["periods"].array; val count = maxOf(aPeriods.size, hPeriods.size)
            return GameCentre(lineScore = if (count == 0 || state == GameState.PRE) null else LineScore(EspnSummary.periodLabels(SportGroup.Basketball, count), List(count) { aPeriods.getOrNull(it)?.get("score").string("–") }, List(count) { hPeriods.getOrNull(it)?.get("score").string("–") }, aScore?.toString() ?: "–", hScore?.toString() ?: "–", match.away.abbreviation, match.home.abbreviation),
                plays = plays, boxScore = boxes, teamStats = stats, leaders = leaders, shots = shots, shotSource = if (shots.isNotEmpty()) "${config.name} native play data" else null,
                basketball = BasketballDetail(away["timeoutsRemaining"].stringOrNull()?.toIntOrNull(), home["timeoutsRemaining"].stringOrNull()?.toIntOrNull(), away["inBonus"].takeIf { it.isPresent }?.bool(), home["inBonus"].takeIf { it.isPresent }?.bool(), onCourt,
                    period = period, regulationPeriods = game["regulationPeriods"].int(4), boxLoaded = boxes.isNotEmpty(), playsLoaded = pbp != null,
                    gameLeaders = NativeBasketballPresentation.leaders(game, config), onCourtValid = listOf(away, home).all { team -> team["players"].array.count { it["oncourt"].bool() } <= 5 }),
                gameInfo = listOfNotNull("Source" to config.name,
                    nativeMatch.venue?.let { venue -> "Venue" to listOf(venue, game["arena"]["arenaCity"].string(), game["arena"]["arenaState"].string()).filter(String::isNotBlank).joinToString(", ") },
                    NativeBasketballMapping.start(game)?.let { "Date" to Dates.format("EEE, MMM d, yyyy · h:mm a", it) },
                    game["seriesText"].stringOrNull()?.let { "Series" to it },
                    nativeMatch.broadcasts.takeIf { it.isNotEmpty() }?.let { "Broadcast" to it.joinToString(" · ") }),
                liveMatch = nativeMatch, providerStamp = ProviderStamp(config.name, id, requested, sequence = ordered.maxOfOrNull { (it["orderNumber"] ?: it["actionNumber"]).int() } ?: -1, final = state == GameState.FINAL))
        }

        fun reduce(previous: GameCentre?, incoming: GameCentre): GameCentre {
            val old = previous?.takeIf { it.providerStamp?.provider == incoming.providerStamp?.provider } ?: return incoming
            val a = old.providerStamp ?: return incoming; val b = incoming.providerStamp ?: return old
            if (a.gameId != b.gameId || b.requestedAt < a.requestedAt || a.final && !b.final) return old
            val before = old.liveMatch; val after = incoming.liveMatch
            val oldPeriod = old.basketball?.period?.takeIf { it > 0 } ?: before?.live?.period?.toIntOrNull() ?: 0
            val newPeriod = incoming.basketball?.period?.takeIf { it > 0 } ?: after?.live?.period?.toIntOrNull() ?: 0
            if (newPeriod < oldPeriod) return old
            val scoreAdvanced = (after?.away?.score?.toIntOrNull() ?: 0) > (before?.away?.score?.toIntOrNull() ?: 0) || (after?.home?.score?.toIntOrNull() ?: 0) > (before?.home?.score?.toIntOrNull() ?: 0)
            fun seconds(clock: String?) = NativeBasketballDuration.clockSeconds(clock)
            if (newPeriod == oldPeriod && seconds(after?.live?.clock)?.let { newer -> seconds(before?.live?.clock)?.let { newer > it + 2 } } == true && !scoreAdvanced && incoming.plays.size <= old.plays.size) return old
            val scoreRegressed = (after?.away?.score?.toIntOrNull() ?: 0) < (before?.away?.score?.toIntOrNull() ?: 0) || (after?.home?.score?.toIntOrNull() ?: 0) < (before?.home?.score?.toIntOrNull() ?: 0)
            if (newPeriod == oldPeriod && scoreRegressed && incoming.boxScore.isEmpty()) return old
            val mergedPlays = (old.plays + incoming.plays).associateBy { it.id }.values.sortedWith(compareByDescending<Play> { it.periodNumber }.thenByDescending { it.sortOrder })
            val hasBox = incoming.basketball?.boxLoaded == true || incoming.boxScore.isNotEmpty()
            val shrinking = !b.final && old.boxScore.any { prior ->
                val next = incoming.boxScore.firstOrNull { TeamIdentity.sameAbbreviation(incoming.liveMatch?.league?.path.orEmpty(), it.abbreviation, prior.abbreviation) }
                (next?.groups?.sumOf { it.rows.size } ?: 0) < prior.groups.sumOf { it.rows.size }
            }
            val acceptBox = hasBox && !shrinking
            val detail = incoming.basketball
            val times = detail?.surfaceUpdatedAt.orEmpty().toMutableMap()
            if (!acceptBox) { times.remove("box"); old.basketball?.surfaceUpdatedAt?.get("box")?.let { times["box"] = it } }
            return incoming.copy(plays = mergedPlays, playsSource = incoming.playsSource ?: old.playsSource,
                boxScore = if (acceptBox) incoming.boxScore else old.boxScore, teamStats = if (acceptBox) incoming.teamStats else old.teamStats,
                teamStatsSource = if (acceptBox) incoming.teamStatsSource else old.teamStatsSource,
                leaders = if (acceptBox) incoming.leaders else old.leaders,
                lineScore = incoming.lineScore ?: old.lineScore, shots = mergedPlays.mapNotNull { it.shot }, shotSource = incoming.shotSource ?: old.shotSource,
                gameInfo = (old.gameInfo + incoming.gameInfo).associate { it.first to it.second }.toList(),
                basketball = detail?.copy(boxLoaded = acceptBox || old.basketball?.boxLoaded == true,
                    playsLoaded = detail.playsLoaded || old.basketball?.playsLoaded == true, surfaceUpdatedAt = times,
                    onCourt = if (acceptBox && detail.onCourtValid) detail.onCourt else old.basketball?.onCourt.orEmpty(),
                    gameLeaders = detail.gameLeaders.ifEmpty { old.basketball?.gameLeaders.orEmpty() }))
        }
    }
}
