package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*

/** Mapped only after NativeLaLigaReconciliation.validate. FotMob people NEVER become official Opta IDs. */
object NativeFotMobDetails {
    private fun rows(value: JsonElement?, limit: Int, surface: String): JsonArray = (value as? JsonArray)?.takeIf { it.size <= limit }
        ?: throw IOException("FotMob $surface missing or exceeds bounds")
    private fun integer(value: JsonElement?, max: Int = 200) = value.stringOrNull()?.toIntOrNull()?.takeIf { it in 0..max }
    private fun text(value: JsonElement?, max: Int = 256) = value.stringOrNull()?.trim()?.takeIf { it.isNotBlank() && it.length <= max }
    private fun person(raw: JsonElement?): SportPlayer? {
        val id = raw["id"].string().takeIf(NativeFotMobClient::validId) ?: return null
        val name = text(raw["name"]) ?: listOfNotNull(text(raw["firstName"]), text(raw["lastName"])).joinToString(" ").takeIf(String::isNotBlank) ?: return null
        return SportPlayer("fotmob:$id", name, text(raw["shirtNumber"], 8).orEmpty())
    }
    private fun validate(raw: JsonElement, game: Match, mapping: NativeLaLigaReconciliation.Mapping) = NativeLaLigaReconciliation.validate(raw, mapping, game)
    fun lineups(raw: JsonElement, game: Match, mapping: NativeLaLigaReconciliation.Mapping): List<SoccerLineup> {
        validate(raw, game, mapping)
        val block = raw["content"]["lineup"] as? JsonObject ?: throw IOException("FotMob lineups not published")
        return listOf("homeTeam", "awayTeam").mapNotNull { key ->
            val team = block[key] ?: return@mapNotNull null
            val home = key == "homeTeam"
            if (team["id"].string() != if (home) mapping.fotmobHome else mapping.fotmobAway) throw IOException("FotMob lineup club mismatch")
            val players = listOf("starters", "subs").flatMap { role -> rows(team[role], 64, "lineup $role").map { player ->
                val x = NativeMlsMapping.number(player["verticalLayout"]["x"], 1.0)?.toFloat()
                val y = NativeMlsMapping.number(player["verticalLayout"]["y"], 1.0)?.toFloat()
                val rating = NativeMlsMapping.number(player["performance"]["rating"], 10.0)
                SoccerLineupPlayer(person(player) ?: throw IOException("FotMob lineup person identity missing"), role == "starters",
                    pitchPosition = if (role == "starters" && x != null && y != null) SoccerPitchPosition(x, y) else null,
                    rating = rating, ratingSource = rating?.let { "FotMob Rating" })
            } }
            if (players.map { it.player.id }.distinct().size != players.size) throw IOException("FotMob lineup duplicate person membership")
            if (players.isEmpty()) null else SoccerLineup(home, text(team["formation"], 32), players, text(team["coach"]["name"]))
        }
    }
    fun events(raw: JsonElement, game: Match, mapping: NativeLaLigaReconciliation.Mapping): List<Play> {
        validate(raw, game, mapping)
        val entries = rows(raw["content"]["matchFacts"]["events"]["events"], 2000, "events")
        val mapped = entries.mapIndexedNotNull { ordinal, row ->
            val type = text(row["type"], 80) ?: throw IOException("FotMob event kind missing")
            if (type in setOf("Comment", "AddedTime") || NativeMlsMapping.boolean(row["isPenaltyShootoutEvent"]) == true) return@mapIndexedNotNull null
            val home = NativeMlsMapping.boolean(row["isHome"])
            val club = home?.let { if (it) game.home.teamId else game.away.teamId }
            val minute = integer(row["time"]); val added = integer(row["overloadTime"], 99) ?: integer(row["addedTime"], 99)
            val swap = row["swap"] as? JsonArray
            val primary = if (type == "Substitution") person(swap?.getOrNull(0)) else person(row["player"])
            val secondary = if (type == "Substitution") person(swap?.getOrNull(1)) else {
                val id = row["assistPlayerId"].string().takeIf(NativeFotMobClient::validId)
                val name = text(row["assistInput"])
                if (id != null && name != null) SportPlayer("fotmob:$id", name) else null
            }
            val kind = when (type) {
                "Goal" -> when {
                    NativeMlsMapping.boolean(row["ownGoal"]) == true -> SoccerEventKind.OwnGoal
                    row["goalDescriptionKey"].string() == "penalty" || NativeMlsMapping.boolean(row["penalty"]) == true -> SoccerEventKind.PenaltyGoal
                    else -> SoccerEventKind.Goal
                }
                "Card" -> when (row["card"].string().lowercase()) {
                    "yellow" -> SoccerEventKind.YellowCard; "red" -> SoccerEventKind.RedCard
                    "yellowred", "yellow-red", "secondyellow" -> SoccerEventKind.SecondYellow; else -> SoccerEventKind.Unknown
                }
                "Substitution" -> SoccerEventKind.Substitution
                "Half" -> when (row["halfStrKey"].string().lowercase()) { "halftime", "halftime_short" -> SoccerEventKind.HalfTime; "fulltime", "fulltime_short" -> SoccerEventKind.PeriodEnd; else -> SoccerEventKind.Unknown }
                else -> SoccerEventKind.Unknown
            }
            // Stable source IDs first; synthesized identity never includes a mutable list ordinal or description.
            val reported = row["eventId"].string().takeIf { NativeFotMobClient.validId(it) }
            val stable = reported ?: row["reactKey"].stringOrNull()?.takeIf { it.length <= 256 && it.isNotBlank() } ?: listOf(
                type, minute, added, home, primary?.id, secondary?.id, row["halfStrKey"].string(), row["card"].string()).joinToString("|").let { input ->
                input.encodeUtf8().sha256().hex()
            }
            val label = when (kind) {
                SoccerEventKind.Goal -> "Goal"; SoccerEventKind.OwnGoal -> "Own goal"; SoccerEventKind.PenaltyGoal -> "Penalty goal"
                SoccerEventKind.Substitution -> "Substitution"; SoccerEventKind.YellowCard -> "Yellow card"; SoccerEventKind.SecondYellow -> "Second yellow card"
                SoccerEventKind.RedCard -> "Red card"; SoccerEventKind.HalfTime -> "Half-time"; SoccerEventKind.PeriodEnd -> "Full time"
                else -> listOfNotNull(type, text(row["card"])).distinct().joinToString(" · ")
            }
            val description = if (kind == SoccerEventKind.Substitution) listOfNotNull(primary?.let { "On: ${it.name}" }, secondary?.let { "Off: ${it.name}" }).joinToString(" · ")
                else listOfNotNull(primary?.name, secondary?.let { "Assist: ${it.name}" }, text(row["cardReason"]["defaultText"], 1000), text(row["goalDescription"], 1000)).joinToString(" · ")
            val clock = minute?.let { "$it${added?.takeIf { value -> value > 0 }?.let { value -> "+$value" }.orEmpty()}′" }.orEmpty()
            // No inferred half: added-time first-half events cannot be silently placed in the second half.
            Play("fotmob:${mapping.fotmobId}|$stable", "", clock, description.ifBlank { label }, club, null, null,
                kind in setOf(SoccerEventKind.Goal, SoccerEventKind.OwnGoal, SoccerEventKind.PenaltyGoal), label,
                sortOrder = ordinal.toDouble(), soccerEvent = SoccerEvent(kind, primary, secondary, minute, rawPeriod = ""))
        }.also { if (it.map { play -> play.id }.distinct().size != it.size) throw IOException("FotMob event identities are ambiguous") }
        // A mutable array ordinal is not a clock. Order reported minutes/added time explicitly,
        // retaining source order for ties. HT/FT close their reported minute AFTER stoppage events:
        // the real final feed reports FT at 90, after a card at 90+7. Never guess a missing half/time.
        val chronological = if (mapped.all { it.soccerEvent?.minute != null }) mapped.sortedWith(
            compareBy<Play> { it.soccerEvent!!.minute }
                .thenBy { if (it.soccerEvent!!.kind in setOf(SoccerEventKind.HalfTime, SoccerEventKind.PeriodEnd)) 1 else 0 }
                .thenBy { it.clock.substringAfter('+', "0").removeSuffix("′").toIntOrNull() ?: 0 }
                .thenBy { it.sortOrder }
        ) else mapped
        return chronological.mapIndexed { index, play -> play.copy(sortOrder = index.toDouble()) }.asReversed()
    }
    fun statistics(raw: JsonElement, game: Match, mapping: NativeLaLigaReconciliation.Mapping): List<StatComparison> {
        validate(raw, game, mapping)
        val sections = rows(raw["content"]["stats"]["Periods"]["All"]["stats"], 64, "statistic sections")
        val result = LinkedHashMap<String, StatComparison>()
        for (section in sections) for (row in rows(section["stats"], 128, "statistic rows")) {
            val key = text(row["key"], 128) ?: continue
            val values = rows(row["stats"], 2, "statistic values")
            if (values.size != 2) throw IOException("FotMob statistic side count missing")
            fun value(raw: JsonElement?): String? {
                val token = raw.stringOrNull()?.takeIf { it.length <= 64 } ?: return null
                val match = Regex("^(-?[0-9]+(?:\\.[0-9]+)?)(?:\\s*\\([0-9]+(?:\\.[0-9]+)?%\\))?$").matchEntire(token) ?: return null
                val number = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1_000_000.0 } ?: return null
                if (key == "BallPossesion" && number > 100) return null
                return NativeMlsMapping.text(number) + if (key == "BallPossesion") "%" else ""
            }
            val h = value(values[0]); val a = value(values[1]); if (h == null && a == null) continue
            val label = labels[key] ?: text(row["title"], 128) ?: NativeMlsDetails.humanize(key)
            val entry = StatComparison(label, a ?: "–", h ?: "–")
            if (result[key]?.let { it != entry } == true) throw IOException("FotMob statistic key has conflicting values")
            result[key] = entry
        }
        return result.values.toList()
    }
    private val labels = mapOf("BallPossesion" to "Possession", "expected_goals" to "Expected goals", "expected_goals_on_target" to "Expected goals on target",
        "total_shots" to "Shots", "ShotsOnTarget" to "Shots on target", "ShotsOffTarget" to "Shots off target", "corners" to "Corners",
        "accurate_passes" to "Completed passes", "accurate_crosses" to "Completed crosses", "matchstats.headers.tackles" to "Tackles")
    fun shots(raw: JsonElement, game: Match, mapping: NativeLaLigaReconciliation.Mapping): List<SoccerShot> {
        validate(raw, game, mapping)
        return rows(raw["content"]["shotmap"]["shots"], 1000, "shots").map { shot ->
            val id = shot["id"].string().takeIf(NativeFotMobClient::validId) ?: throw IOException("FotMob shot identity missing")
            val team = when (shot["teamId"].string()) { mapping.fotmobHome -> game.home.teamId; mapping.fotmobAway -> game.away.teamId; else -> null }
                ?: throw IOException("FotMob shot club identity mismatch")
            val playerId = shot["playerId"].string().takeIf(NativeFotMobClient::validId)
            val name = text(shot["playerName"]) ?: text(shot["fullName"])
            SoccerShot("fotmob:${mapping.fotmobId}|$id", team, if (playerId != null && name != null) SportPlayer("fotmob:$playerId", name) else null,
                integer(shot["min"]), integer(shot["minAdded"], 99), NativeMlsMapping.reportedNumber(shot["x"]), NativeMlsMapping.reportedNumber(shot["y"]),
                NativeMlsMapping.number(shot["expectedGoals"], 1.0), text(shot["eventType"], 80).orEmpty(), text(shot["shotType"], 80), text(shot["situation"], 80))
        }.also { if (it.map { shot -> shot.id }.distinct().size != it.size) throw IOException("FotMob duplicate shot identity") }
    }
    fun pressure(raw: JsonElement, game: Match, mapping: NativeLaLigaReconciliation.Mapping): List<SoccerPressureSample> {
        validate(raw, game, mapping)
        return rows(raw["content"]["momentum"]["main"]["data"], 1000, "attacking pressure").mapNotNull { row ->
            val minute = NativeMlsMapping.number(row["minute"], 200.0) ?: throw IOException("FotMob pressure minute missing")
            // Real live payloads preallocate future minutes with null values. They are not zero observations.
            if (row["value"] == null || row["value"] is JsonNull) return@mapNotNull null
            val value = NativeMlsMapping.reportedNumber(row["value"]) ?: throw IOException("FotMob pressure value missing")
            SoccerPressureSample(minute, value)
        }.also { if (it.map { row -> row.minute }.distinct().size != it.size) throw IOException("FotMob pressure minute duplicated") }
    }
    fun commentary(raw: JsonElement, mapping: NativeLaLigaReconciliation.Mapping): List<SoccerComment> {
        if (!mapping.valid() || raw["HometeamId"].string() != mapping.fotmobHome || raw["AwayteamId"].string() != mapping.fotmobAway) throw IOException("FotMob commentary club identity mismatch")
        return rows(raw["Events"], 2000, "commentary").map { row ->
            val id = row["MessageId"].string().takeIf(NativeFotMobClient::validId) ?: throw IOException("FotMob commentary identity missing")
            val description = text(row["Description"], 16_384) ?: throw IOException("FotMob commentary text missing")
            val incident = text(row["IncidentCode"], 128).orEmpty()
            val minute = integer(row["Elapsed"])?.takeUnless { it == 0 && incident.startsWith("end") }; val added = integer(row["ElapsedPlus"], 99)
            SoccerComment("fotmob:${mapping.fotmobId}|$id", minute?.let { "$it${added?.takeIf { n -> n > 0 }?.let { n -> "+$n" }.orEmpty()}′" }.orEmpty(), description,
                incident, null)
        }.also { if (it.map { row -> row.id }.distinct().size != it.size) throw IOException("FotMob commentary duplicate identity") }
    }
}
