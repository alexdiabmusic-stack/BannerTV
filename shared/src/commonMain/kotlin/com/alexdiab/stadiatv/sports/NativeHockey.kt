package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

class NativeHockey(private val http: SportsHttp, private val baseUrl: String = "https://api-web.nhle.com/v1", private val now: () -> Long = ::nowMillis,
    private val snapshots: NativeHockeySnapshotCache? = null) {
    private val cooldown = SportsMemo<String, Long>()
    suspend fun cachedMatch(id: String): Match? = snapshots?.match(id)
    suspend fun load(match: Match, previous: GameCentre?): GameCentre? = coroutineScope {
        val nativeId = match.nativeReference?.takeIf { it.provider.equals("NHL", true) }?.gameId
        if (match.nativeReference != null && nativeId == null) return@coroutineScope null
        val id = nativeId ?: previous?.providerStamp?.takeIf { it.provider == "NHL" }?.gameId ?: run {
            val date = Dates.format("yyyy-MM-dd", match.startMillis, "America/New_York")
            val schedule = withTimeout(4_000) { http.getJson("$baseUrl/schedule/$date", ttlMillis = 300_000, retries = 0) }
            schedule["gameWeek"].array.flatMap { it["games"].array }.filter { raw -> NativeGameInsights.sameTeams(raw.path("awayTeam", "abbrev").string(), raw.path("homeTeam", "abbrev").string(), match) && Dates.parseIso(raw["startTimeUTC"].string())?.let { abs(it - match.startMillis) < 3 * 3_600_000L } == true }.singleOrNull()?.get("id").stringOrNull()
        } ?: return@coroutineScope null
        if (!id.matches(Regex("[0-9]{10}"))) return@coroutineScope null
        // Existing screens already own their last good native state; don't decode a full disk PBP on every poll.
        val baseline = previous?.takeIf { it.providerStamp?.provider == "NHL" && it.providerStamp.gameId == id } ?: snapshots?.load(id, match)
        if (cooldown.size > 64) {
            val time = now()
            cooldown.snapshot().entries.filter { it.value <= time }.forEach { cooldown.remove(it.key, it.value) }
        }
        val requested = now()
        val errors = SportsMemo<String, String>()
        suspend fun surface(name: String): JsonElement? {
            val key = "$id:$name"
            val remaining = (cooldown[key] ?: 0L) - now()
            if (remaining > 0) { errors[name] = "NHL $name unavailable · retry in ${(remaining + 999) / 1000}s"; return null }
            return try {
                withTimeout(4_000) { http.getJson("$baseUrl/gamecenter/$id/$name", retries = 0) }
                    .takeIf { it["id"].string() == id || name == "right-rail" && (it["id"] == null || it["id"].string() == id) }
                    .also { if (it == null) errors[name] = "NHL $name response did not identify this game" }
            } catch (_: TimeoutCancellationException) {
                cooldown[key] = now() + 30_000; errors[name] = "NHL $name timed out"; null
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: HttpStatusException) {
                val delay = NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds)
                cooldown[key] = now() + delay; errors[name] = "NHL $name unavailable (HTTP ${error.code}) · retry in ${delay / 1000}s"; null
            } catch (_: Exception) {
                cooldown[key] = now() + 30_000; errors[name] = "NHL $name unavailable"; null
            }
        }
        val landing = async { surface("landing") }; val box = async { surface("boxscore") }; val pbp = async { surface("play-by-play") }; val rail = async { surface("right-rail") }
        val l = landing.await(); val b = box.await(); val p = pbp.await(); val r = rail.await()
        val result = update(baseline, l, b, p, r, match, requested, errors.snapshot()) ?: return@coroutineScope null
        snapshots?.save(id, requested, mapOf("landing" to l, "boxscore" to b, "play-by-play" to p, "right-rail" to r), result)
        result
    }

    companion object {
        private fun localized(raw: JsonElement?): String = raw["default"].stringOrNull() ?: raw.string()
        fun situation(code: String?): HockeyDetail? {
            val digits = code?.takeIf { it.length == 4 }?.map { it.digitToIntOrNull() } ?: return null
            if (digits.any { it == null } || digits[0] !in 0..1 || digits[3] !in 0..1 || digits[1] !in 3..6 || digits[2] !in 3..6) return null
            return HockeyDetail("${digits[1]}-on-${digits[2]}", digits[1], digits[2], digits[0] == 0, digits[3] == 0)
        }

        fun parse(root: JsonElement, landing: JsonElement?, box: JsonElement?, rail: JsonElement?, match: Match, requested: Long): GameCentre? {
            val id = root["id"].stringOrNull() ?: return null
            if (!id.matches(Regex("[0-9]{10}")) || root["gameState"].string() !in setOf("FUT", "PRE", "LIVE", "CRIT", "OFF", "FINAL", "SUSP")) return null
            if (match.nativeReference?.provider == "NHL" && match.nativeReference.gameId != id) return null
            if (!NativeGameInsights.sameTeams(root.path("awayTeam", "abbrev").string(), root.path("homeTeam", "abbrev").string(), match)) return null
            if (listOfNotNull(landing, box).any { it["id"].string() != id }) return null
            val state = when (root["gameState"].string()) { "LIVE", "CRIT" -> GameState.LIVE; "OFF", "FINAL" -> GameState.FINAL; else -> GameState.PRE }
            val period = root.path("periodDescriptor", "number").int()
            val clock = root.path("clock", "timeRemaining").string()
            val roster = root["rosterSpots"].array.associateBy { it["playerId"].string() }
            fun name(raw: JsonElement?) = listOf(localized(raw["firstName"]), localized(raw["lastName"])).filter(String::isNotBlank).joinToString(" ").ifBlank { localized(raw["name"]).ifBlank { "Unknown player" } }
            val rawPlays = root["plays"].array
            val players = NativeHockeyPresentation.players(box, roster, match)
            val eventsLoaded = root["plays"] is JsonArray
            val plays = NativeHockeyEvents.plays(root, landing, match)
            val currentSituation = if (state == GameState.LIVE) situation(rawPlays.maxByOrNull { it["sortOrder"].int() }?.get("situationCode").stringOrNull()) else null
            val hockey = (currentSituation ?: HockeyDetail(null, null, null, null, null)).copy(
                scoringSummary = NativeHockeyEvents.summary(landing, match), eventsLoaded = eventsLoaded,
                summaryLoaded = landing.path("summary", "scoring") is JsonArray,
                players = players, leaders = NativeHockeyPresentation.leaders(players, match),
                boxLoaded = box["playerByGameStats"] is JsonObject, landingLoaded = landing != null,
                recapUrl = landing.path("summary", "gameRecap", "url").stringOrNull()?.takeIf { url -> safeWebURL(url, false) },
            )
            val periodLabel = NativeHockeyEvents.periodLabel(root["periodDescriptor"])
            val extra = periodLabel.takeIf { it == "Shootout" || it.endsWith("OT") }
            val awayScore = root.path("awayTeam", "score").stringOrNull() ?: match.away.score
            val homeScore = root.path("homeTeam", "score").stringOrNull() ?: match.home.score
            fun winner(score: String?, other: String?) = state == GameState.FINAL && score?.toIntOrNull()?.let { n -> other?.toIntOrNull()?.let { n > it } } == true
            val nativeMatch = match.copy(state = state, away = match.away.copy(score = awayScore, isWinner = winner(awayScore, homeScore)), home = match.home.copy(score = homeScore, isWinner = winner(homeScore, awayScore)), statusDetail = when {
                root["gameScheduleState"].string() == "PPD" -> "Postponed"
                root["gameScheduleState"].string() == "CNCL" -> "Cancelled"
                root["gameState"].string() == "SUSP" -> "Suspended"
                state == GameState.FINAL -> "Final${extra?.let { " · $it" } ?: ""}"
                root.path("clock", "inIntermission").bool() -> "Intermission · $periodLabel"
                state == GameState.LIVE -> "$clock · $periodLabel"
                else -> match.statusDetail
            }, live = LiveContext(if (state == GameState.LIVE) clock else "", "$period", if (state == GameState.LIVE) listOfNotNull(hockey.strength, hockey.awayGoaliePulled?.takeIf { it }?.let { "${match.away.abbreviation} goalie pulled" }, hockey.homeGoaliePulled?.takeIf { it }?.let { "${match.home.abbreviation} goalie pulled" }) else emptyList()))
            val reportedStats = (rail["teamGameStats"].array.ifEmpty { landing.path("summary", "teamGameStats").array }).mapNotNull { raw ->
                val a = raw["awayValue"].stringOrNull() ?: return@mapNotNull null; val h = raw["homeValue"].stringOrNull() ?: return@mapNotNull null; val key = raw["category"].string()
                fun value(s: String) = if (key.endsWith("Pctg")) s.toDoubleOrNull()?.let { "${decimal(it * 100, 1)}%" } ?: s else s
                StatComparison(mapOf("sog" to "Shots on goal", "faceoffWinningPctg" to "Faceoff %", "faceoffWins" to "Faceoffs won", "powerPlay" to "Power plays", "powerPlayPctg" to "Power-play %", "pim" to "Penalty minutes", "blockedShots" to "Blocked shots", "hits" to "Hits", "giveaways" to "Giveaways", "takeaways" to "Takeaways")[key] ?: key, value(a), value(h), key, lowerIsBetter = key == "giveaways")
            }
            val stats = NativeHockeyPresentation.teamStats(reportedStats, root, players, match)
            val boxes = listOf("awayTeam", "homeTeam").mapNotNull { side ->
                val groups = listOf("forwards", "defense", "goalies").mapNotNull { group ->
                    val rawRows = box.path("playerByGameStats", side, group).array
                    val columns = if (group == "goalies") listOf("TOI" to "toi", "SV" to "saves", "SA" to "shotsAgainst", "GA" to "goalsAgainst", "SV%" to "savePctg") else listOf("G" to "goals", "A" to "assists", "PTS" to "points", "+/−" to "plusMinus", "PIM" to "pim", "SOG" to "sog", "Hits" to "hits", "Blocks" to "blockedShots", "TOI" to "toi")
                    val rows = rawRows.mapNotNull { row -> val playerId = row["playerId"].stringOrNull() ?: return@mapNotNull null; val person = roster[playerId] ?: row; BoxRow(name(person), row["position"].string(), columns.map { (_, key) -> row[key].string("–") }, athleteId = "nhl:$playerId", headshot = person["headshot"].stringOrNull(), profileUrl = "https://www.nhl.com/player/$playerId") }
                    if (rows.isEmpty()) null else BoxGroup(group.replaceFirstChar(Char::uppercase), columns.map { it.first }, rows, emptyList())
                }
                if (groups.isEmpty()) null else TeamBox(if (side == "homeTeam") match.home.displayName else match.away.displayName, if (side == "homeTeam") match.home.abbreviation else match.away.abbreviation, groups)
            }
            return GameCentre(plays = plays, playsSource = if (eventsLoaded) "NHL play-by-play" else null, teamStats = stats, boxScore = boxes, liveMatch = nativeMatch, hockey = hockey, providerStamp = ProviderStamp("NHL", id, requested, sequence = rawPlays.maxOfOrNull { it["sortOrder"].int() } ?: -1, final = state == GameState.FINAL), gameInfo = listOfNotNull(localized(root["venue"]).takeIf(String::isNotBlank)?.let { "Venue" to it }))
        }

        /** Each resource is compared with the previous game independently, like NHLGameCenterReducer.
         * A lagging PBP must not veto a final landing/box response. Complete event snapshots may remove
         * reviewed goals, but a truncated sequence never replaces the last good event stream. */
        fun update(previous: GameCentre?, landing: JsonElement?, box: JsonElement?, pbp: JsonElement?, rail: JsonElement?,
            match: Match, requested: Long, failures: Map<String, String> = emptyMap()): GameCentre? {
            val errors = failures.toMutableMap()
            val old = previous?.takeIf { it.providerStamp?.provider == "NHL" }
            if ((old?.providerStamp?.requestedAt ?: 0) > requested) return old
            val surfaces = linkedMapOf("landing" to landing, "boxscore" to box, "play-by-play" to pbp)
            val expectedId = match.nativeReference?.takeIf { it.provider == "NHL" }?.gameId ?: old?.providerStamp?.gameId
                ?: surfaces.values.firstNotNullOfOrNull { it?.get("id").stringOrNull() }
            val accepted = surfaces.mapNotNull { (key, raw) ->
                if (raw == null) return@mapNotNull null
                val value = parse(raw, null, null, null, match, requested)
                when {
                    value == null || value.providerStamp?.gameId != expectedId -> { errors[key] = "NHL $key is missing valid game information"; null }
                    !accepts(old, value) -> { errors[key] = "NHL $key returned older game data; keeping the last good section"; null }
                    else -> key to value
                }
            }.toMap()
            var metadata = old
            var metadataRoot: JsonElement? = null
            for ((key, value) in accepted) if (accepts(metadata, value)) { metadata = value; metadataRoot = surfaces[key] }
            if (accepted.isEmpty()) return old?.copy(hockey = old.hockey?.copy(errors = errors.entries.sortedBy { it.key }.associate { it.key to it.value }))
            val base = metadata ?: return null
            val root = metadataRoot ?: return null
            val freshRail = rail?.takeIf { it["id"] == null || it["id"].string() == expectedId }
            val current = old?.hockey ?: HockeyDetail(null, null, null, null, null)
            val freshLanding = landing.takeIf { "landing" in accepted }
            val freshBox = box.takeIf { "boxscore" in accepted }
            val freshPbp = pbp.takeIf { "play-by-play" in accepted && (accepted["play-by-play"]?.providerStamp?.sequence ?: -1) >= (old?.providerStamp?.sequence ?: -1) }
            if (pbp != null && "play-by-play" in accepted && freshPbp == null) errors["play-by-play"] = "NHL play-by-play returned a truncated sequence; keeping the last good events"
            val roster = pbp?.takeIf { "play-by-play" in accepted }?.get("rosterSpots").array.associateBy { it["playerId"].string() }
            val players = if (freshBox != null && freshBox["playerByGameStats"] is JsonObject) NativeHockeyPresentation.players(freshBox, roster, match) else current.players
            val parsedBox = freshBox?.let { parse(it, null, it, null, match, requested) }
            val parsedPlays = freshPbp?.let { parse(it, freshLanding, null, null, match, requested) }
            val oldEvents = old?.plays.orEmpty().associateBy { it.id }
            val plays = parsedPlays?.plays?.map { event ->
                val before = oldEvents[event.id]
                if (freshLanding != null || before == null) event else event.copy(
                    videoUrl = event.videoUrl ?: before.videoUrl,
                    hockeyEvent = event.hockeyEvent?.let { fresh -> fresh.copy(strength = fresh.strength ?: before.hockeyEvent?.strength,
                        seasonGoals = fresh.seasonGoals ?: before.hockeyEvent?.seasonGoals,
                        assistSeasonTotals = fresh.assistSeasonTotals.ifEmpty { before.hockeyEvent?.assistSeasonTotals.orEmpty() }) })
            } ?: old?.plays.orEmpty()
            val freshStats = parse(root, freshLanding, null, freshRail, match, requested)?.teamStats.orEmpty()
            val stats = NativeHockeyPresentation.teamStats(freshStats, root, players, match)
            val times = current.surfaceUpdatedAt.toMutableMap().apply {
                if (freshLanding != null) put("landing", requested)
                if (freshBox != null && freshBox["playerByGameStats"] is JsonObject) put("boxscore", requested)
                if (freshPbp != null && freshPbp["plays"] is JsonArray) put("play-by-play", requested)
                if (freshRail != null) put("right-rail", requested)
            }
            val situation = parsedPlays?.hockey?.takeIf { base.liveMatch?.state == GameState.LIVE }
            val detail = current.copy(
                strength = situation?.strength, awaySkaters = situation?.awaySkaters, homeSkaters = situation?.homeSkaters,
                awayGoaliePulled = situation?.awayGoaliePulled, homeGoaliePulled = situation?.homeGoaliePulled,
                scoringSummary = if (freshLanding != null) NativeHockeyEvents.summary(freshLanding, match) else current.scoringSummary,
                summaryLoaded = freshLanding != null || current.summaryLoaded,
                landingLoaded = freshLanding != null || current.landingLoaded,
                recapUrl = if (freshLanding != null) parse(freshLanding, freshLanding, null, null, match, requested)?.hockey?.recapUrl else current.recapUrl,
                players = players, leaders = NativeHockeyPresentation.leaders(players, match),
                boxLoaded = freshBox?.get("playerByGameStats") is JsonObject || current.boxLoaded,
                eventsLoaded = parsedPlays?.hockey?.eventsLoaded == true || current.eventsLoaded,
                errors = errors.entries.sortedBy { it.key }.associate { it.key to it.value }, surfaceUpdatedAt = times, savedSnapshot = false,
            )
            return base.copy(plays = plays, playsSource = if (detail.eventsLoaded) "NHL play-by-play" else null,
                boxScore = if (freshBox?.get("playerByGameStats") is JsonObject) parsedBox?.boxScore.orEmpty() else old?.boxScore.orEmpty(),
                teamStats = (old?.teamStats.orEmpty() + stats).associateBy { it.id ?: it.label }.values.toList(),
                hockey = detail, gameInfo = base.gameInfo.ifEmpty { old?.gameInfo.orEmpty() },
                providerStamp = base.providerStamp?.copy(requestedAt = requested, sequence = parsedPlays?.providerStamp?.sequence ?: old?.providerStamp?.sequence ?: -1))
        }

        private fun accepts(old: GameCentre?, candidate: GameCentre): Boolean {
            if (old == null) return true
            val a = old.providerStamp ?: return true; val b = candidate.providerStamp ?: return false
            if (a.gameId != b.gameId || b.requestedAt < a.requestedAt || a.final && !b.final) return false
            val before = old.liveMatch ?: return true; val after = candidate.liveMatch ?: return false
            if (before.state == GameState.LIVE && after.state == GameState.PRE) return false
            val p = before.live.period?.toIntOrNull() ?: 0; val q = after.live.period?.toIntOrNull() ?: 0
            if (q < p) return false
            fun seconds(s: String?) = s?.split(':')?.takeIf { it.size == 2 }?.let { v -> v[0].toIntOrNull()?.let { m -> v[1].toIntOrNull()?.takeIf { it in 0..59 }?.let { m * 60 + it } } }
            return !(p == q && before.state == GameState.LIVE && after.state == GameState.LIVE &&
                seconds(after.live.clock)?.let { n -> seconds(before.live.clock)?.let { n > it } } == true)
        }

        fun reduce(old: GameCentre?, incoming: GameCentre): GameCentre {
            if (old?.providerStamp?.provider != "NHL") return incoming
            val before = old.providerStamp!!; val after = incoming.providerStamp ?: return old
            if (before.gameId != after.gameId || after.requestedAt < before.requestedAt || before.final && !after.final) return old
            val a = old.liveMatch; val b = incoming.liveMatch
            val oldPeriod = a?.live?.period?.toIntOrNull() ?: 0; val newPeriod = b?.live?.period?.toIntOrNull() ?: 0
            if (newPeriod < oldPeriod || a?.state == GameState.LIVE && b?.state == GameState.PRE) return old
            fun seconds(s: String?) = s?.split(':')?.takeIf { it.size == 2 }?.let { p -> p[0].toIntOrNull()?.let { m -> p[1].toIntOrNull()?.let { m * 60 + it } } }
            if (newPeriod == oldPeriod && a?.state == GameState.LIVE && b?.state == GameState.LIVE && seconds(b.live.clock)?.let { seconds(a.live.clock)?.let { oldSeconds -> it > oldSeconds } } == true) return old
            val keepPlays = incoming.hockey?.eventsLoaded != true || after.sequence < before.sequence
            val detail = incoming.hockey?.let { fresh -> fresh.copy(
                scoringSummary = if (fresh.summaryLoaded) fresh.scoringSummary else old.hockey?.scoringSummary.orEmpty(),
                summaryLoaded = fresh.summaryLoaded || old.hockey?.summaryLoaded == true,
                eventsLoaded = if (keepPlays) old.hockey?.eventsLoaded == true else fresh.eventsLoaded,
                players = if (fresh.boxLoaded) fresh.players else old.hockey?.players.orEmpty(),
                leaders = if (fresh.boxLoaded) fresh.leaders else old.hockey?.leaders.orEmpty(), boxLoaded = fresh.boxLoaded || old.hockey?.boxLoaded == true,
                recapUrl = if (fresh.landingLoaded) fresh.recapUrl else old.hockey?.recapUrl, landingLoaded = fresh.landingLoaded || old.hockey?.landingLoaded == true,
            ) } ?: old.hockey
            return incoming.copy(plays = if (keepPlays) old.plays else incoming.plays,
                playsSource = if (keepPlays) old.playsSource else incoming.playsSource,
                providerStamp = if (keepPlays) after.copy(sequence = before.sequence) else after,
                boxScore = incoming.boxScore.ifEmpty { old.boxScore }, teamStats = incoming.teamStats.ifEmpty { old.teamStats }, hockey = detail)
        }
    }
}
