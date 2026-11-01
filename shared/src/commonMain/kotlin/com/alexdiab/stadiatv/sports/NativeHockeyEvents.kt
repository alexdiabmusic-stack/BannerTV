package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/** Translation of NHLPlayMapper / HockeyPlayDescriptionBuilder. All identities stay NHL-scoped. */
object NativeHockeyEvents {
    fun scoringTimeline(data: GameCentre): List<Play> =
        (data.hockey?.scoringSummary?.takeIf { it.isNotEmpty() } ?: data.plays).filter { it.isScoring }.asReversed()

    private val codeKeys = mapOf(502 to "faceoff", 503 to "hit", 504 to "giveaway", 505 to "goal", 506 to "shot-on-goal",
        507 to "missed-shot", 508 to "blocked-shot", 509 to "penalty", 516 to "stoppage", 520 to "period-start",
        521 to "period-end", 523 to "shootout-complete", 524 to "game-end", 525 to "takeaway", 535 to "delayed-penalty", 537 to "failed-shot-attempt")
    private val shotKinds = setOf("goal", "shot-on-goal", "missed-shot", "blocked-shot", "failed-shot-attempt")
    private val names = mapOf("delaying-game" to "Delay of game", "delaying-game-puck-over-glass" to "Delay of game — puck over glass",
        "high-sticking" to "High-sticking", "high-sticking-double-minor" to "High-sticking (double minor)", "cross-checking" to "Cross-checking",
        "too-many-men-on-the-ice" to "Too many players on the ice", "wrist" to "Wrist shot", "snap" to "Snap shot", "slap" to "Slap shot",
        "tip-in" to "Tip-in", "backhand" to "Backhand", "wrap-around" to "Wrap-around", "O" to "Offensive zone", "D" to "Defensive zone",
        "N" to "Neutral zone", "wide-right" to "Wide right", "wide-left" to "Wide left", "above-crossbar" to "Above the crossbar")

    fun humanize(raw: String): String = names[raw] ?: raw.replace('-', ' ').replaceFirstChar(Char::uppercase)
    fun localized(raw: JsonElement?): String = raw["default"].stringOrNull() ?: raw.string()
    private fun id(raw: JsonElement?): String? = raw.stringOrNull()?.takeIf { it.matches(Regex("[0-9]{1,12}")) }
    private fun count(raw: JsonElement?): Int? = raw.string().toIntOrNull()?.takeIf { it >= 0 }
    private fun web(raw: JsonElement?): String? = raw.stringOrNull()?.takeIf { value ->
        safeWebURL(value, false)
    }
    fun kind(raw: JsonElement): String = raw["typeDescKey"].stringOrNull() ?: codeKeys[raw["typeCode"].int()] ?: "unknown"

    fun periodLabel(raw: JsonElement?): String {
        val number = count(raw["number"]) ?: 0
        return when (raw["periodType"].string()) {
            "SO" -> "Shootout"
            "OT" -> (number - (count(raw["maxRegulationPeriods"]) ?: 3)).coerceAtLeast(1).let { if (it == 1) "OT" else "${it}OT" }
            "REG" -> listOf("1st period", "2nd period", "3rd period").getOrNull(number - 1) ?: "Period $number"
            else -> if (number > 0) "Period $number" else "Pregame"
        }
    }

    /** Rotate BOTH axes to home-defends-left, never reflect x alone. Reject missing/out-of-rink coordinates. */
    fun location(raw: JsonElement): HockeyLocation? {
        val x = raw.path("details", "xCoord").double(Double.NaN)
        val y = raw.path("details", "yCoord").double(Double.NaN)
        if (!x.isFinite() || !y.isFinite() || abs(x) > 100 || abs(y) > 42.5) return null
        val side = raw["homeTeamDefendingSide"].string()
        val rotation = if (side == "right") -1 else 1
        return HockeyLocation((x * rotation).toFloat(), (y * rotation).toFloat(), side in setOf("left", "right"))
    }

    private fun player(raw: JsonElement?, roster: Map<String, HockeyPlayer>): HockeyPlayer? {
        val key = id(raw["playerId"]) ?: return null
        val full = listOf(localized(raw["firstName"]), localized(raw["lastName"])).filter(String::isNotBlank).joinToString(" ")
        val existing = roster[key]
        return HockeyPlayer(key, full.ifBlank { localized(raw["name"]).ifBlank { existing?.name ?: "Unknown player" } }, web(raw["headshot"]) ?: existing?.headshot)
    }

    fun plays(root: JsonElement, landing: JsonElement?, match: Match): List<Play> {
        val roster = root["rosterSpots"].array.mapNotNull { player(it, emptyMap()) }.associateBy { it.id }
        val goals = landing.path("summary", "scoring").array.flatMap { it["goals"].array }.mapNotNull { goal -> id(goal["eventId"])?.let { it to goal } }.toMap()
        val away = root.path("awayTeam", "id").string(); val home = root.path("homeTeam", "id").string()
        val game = root["id"].string()
        // Deduplicate before counting attempts. Stable event IDs allow later corrections without duplicates.
        val ordered = root["plays"].array.mapIndexed { index, raw -> (raw["sortOrder"].string().toIntOrNull() ?: index) to raw }
            .associateBy { (order, raw) -> id(raw["eventId"]) ?: "missing:$order" }.values.sortedBy { it.first }
        val attempts = mutableMapOf<String, Int>()
        return ordered.map { (order, raw) ->
            val details = raw["details"]; val key = kind(raw); val goal = goals[id(raw["eventId"])]
            val owner = id(details["eventOwnerTeamId"])
            val team = when { owner == null -> null; away.isNotBlank() && owner == away -> match.away.teamId; home.isNotBlank() && owner == home -> match.home.teamId; else -> null }
            fun resolve(field: String): HockeyPlayer? = id(details[field])?.let { roster[it] ?: HockeyPlayer(it, "Unknown player") }
            val keys = when (key) {
                "goal" -> listOf("scoringPlayerId", "assist1PlayerId", "assist2PlayerId")
                "shot-on-goal", "missed-shot", "failed-shot-attempt" -> listOf("shootingPlayerId", "goalieInNetId")
                "blocked-shot" -> listOf("shootingPlayerId", "blockingPlayerId")
                "hit" -> listOf("hittingPlayerId", "hitteePlayerId")
                "faceoff" -> listOf("winningPlayerId", "losingPlayerId")
                "penalty" -> listOf("committedByPlayerId", "drawnByPlayerId", "servedByPlayerId")
                else -> listOf("playerId")
            }
            val primary = if (key == "goal") player(goal, roster) ?: resolve(keys[0]) else resolve(keys[0])
            val secondary = keys.getOrNull(1)?.let(::resolve); val tertiary = keys.getOrNull(2)?.let(::resolve)
            val assists = if (key != "goal") emptyList() else if (goal["assists"] != null) goal["assists"].array.mapNotNull { player(it, roster) } else listOfNotNull(secondary, tertiary)
            val period = periodLabel(raw["periodDescriptor"])
            val shootout = period == "Shootout" && key in shotKinds
            val round = if (shootout && owner in setOf(away, home) && owner != null) ((attempts[owner] ?: 0) + 1).also { attempts[owner] = it } else null
            val strength = if (key == "goal" && !shootout) strength(goal, raw["situationCode"].stringOrNull(), owner, away, home) else null
            val (title, subtitle) = describe(key, primary?.name ?: "Unknown player", secondary?.name, tertiary?.name, assists, details, period, strength)
            val totals = goal["assists"].array.mapNotNull { assist -> id(assist["playerId"])?.let { who -> count(assist["assistsToDate"])?.let { who to it } } }.toMap()
            val event = HockeyEvent(key, title, subtitle, period, primary, assists, strength, round,
                if (key == "goal" && !shootout) count(goal["goalsToDate"]) ?: count(details["scoringPlayerTotal"]) else null, totals, location(raw))
            Play("nhl:$game:${id(raw["eventId"]) ?: "missing:$order"}", period, raw["timeInPeriod"].string(), listOfNotNull(title, subtitle).joinToString(" · "), team,
                if (shootout) null else details["awayScore"].stringOrNull() ?: goal["awayScore"].stringOrNull(),
                if (shootout) null else details["homeScore"].stringOrNull() ?: goal["homeScore"].stringOrNull(), key == "goal",
                label(key, shootout), raw.path("periodDescriptor", "number").int(), web(goal["highlightClipSharingUrl"]), sortOrder = order.toDouble(), hockeyEvent = event)
        }.asReversed()
    }

    /** Landing-only fallback, not fabricated play-by-play. Used only when a complete event feed is unavailable. */
    fun summary(landing: JsonElement?, match: Match): List<Play> {
        val game = landing["id"].stringOrNull() ?: return emptyList()
        return landing.path("summary", "scoring").array.flatMap { group -> group["goals"].array.map { group["periodDescriptor"] to it } }
            .mapIndexed { index, (periodRaw, goal) ->
                val primary = player(goal, emptyMap()); val assists = goal["assists"].array.mapNotNull { player(it, emptyMap()) }
                val period = periodLabel(periodRaw); val shootout = period == "Shootout"
                val strength = if (shootout) null else strength(goal, goal["situationCode"].stringOrNull(), null, "", "")
                val (title, subtitle) = describe("goal", primary?.name ?: "Unknown player", null, null, assists, goal, period, strength)
                val team = localized(goal["teamAbbrev"]).takeIf(String::isNotBlank)?.let { abbreviation ->
                    listOf(match.away, match.home).firstOrNull { NativeGameInsights.sameAbbreviation(it.abbreviation, abbreviation) }?.teamId
                }
                val totals = goal["assists"].array.mapNotNull { a -> id(a["playerId"])?.let { who -> count(a["assistsToDate"])?.let { who to it } } }.toMap()
                Play("nhl:$game:summary:${id(goal["eventId"]) ?: index}", period, goal["timeInPeriod"].string(), listOfNotNull(title, subtitle).joinToString(" · "), team,
                    if (shootout) null else goal["awayScore"].stringOrNull(), if (shootout) null else goal["homeScore"].stringOrNull(), true, label("goal", shootout), periodRaw["number"].int(),
                    web(goal["highlightClipSharingUrl"]), sortOrder = index.toDouble(), hockeyEvent = HockeyEvent("goal", title, subtitle, period, primary, assists, strength,
                        seasonGoals = if (shootout) null else count(goal["goalsToDate"]), assistSeasonTotals = totals))
            }.distinctBy { it.id }.asReversed()
    }

    private fun strength(goal: JsonElement?, code: String?, owner: String?, away: String, home: String): String? {
        val explicit = mapOf("pp" to "Power-play goal", "sh" to "Short-handed goal", "ev" to "Even-strength goal")[goal["strength"].string()]
        if (goal["goalModifier"].string() in setOf("empty-net", "en")) return "Empty-net goal"
        val situation = NativeHockey.situation(code)
        if (owner != null && owner in setOf(away, home) && situation != null) {
            val scoringHome = owner == home
            if (if (scoringHome) situation.awayGoaliePulled == true else situation.homeGoaliePulled == true) return "Empty-net goal"
            return explicit ?: if (scoringHome) "${situation.homeSkaters}-on-${situation.awaySkaters}" else situation.strength
        }
        return explicit
    }

    private fun label(key: String, shootout: Boolean) = if (shootout) { if (key == "goal") "Shootout goal" else "Shootout attempt" } else when (key) {
        "shot-on-goal" -> "Shot"; "failed-shot-attempt" -> "Shot attempt"; else -> humanize(key)
    }

    private fun describe(key: String, primary: String, secondary: String?, tertiary: String?, assists: List<HockeyPlayer>, details: JsonElement?, period: String, strength: String?): Pair<String, String?> {
        val shot = details["shotType"].stringOrNull()?.let(::humanize); val zone = details["zoneCode"].stringOrNull()?.let(::humanize)
        fun join(vararg values: String?) = values.filterNotNull().filter(String::isNotBlank).joinToString(" · ").takeIf(String::isNotBlank)
        if (period == "Shootout" && key in shotKinds) return primary to when (key) { "goal" -> "Goal"; "shot-on-goal" -> "Saved"; else -> "Missed" }
        return when (key) {
            "goal" -> "$primary scores" to join(shot, if (assists.isEmpty()) "Unassisted" else "Assisted by ${assists.joinToString(", ") { it.name }}", strength)
            "shot-on-goal" -> ("$primary shot saved" + (secondary?.let { " by $it" } ?: "")) to join(shot, zone)
            "missed-shot" -> "$primary misses the net" to join(shot, details["reason"].stringOrNull()?.let(::humanize), zone)
            "blocked-shot" -> "${secondary ?: "Unknown player"} blocks $primary's shot" to shot
            "hit" -> "$primary hits ${secondary ?: "Unknown player"}" to zone
            "faceoff" -> "$primary wins faceoff vs. ${secondary ?: "Unknown player"}" to zone
            "penalty" -> "${if (id(details["committedByPlayerId"]) == null) "Bench" else primary} — ${humanize(details["descKey"].string("penalty"))}" to
                join(count(details["duration"])?.let { "$it min" }, secondary?.let { "Drawn by $it" }, tertiary?.let { "Served by $it" })
            "giveaway" -> "$primary giveaway" to zone
            "takeaway" -> "$primary takeaway" to zone
            "stoppage" -> "Stoppage" to details["reason"].stringOrNull()?.let(::humanize)
            "period-start" -> "Start of $period" to null
            "period-end" -> "End of $period" to null
            "game-end" -> "Final" to null
            "shootout-complete" -> "Shootout complete" to null
            "delayed-penalty" -> "Delayed penalty" to null
            "failed-shot-attempt" -> "$primary shot attempt unsuccessful" to join(shot, zone)
            else -> "Game event" to humanize(key)
        }
    }
}
