package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement

/** BasketballGamePresentation: canonical views share the same league-independent builders. */
object NativeBasketballPresentation {
    /** Missing opening score is not an invented 0–0 tie. Corrections/non-baskets may change the lead too. */
    fun leadChanges(plays: List<Play>): List<Play> {
        var previous: Int? = null
        val events = mutableListOf<Play>()
        plays.asReversed().sortedWith(compareBy<Play> { it.periodNumber }.thenBy { it.sortOrder }).forEach { play ->
            val away = play.awayScore?.toIntOrNull() ?: return@forEach
            val home = play.homeScore?.toIntOrNull() ?: return@forEach
            val leader = away.compareTo(home)
            if (previous != null && previous != leader) events += play
            previous = leader
        }
        return events.takeLast(6)
    }
    fun leadHeadline(play: Play, match: Match): String? {
        val away = play.awayScore?.toIntOrNull() ?: return null; val home = play.homeScore?.toIntOrNull() ?: return null
        return when { away == home -> "Game tied"; away > home -> "${match.away.abbreviation} takes the lead"; else -> "${match.home.abbreviation} takes the lead" }
    }
    fun leaders(game: JsonElement, config: NativeBasketballLeague): List<BasketballLeader> = listOf("away" to "awayLeaders", "home" to "homeLeaders").mapNotNull { (side, key) ->
        val team = game["${side}Team"]
        val combined = game["gameLeaders"][key]
        val row = combined?.takeIf { NativeBasketballMapping.id(it["personId"]) != null && !it["name"].stringOrNull().isNullOrBlank() }
            ?: team["players"].array.filter { it["played"].bool((NativeBasketballDuration.seconds(it["statistics"]["minutes"].string()) ?: 0.0) > 0) }
                .filter { it["statistics"]["points"].stringOrNull()?.toDoubleOrNull()?.let { n -> n.isFinite() && n > 0 } == true }
                .maxByOrNull { it["statistics"]["points"].double() } ?: return@mapNotNull null
        val id = NativeBasketballMapping.id(row["personId"]) ?: return@mapNotNull null
        val name = row["name"].stringOrNull() ?: listOf(row["firstName"].string(), row["familyName"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val isCombined = row === combined
        if (row["teamTricode"].stringOrNull()?.let { !TeamIdentity.sameAbbreviation(config.league.path, it, team["teamTricode"].string()) } == true) return@mapNotNull null
        val stats = if (isCombined) row else row["statistics"]
        val line = (if (isCombined) listOf("points" to "PTS", "rebounds" to "REB", "assists" to "AST") else listOf("points" to "PTS"))
            .mapNotNull { (stat, label) -> number(stats[stat])?.let { "$it $label" } }.joinToString(" · ").takeIf(String::isNotBlank) ?: return@mapNotNull null
        BasketballLeader(Athlete("${config.prefix}:$id", name, row["jerseyNum"].string(), row["position"].string(), NativeBasketballProvider.headshot(id, config)), team["teamTricode"].string(), line)
    }
    fun number(raw: JsonElement?): String? {
        val number = raw.stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in -1_000_000.0..1_000_000.0 } ?: return null
        return number.toLong().toString()
    }
    fun keyStats(all: List<StatComparison>): List<StatComparison> = listOf("fieldGoalsPercentage", "threePointersPercentage", "reboundsTotal", "assists", "turnovers", "pointsInThePaint")
        .mapNotNull { id -> all.firstOrNull { it.id == id } }

    /** Prefer the native sentence; compose only from fields actually present when it is absent. */
    fun play(raw: JsonElement): BasketballPlayDetail {
        val action = raw["actionType"].string().lowercase(); val sub = raw["subType"].string().lowercase()
        val player = (raw["playerName"] ?: raw["playerNameI"]).stringOrNull()?.takeIf(String::isNotBlank)
        val team = raw["teamTricode"].stringOrNull()?.takeIf(String::isNotBlank)
        val who = player ?: team
        val period = raw["period"].int()
        val shot = action.startsWith("2pt") || action.startsWith("3pt")
        val made = raw["shotResult"].string().equals("Made", true)
        val boundary = action == "period" && sub in setOf("start", "end")
        val gameEnd = action in setOf("gameend", "game end") || action == "game" && sub == "end"
        val technical = action == "foul" && ("technical" in sub || "flagrant" in sub)
        val priority = when {
            shot && made || technical || gameEnd -> BasketballPlayPriority.High
            shot || action in setOf("freethrow", "block", "steal", "timeout", "instantreplay", "replay") || boundary ||
                action == "foul" && ("shooting" in sub || "offensive" in sub) || action == "turnover" && ("shot clock" in sub || "offensive foul" in sub) -> BasketballPlayPriority.Medium
            else -> BasketballPlayPriority.Compact
        }
        val title = raw["description"].stringOrNull()?.takeIf(String::isNotBlank) ?: when {
            shot -> {
                val distance = raw["shotDistance"].stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it < 150 }
                listOfNotNull(who, if (made) "makes" else "misses", distance?.let { "a ${kotlin.math.round(it).toInt()}-foot shot" } ?: "a shot").joinToString(" ")
            }
            boundary -> if (sub == "start") "Start of ${NativeBasketballDuration.period(period)}" else if (period == 2) "Halftime" else "End of ${NativeBasketballDuration.period(period)}"
            gameEnd -> "Final"
            action in setOf("replay", "instantreplay") -> "Replay review"
            action == "substitution" -> listOfNotNull("Substitution", who).joinToString(": ")
            action == "foul" -> listOfNotNull(who, (raw["subType"].stringOrNull() ?: "Personal").replaceFirstChar(Char::uppercase) + " foul").joinToString(" — ")
            else -> listOfNotNull(who, action.replace("freethrow", "free throw").replace("jumpball", "jump ball").replace('_', ' ').replace('-', ' ').takeIf(String::isNotBlank) ?: "Game event").joinToString(" ").replaceFirstChar(Char::uppercase)
        }
        val subtitle = listOfNotNull(
            (raw["assistPlayerNameInitial"] ?: raw["assistPlayerName"]).stringOrNull()?.let { "Assist: $it" },
            raw["blockPlayerName"].stringOrNull()?.let { "Blocked by $it" }, raw["stealPlayerName"].stringOrNull()?.let { "Steal: $it" },
            team.takeIf { player != null },
            if (made && (shot || action == "freethrow")) raw["scoreAway"].stringOrNull()?.let { away -> raw["scoreHome"].stringOrNull()?.let { home -> "$away–$home" } } else null,
        ).joinToString(" · ").takeIf(String::isNotBlank)
        return BasketballPlayDetail(title, subtitle, priority, boundary)
    }
}
