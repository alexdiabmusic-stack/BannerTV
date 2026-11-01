package com.alexdiab.stadiatv.sports.pga

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.ByteString.Companion.decodeBase64

internal fun JsonElement?.numeric(): Double? = stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() }
internal fun JsonElement?.whole(): Int? = numeric()?.toInt()

object PgaPayload {
    fun decode(encoded: String, limit: Int = 16 * 1024 * 1024): JsonElement {
        require(encoded.isNotEmpty() && encoded.length <= limit * 4L / 3 + 4)
        val bytes = requireNotNull(encoded.decodeBase64()).toByteArray(); require(bytes.size <= limit)
        return parseJson(gunzip(bytes, limit.toLong()))
    }
}

@Serializable data class GolfPerson(val id: String, val name: String, val country: String?, val amateur: Boolean = false)
@Serializable data class GolfRow(val player: GolfPerson, val position: String?, val total: String?, val totalStrokes: Int?, val today: String?, val thru: String?, val round: Int?, val teeTime: Long?, val state: String?, val course: String?, val group: Int?, val backNine: Boolean, val rounds: List<String>, val movement: String?, val movementAmount: String?, val official: Boolean?, val projected: Boolean?) {
    val status: String get() = when (position?.uppercase()) { "CUT" -> "Cut"; "WD", "W/D" -> "Withdrawn"; "DQ" -> "Disqualified"; "MDF" -> "Missed cut"; else -> when (state?.uppercase()) { "ACTIVE" -> "Active"; "COMPLETE", "FINISHED" -> "Finished"; "NOT_STARTED" -> "Not started"; else -> state.orEmpty() } }
    val movementLabel: String? get() {
        val count = movementAmount?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return when (movement?.uppercase()) { "UP" -> "Up $count"; "DOWN" -> "Down $count"; else -> null }
    }
}
@Serializable data class GolfHole(val number: Int, val par: Int?, val yards: Int?, val average: Double?, val averageToPar: String?, val rank: Int?, val eagles: Int?, val birdies: Int?, val pars: Int?, val bogeys: Int?, val doubles: Int?)
@Serializable data class GolfCourse(val id: String, val name: String, val code: String? = null, val host: Boolean = false, val par: Int? = null, val yards: Int? = null, val holes: List<GolfHole> = emptyList())
@Serializable data class GolfTournament(val id: String, val name: String, val logo: String?, val location: String?, val timezone: String?, val year: Int?, val displayDate: String?, val tournamentStatus: String?, val roundStatus: String?, val statusDisplay: String?, val roundDisplay: String?, val round: Int?, val format: String?, val courses: List<GolfCourse>, val weather: JsonElement?, val headshotBase: String?, val site: String?) {
    val status: String get() = status(tournamentStatus, roundStatus)
    val live: Boolean get() = status in setOf("Live", "Suspended")
    val strokePlay: Boolean get() = format?.uppercase() in setOf("STROKE_PLAY", "STROKEPLAY", "INDIVIDUAL_STROKE_PLAY")
    companion object {
        fun status(tournament: String?, round: String?): String {
            val t = tournament?.uppercase().orEmpty(); val r = round?.uppercase().orEmpty().ifBlank { t }
            return when { "CANCEL" in r -> "Cancelled"; "SUSPEND" in r || "DELAY" in r -> "Suspended"; "COMPLETE" in t || "OFFICIAL" in t || t == "FINAL" -> "Final"; "COMPLETE" in r || r in setOf("OFFICIAL", "FINAL") -> "Round complete"; listOf("PROGRESS", "LIVE", "ACTIVE").any { it in r } -> "Live"; listOf("NOT_STARTED", "NOTSTARTED", "SCHEDULED", "UPCOMING").any { it in r } -> "Upcoming"; else -> r.ifBlank { "Unknown" } }
        }
    }
}
data class GolfSchedule(val id: String, val name: String, val date: String?, val status: String?, val purse: String?, val points: String?, val champion: String?, val course: String?, val location: String?)
data class GolfHoleScore(val hole: Int, val par: Int?, val yards: Int?, val strokes: Int?, val status: String?, val sequence: Int?) {
    val delta: Int? get() = strokes?.takeIf { it > 0 }?.let { n -> par?.let { n - it } }
}
data class GolfRoundScore(val round: Int, val course: String?, val total: Int?, val scoreToPar: String?, val holes: List<GolfHoleScore>)
data class GolfTeeGroup(val round: Int, val group: Int, val teeTime: Long?, val startingTee: Int?, val backNine: Boolean, val players: List<GolfPerson>)
data class GolfPoint(val x: Double, val y: Double)
data class GolfShot(val hole: Int, val stroke: Int, val description: String?, val distance: String?, val remaining: String?, val type: String?, val from: String?, val to: String?, val final: Boolean, val leftFrom: GolfPoint?, val leftTo: GolfPoint?, val bottomFrom: GolfPoint?, val bottomTo: GolfPoint?)
data class GolfShotHole(val hole: Int, val par: Int?, val yards: Int?, val score: String?, val status: String?, val strokes: List<GolfShot>)

object PgaMapper {
    fun person(value: JsonElement?): GolfPerson? {
        val id = value["id"].stringOrNull() ?: return null
        val name = value["displayName"].stringOrNull() ?: listOf(value["firstName"].string(), value["lastName"].string()).joinToString(" ").trim()
        return GolfPerson(id, name.ifBlank { "Player $id" }, value["country"].stringOrNull(), value["amateur"].bool())
    }
    fun leaderboard(raw: JsonElement): List<GolfRow> = raw["players"].array.mapNotNull { row ->
        val p = person(row["player"]) ?: return@mapNotNull null; val s = row["scoringData"]
        GolfRow(p, s["position"].stringOrNull(), s["total"].stringOrNull(), s["totalStrokes"].whole(), s["score"].stringOrNull(), s["thru"].stringOrNull(), s["currentRound"].whole(), s["teeTime"].numeric()?.takeIf { it > 0 }?.toLong(), s["playerState"].stringOrNull(), s["courseId"].stringOrNull(), s["groupNumber"].whole(), s["backNine"].bool(), s["rounds"].array.map { it.string("–") }, s["movementDirection"].stringOrNull(), s["movementAmount"].stringOrNull(), s["official"]?.let { it.bool() }, s["projected"]?.let { it.bool() })
    }.distinctBy { it.player.id }
    fun merge(previous: List<GolfRow>, fresh: List<GolfRow>): List<GolfRow> = if (fresh.isEmpty()) previous else fresh + previous.filter { old -> fresh.none { it.player.id == old.player.id } }
    fun tournaments(raw: JsonElement): List<GolfTournament> = raw["tournaments"].array.mapNotNull { t ->
        val id = t["id"].stringOrNull() ?: return@mapNotNull null
        GolfTournament(id, t["tournamentName"].string(), t["tournamentLogo"].stringOrNull(), t["tournamentLocation"].stringOrNull(), t["timezone"].stringOrNull(), t["seasonYear"].whole(), t["displayDate"].stringOrNull(), t["tournamentStatus"].stringOrNull(), t["roundStatus"].stringOrNull(), t["roundStatusDisplay"].stringOrNull(), t["roundDisplay"].stringOrNull(), t["currentRound"].whole(), t["formatType"].stringOrNull(), t["courses"].array.mapNotNull { c -> c["id"].stringOrNull()?.let { GolfCourse(it, c["courseName"].string(), c["courseCode"].stringOrNull(), c["hostCourse"].bool()) } }, t["weather"], t["headshotBaseUrl"].stringOrNull(), t["tournamentSiteURL"].stringOrNull())
    }
    fun courses(raw: JsonElement?): List<GolfCourse> = raw["courses"].array.mapNotNull { c ->
        val id = c["courseId"].stringOrNull() ?: return@mapNotNull null
        val holes = c["roundHoleStats"].array.lastOrNull { it["holeStats"].array.isNotEmpty() }?.get("holeStats").array.filter { it["__typename"].string() == "CourseHoleStats" }.mapNotNull { h -> h["courseHoleNum"].whole()?.let { GolfHole(it, h["parValue"].whole(), h["yards"].whole(), h["scoringAverage"].numeric(), h["scoringAverageDiff"].stringOrNull(), h["rank"].whole(), h["eagles"].whole(), h["birdies"].whole(), h["pars"].whole(), h["bogeys"].whole(), h["doubleBogey"].whole()) } }.sortedBy { it.number }
        GolfCourse(id, c["courseName"].stringOrNull() ?: c["courseOverview"]["name"].string(), c["courseCode"].stringOrNull(), c["hostCourse"].bool(), c["par"].whole(), c["yardage"].whole(), holes)
    }
    fun scorecard(raw: JsonElement): List<GolfRoundScore> = raw["roundScores"].array.mapNotNull { r ->
        val round = r["roundNumber"].whole() ?: return@mapNotNull null
        val holes = (r["firstNine"]["holes"].array + r["secondNine"]["holes"].array).mapNotNull { h -> h["holeNumber"].whole()?.let { GolfHoleScore(it, h["par"].whole(), h["yardage"].whole(), h["score"].whole(), h["status"].stringOrNull(), h["sequenceNumber"].whole()) } }.distinctBy { it.hole }.sortedBy { it.hole }
        GolfRoundScore(round, r["courseName"].stringOrNull(), r["total"].whole(), r["scoreToPar"].stringOrNull(), holes)
    }
    fun teeTimes(raw: JsonElement): List<GolfTeeGroup> = raw["rounds"].array.flatMap { round -> round["groups"].array.map { group -> GolfTeeGroup(round["roundInt"].int(), group["groupNumber"].int(), group["teeTime"].numeric()?.takeIf { it > 0 }?.toLong(), group["startTee"].whole(), group["backNine"].bool(), group["players"].array.mapNotNull(::person)) } }
    private fun point(raw: JsonElement?): GolfPoint? { val x = raw["x"].numeric() ?: return null; val y = raw["y"].numeric() ?: return null; return GolfPoint(x, y) }
    fun shots(raw: JsonElement): List<GolfShotHole> = raw["holes"].array.mapNotNull { hole ->
        val number = hole["holeNumber"].whole() ?: return@mapNotNull null
        GolfShotHole(number, hole["par"].whole(), hole["yardage"].whole(), hole["score"].stringOrNull(), hole["status"].stringOrNull(), hole["strokes"].array.mapNotNull { stroke ->
            val n = stroke["strokeNumber"].whole() ?: return@mapNotNull null; val o = stroke["overview"]
            GolfShot(number, n, stroke["playByPlay"].stringOrNull(), stroke["distance"].stringOrNull(), stroke["distanceRemaining"].stringOrNull(), stroke["strokeType"].stringOrNull(), stroke["fromLocation"].stringOrNull(), stroke["toLocation"].stringOrNull(), stroke["finalStroke"].bool(), point(o["leftToRightCoords"]["fromCoords"]), point(o["leftToRightCoords"]["toCoords"]), point(o["bottomToTopCoords"]["fromCoords"]), point(o["bottomToTopCoords"]["toCoords"]))
        }.distinctBy { it.stroke })
    }
    fun cup(raw: JsonElement?): List<JsonElement> { val projected = raw["projectedPlayers"].array.filter { it["__typename"].string() == "TourCupCombinedPlayer" }; return projected.ifEmpty { raw["officialPlayers"].array.filter { it["__typename"].string() == "TourCupCombinedPlayer" } } }
}
