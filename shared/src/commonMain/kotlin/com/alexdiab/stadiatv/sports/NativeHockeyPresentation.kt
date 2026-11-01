package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement

/** Pure NHLGamePresentation + NHLGameMapper builders, not season or estimated player statistics. */
object NativeHockeyPresentation {
    private val fields = linkedMapOf("goals" to "G", "assists" to "A", "points" to "PTS", "plusMinus" to "+/−", "pim" to "PIM",
        "sog" to "SOG", "hits" to "Hits", "blockedShots" to "Blocks", "powerPlayGoals" to "PPG", "faceoffWinningPctg" to "FO%",
        "toi" to "TOI", "shifts" to "Shifts", "giveaways" to "Giveaways", "takeaways" to "Takeaways", "saves" to "Saves",
        "shotsAgainst" to "SA", "goalsAgainst" to "GA", "savePctg" to "SV%", "saveShotsAgainst" to "SV/SA",
        "evenStrengthShotsAgainst" to "EV SA", "powerPlayShotsAgainst" to "PP SA", "shorthandedShotsAgainst" to "SH SA")
    private fun local(value: JsonElement?): String = value["default"].stringOrNull() ?: value.string()
    fun statValue(key: String, raw: String): String {
        val number = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return raw
        return when (key) {
            "faceoffWinningPctg", "shootingPctg" -> com.alexdiab.stadiatv.foundation.decimal(number * 100, 1) + "%"
            "savePctg" -> com.alexdiab.stadiatv.foundation.decimal(number, 3).removePrefix("0")
            else -> raw
        }
    }

    fun players(box: JsonElement?, roster: Map<String, JsonElement>, match: Match): List<HockeyPlayerStats> =
        listOf("awayTeam" to match.away.abbreviation, "homeTeam" to match.home.abbreviation).flatMap { (side, abbreviation) ->
            listOf("forwards" to "Forwards", "defense" to "Defensemen", "goalies" to "Goalies").flatMap { (key, group) ->
                box.path("playerByGameStats", side, key).array.mapNotNull { row ->
                    val id = row["playerId"].stringOrNull()?.takeIf { it.toLongOrNull()?.let { it > 0 } == true } ?: return@mapNotNull null
                    val person = roster[id] ?: row
                    val name = listOf(local(person["firstName"]), local(person["lastName"])).filter(String::isNotBlank).joinToString(" ")
                        .ifBlank { local(row["name"]).ifBlank { "Unknown player" } }
                    val athlete = Athlete("nhl:$id", name, row["sweaterNumber"].string(person["sweaterNumber"].string()), row["position"].string(person["positionCode"].string()), person["headshot"].stringOrNull())
                    HockeyPlayerStats(athlete, abbreviation, group, fields.mapNotNull { (key, label) -> row[key].stringOrNull()?.let { HockeyStat(key, label, statValue(key, it)) } })
                }
            }
        }.distinctBy { it.player.id }

    fun leaders(players: List<HockeyPlayerStats>, match: Match): List<HockeyLeader> {
        fun value(player: HockeyPlayerStats, key: String) = player.stats.firstOrNull { it.key == key }?.value
        fun number(player: HockeyPlayerStats, key: String) = value(player, key)?.toIntOrNull() ?: 0
        fun skater(team: String): HockeyLeader? {
            val best = players.filter { it.team == team && it.group != "Goalies" }.sortedWith(
                compareByDescending<HockeyPlayerStats> { number(it, "points") }.thenByDescending { number(it, "goals") }.thenByDescending { number(it, "sog") }).firstOrNull() ?: return null
            if (number(best, "points") <= 0) return null
            return HockeyLeader(best.player, team, "${value(best, "goals") ?: "–"} G · ${value(best, "assists") ?: "–"} A")
        }
        fun goalie(team: String): HockeyLeader? {
            val best = players.filter { it.team == team && it.group == "Goalies" }.maxByOrNull { number(it, "shotsAgainst") } ?: return null
            if (number(best, "shotsAgainst") <= 0) return null
            return HockeyLeader(best.player, team, listOfNotNull("${value(best, "saves") ?: "–"} SV", value(best, "savePctg")).joinToString(" · "), "GOALIE")
        }
        return listOfNotNull(skater(match.away.abbreviation), skater(match.home.abbreviation), goalie(match.away.abbreviation), goalie(match.home.abbreviation))
    }

    /** Missing total categories may be aggregated only from actual supplied player fields; no zero fill. */
    fun teamStats(reported: List<StatComparison>, root: JsonElement, players: List<HockeyPlayerStats>, match: Match): List<StatComparison> {
        val result = reported.toMutableList()
        val awayShots = root["awayTeam"]["sog"].stringOrNull(); val homeShots = root["homeTeam"]["sog"].stringOrNull()
        if (awayShots != null && homeShots != null) {
            result.removeAll { it.id == "sog" }; result.add(0, StatComparison("Shots on goal", awayShots, homeShots, "sog"))
        }
        for ((key, label) in listOf("hits" to "Hits", "blockedShots" to "Blocked shots", "pim" to "Penalty minutes", "giveaways" to "Giveaways", "takeaways" to "Takeaways")) {
            if (result.any { it.id == key }) continue
            fun sum(team: String): String? = players.filter { it.team == team }.mapNotNull { it.stats.firstOrNull { it.key == key }?.value?.toIntOrNull() }
                .takeIf { it.isNotEmpty() }?.sum()?.toString()
            val a = sum(match.away.abbreviation); val h = sum(match.home.abbreviation)
            if (a != null && h != null) result += StatComparison(label, a, h, key, lowerIsBetter = key == "giveaways")
        }
        return result
    }

    fun keyStats(stats: List<StatComparison>): List<StatComparison> {
        val byId = stats.associateBy { it.id }
        return listOf("sog", "faceoffWinningPctg", "powerPlay", "hits", "blockedShots", "giveaways", "takeaways").mapNotNull { id ->
            byId[id]?.let { stat -> stat.copy(lowerIsBetter = id == "giveaways", awaySecondary = if (id == "powerPlay") byId["powerPlayPctg"]?.away else stat.awaySecondary,
                homeSecondary = if (id == "powerPlay") byId["powerPlayPctg"]?.home else stat.homeSecondary) }
        }
    }
}
