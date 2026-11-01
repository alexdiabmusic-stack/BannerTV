package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/** Translation of MLBGameMapper, MLBPlayMapper and MLBGameCenterReducer. All IDs remain provider-scoped. */
class NativeBaseball(private val http: SportsHttp, private val baseUrl: String = "https://statsapi.mlb.com", private val now: () -> Long = ::nowMillis,
    snapshots: NativeBaseballSnapshotCache? = null) {
    private val loader = NativeBaseballLoader(http, baseUrl, now, snapshots)
    suspend fun cachedMatch(id: String): Match? = loader.cachedMatch(id)
    suspend fun load(match: Match, previous: GameCentre?): GameCentre? {
        val nativeId = match.nativeReference?.takeIf { it.provider == "MLB" }?.gameId
        if (match.nativeReference != null && nativeId == null) return null
        val cachedId = previous?.providerStamp?.takeIf { it.provider == "MLB" && previous.liveMatch?.key == match.key }?.gameId
        val id = nativeId ?: cachedId ?: run {
            val date = Dates.format("yyyy-MM-dd", match.startMillis, "America/New_York")
            val schedule = http.getJson("$baseUrl/api/v1/schedule?sportId=1&date=$date&hydrate=team", ttlMillis = 300_000, retries = 0)
            schedule["dates"].array.flatMap { it["games"].array }.filter { game ->
                teamsMatch(game.path("teams", "away", "team"), game.path("teams", "home", "team"), match) &&
                    Dates.parseIso(game["gameDate"].string())?.let { abs(it - match.startMillis) < 90 * 60_000 } == true
            }.singleOrNull()?.get("gamePk").stringOrNull()
        } ?: return null
        if (!id.matches(Regex("[1-9][0-9]{0,9}"))) return null
        return loader.load(id, match, previous)
    }

    companion object {
        private fun alias(s: String) = TeamIdentity.abbreviation("baseball/mlb", s)
        fun teamsMatch(away: JsonElement?, home: JsonElement?, match: Match): Boolean {
            fun same(raw: JsonElement?, side: TeamSide) = alias(raw["abbreviation"].string()) == alias(side.abbreviation) &&
                raw["id"].isPresent && (side.teamId?.takeIf { it.startsWith("mlb:") }?.removePrefix("mlb:")?.let { it == raw["id"].string() } ?: true)
            return same(away, match.away) && same(home, match.home)
        }
        private fun player(raw: JsonElement?, directory: Map<String, JsonElement> = emptyMap()): SportPlayer? {
            val id = raw["id"].stringOrNull()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) } ?: return null
            val full = directory[id]
            return SportPlayer(id, raw["fullName"].stringOrNull() ?: full["fullName"].string("Unknown player"), full["primaryNumber"].string(), full.path("primaryPosition", "abbreviation").string(), "https://www.mlb.com/player/$id")
        }
        private fun count(raw: JsonElement?, key: String, max: Int) = raw[key].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..max }

        fun parse(root: JsonElement, match: Match, requestedAt: Long): GameCentre? {
            val id = root["gamePk"].stringOrNull()?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) } ?: return null
            if (match.nativeReference?.provider == "MLB" && match.nativeReference.gameId != id) return null
            val game = root["gameData"]; val live = root["liveData"]; val line = live["linescore"]
            if (!teamsMatch(game.path("teams", "away"), game.path("teams", "home"), match)) return null
            val directory = game["players"].obj?.values.orEmpty().associateBy { it["id"].string() }
            val state = NativeBaseballProvider.gameState(game["status"])
            val half = line["inningState"].string()
            val outs = count(line, "outs", 3)
            val clearBases = half.lowercase() in setOf("middle", "end") || outs == 3
            val atBats = atBats(live["plays"], id, directory)
            val offense = line["offense"]
            val detail = BaseballDetail(count(line, "currentInning", 99), half, count(line, "balls", 3), count(line, "strikes", 2), outs,
                listOf("first", "second", "third").map { if (clearBases) null else player(offense[it], directory) }, player(offense["batter"], directory), player(line.path("defense", "pitcher"), directory), atBats, count(line, "scheduledInnings", 99),
                probableAway = player(game["probablePitchers"]["away"], directory), probableHome = player(game["probablePitchers"]["home"], directory))
            val innings = line["innings"].array
            fun total(side: String, key: String) = line.path("teams", side, key).string("–")
            val awayScore = total("away", "runs").takeIf { it != "–" } ?: match.away.score
            val homeScore = total("home", "runs").takeIf { it != "–" } ?: match.home.score
            fun winner(a: String?, b: String?) = state == GameState.FINAL && a?.toIntOrNull()?.let { n -> b?.toIntOrNull()?.let { n > it } } == true
            val nativeMatch = match.copy(state = state, statusDetail = game.path("status", "detailedState").string(match.statusDetail),
                away = match.away.copy(score = awayScore, isWinner = winner(awayScore, homeScore)), home = match.home.copy(score = homeScore, isWinner = winner(homeScore, awayScore)),
                live = if (state == GameState.LIVE) LiveContext(period = detail.inning?.let { "$half $it" }, situation = listOfNotNull(outs?.let { "$it outs" }, if (!clearBases) detail.balls?.let { b -> detail.strikes?.let { s -> "$b–$s count" } } else null, detail.baseDescription)) else LiveContext.EMPTY)
            val boxes = listOf("away", "home").mapNotNull { side -> box(live.path("boxscore", "teams", side), game.path("teams", side), directory) }
            val plays = atBats.asReversed().map { atBat -> Play(atBat.id, "${atBat.half} ${atBat.inning}", "", atBat.description, if (atBat.half.equals("top", true)) match.away.teamId else match.home.teamId, atBat.awayScore, atBat.homeScore, atBat.scoring, atBat.title, atBat.inning) }
            return GameCentre(
                lineScore = innings.takeIf { it.isNotEmpty() }?.let { LineScore(it.map { inning -> inning["num"].string() }, it.map { inning -> inning.path("away", "runs").string("–") }, it.map { inning -> inning.path("home", "runs").string("–") }, total("away", "runs"), total("home", "runs"), match.away.abbreviation, match.home.abbreviation,
                    line["teams"]["away"]["hits"].stringOrNull(), line["teams"]["home"]["hits"].stringOrNull(), line["teams"]["away"]["errors"].stringOrNull(), line["teams"]["home"]["errors"].stringOrNull()) },
                teamStats = listOf("runs" to "Runs", "hits" to "Hits", "errors" to "Errors", "leftOnBase" to "Left on base").mapNotNull { (key, label) -> if (line.path("teams", "away", key).isPresent && line.path("teams", "home", key).isPresent) StatComparison(label, total("away", key), total("home", key)) else null },
                plays = plays, boxScore = boxes, baseball = detail, liveMatch = nativeMatch,
                providerStamp = ProviderStamp("MLB", id, requestedAt, root.path("metaData", "timeStamp").stringOrNull(), atBats.lastOrNull()?.index ?: -1, state == GameState.FINAL),
                gameInfo = buildList {
                    game.path("venue", "name").stringOrNull()?.let { add("Venue" to it) }
                    val weather = listOfNotNull(game.path("weather", "condition").stringOrNull(), game.path("weather", "temp").stringOrNull()?.let { "$it°F" }, game.path("weather", "wind").stringOrNull()).joinToString(" · ")
                    if (weather.isNotBlank()) add("Weather" to weather)
                    (game["game"]["seriesDescription"].stringOrNull() ?: mapOf("W" to "World Series", "L" to "League Championship Series", "D" to "Division Series", "F" to "Wild Card", "S" to "Spring Training", "A" to "All-Star Game")[game["game"]["type"].string()])?.let { add("Series" to it) }
                },
            )
        }

        fun atBats(raw: JsonElement?, gameId: String, directory: Map<String, JsonElement> = emptyMap()): List<BaseballAtBat> {
            val rows = LinkedHashMap<Int, BaseballAtBat>()
            val scoring = raw["scoringPlays"].array.map { it.int(-1) }.toSet()
            for (play in raw["allPlays"].array + listOfNotNull(raw["currentPlay"])) {
                val index = play.path("about", "atBatIndex").stringOrNull()?.toIntOrNull()?.takeIf { it >= 0 } ?: continue
                val about = play["about"]; val result = play["result"]; val matchup = play["matchup"]
                val events = play["playEvents"].array
                val eventModels = events.mapIndexed { offset, event ->
                    val pitch = event["pitchData"]; val coord = pitch["coordinates"]; val details = event["details"]
                    val x = coord["pX"].double(Double.NaN); val z = coord["pZ"].double(Double.NaN); val top = pitch["strikeZoneTop"].double(Double.NaN); val bottom = pitch["strikeZoneBottom"].double(Double.NaN)
                    val location = if (x.isFinite() && z.isFinite() && top.isFinite() && bottom.isFinite() && top > bottom && abs(x) < 10 && z > -5 && z < 15) PitchLocation(x.toFloat(), z.toFloat(), top.toFloat(), bottom.toFloat()) else null
                    val eventIndex = event["index"].stringOrNull()?.toIntOrNull() ?: offset
                    val eventId = "$gameId:$index:$eventIndex"
                    val description = details["description"].stringOrNull() ?: details["event"].stringOrNull() ?: details["eventType"].string().replace('_', ' ')
                    val mappedPitch = if (event["isPitch"].bool()) BaseballPitch(eventId, event["pitchNumber"].stringOrNull()?.toIntOrNull(), description, details.path("type", "description").string(), pitch["startSpeed"].stringOrNull(), pitch.path("breaks", "spinRate").stringOrNull(), location, details["isBall"].bool(), details["isStrike"].bool(), details["isInPlay"].bool()) else null
                    fun measured(key: String) = event["hitData"][key].double(Double.NaN).takeIf { it.isFinite() && it >= 0 }
                    BaseballEvent(eventId, eventIndex, event["type"].string("unknown"), description, mappedPitch,
                        count(event["count"], "balls", 4), count(event["count"], "strikes", 3), count(event["count"], "outs", 3), measured("totalDistance"), measured("launchSpeed"))
                }.associateBy { it.id }.values.sortedBy { it.index }
                val pitches = eventModels.mapNotNull { it.pitch }
                val runners = play["runners"].array.mapIndexed { offset, runner ->
                    val movement = runner["movement"]; val details = runner["details"]
                    BaseballRunner("$gameId:$index:runner:$offset", player(details["runner"], directory), movement["start"].stringOrNull(), movement["end"].stringOrNull(), movement["isOut"].bool(),
                        movement["outBase"].stringOrNull(), player(details["responsiblePitcher"], directory), details["movementReason"].stringOrNull()?.replace('_', ' '),
                        details["earned"]?.let { it.bool() }, details["isScoringEvent"].bool(movement["end"].string() == "score"))
                }
                val title = result["event"].stringOrNull() ?: result["eventType"].string().replace('_', ' ')
                val row = BaseballAtBat("$gameId:$index", index, about["inning"].int(), about["halfInning"].string(), player(matchup["batter"], directory), player(matchup["pitcher"], directory), title, result["description"].string(title), about["isComplete"].bool(), about["isScoringPlay"].bool(index in scoring), result["awayScore"].stringOrNull(), result["homeScore"].stringOrNull(), pitches, eventModels.size, Dates.parseIso(about["endTime"].string()),
                    eventModels, runners, result["rbi"].stringOrNull()?.toIntOrNull()?.takeIf { it >= 0 })
                if (rows[index]?.complete == true && !row.complete) continue
                rows[index] = row
            }
            return rows.values.sortedBy { it.index }
        }

        private fun box(raw: JsonElement?, team: JsonElement?, directory: Map<String, JsonElement>): TeamBox? {
            val players = raw["players"].obj?.values.orEmpty().sortedBy { it["battingOrder"].int(9999) }
            val groups = listOf("batting" to listOf("AB" to "atBats", "R" to "runs", "H" to "hits", "RBI" to "rbi", "BB" to "baseOnBalls", "SO" to "strikeOuts", "HR" to "homeRuns", "AVG" to "avg"), "pitching" to listOf("IP" to "inningsPitched", "H" to "hits", "R" to "runs", "ER" to "earnedRuns", "BB" to "baseOnBalls", "SO" to "strikeOuts", "HR" to "homeRuns", "P" to "numberOfPitches", "S" to "strikes", "ERA" to "era"), "fielding" to listOf("PO" to "putOuts", "A" to "assists", "E" to "errors")).mapNotNull { (group, columns) ->
                val rows = players.mapNotNull { row ->
                    val stats = row.path("stats", group)
                    if (stats.obj.isNullOrEmpty()) return@mapNotNull null
                    val person = player(row["person"], directory) ?: return@mapNotNull null
                    BoxRow(person.name, row.path("position", "abbreviation").string(), columns.map { (_, key) -> stats[key].string("–") }, athleteId = "mlb:${person.id}", profileUrl = person.profileUrl, headshot = "https://img.mlbstatic.com/mlb-photos/image/upload/w_96,q_auto:good/v1/people/${person.id}/headshot/67/current", starter = row["battingOrder"].int(1) in 100..900 && row["battingOrder"].int(1) % 100 == 0)
                }
                if (rows.isEmpty()) null else BoxGroup(group.replaceFirstChar(Char::uppercase), columns.map { it.first }, rows, columns.map { (_, key) -> raw.path("teamStats", group, key).string("–") })
            }
            return if (groups.isEmpty()) null else TeamBox(team["name"].string(), team["abbreviation"].string(), groups)
        }

        /** Accept scoring corrections, but reject stale timestamps, inning/at-bat rollback and a completed AB becoming pending. */
        fun reduce(previous: GameCentre?, incoming: GameCentre): GameCentre {
            val old = previous?.takeIf { it.providerStamp?.provider == "MLB" } ?: return incoming
            val a = old.providerStamp!!; val b = incoming.providerStamp ?: return old
            if (a.gameId != b.gameId || b.requestedAt < a.requestedAt || a.final && !b.final) return old
            if (a.revision != null && b.revision != null && b.revision < a.revision) return old
            val before = old.baseball ?: return incoming; val after = incoming.baseball ?: return old
            if ((after.inning ?: 0) < (before.inning ?: 0)) return old
            if (after.inning == before.inning && before.half.equals("bottom", true) && after.half.equals("top", true)) return old
            val last = before.atBats.lastOrNull(); val next = after.atBats.lastOrNull()
            if (last != null && (next == null || next.index < last.index || next.index == last.index && (last.complete && !next.complete || next.eventCount < last.eventCount || last.endTime != null && next.endTime != null && next.endTime < last.endTime))) return old
            return incoming.copy(videos = if ("highlights" in incoming.baseballExtras.checkedAt) incoming.videos else incoming.videos.ifEmpty { old.videos },
                winProbability = if ("probability" in incoming.baseballExtras.checkedAt) incoming.winProbability else incoming.winProbability.ifEmpty { old.winProbability }, winProbabilitySource = incoming.winProbabilitySource ?: old.winProbabilitySource,
                baseballProbability = if ("probability" in incoming.baseballExtras.checkedAt) incoming.baseballProbability else incoming.baseballProbability.ifEmpty { old.baseballProbability },
                baseballExtras = BaseballExtraState(old.baseballExtras.checkedAt + incoming.baseballExtras.checkedAt,
                    (old.baseballExtras.saved - incoming.baseballExtras.checkedAt.keys) + incoming.baseballExtras.saved))
        }
    }
}
