package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*

/** Native EPL discovery, official tables, announced squads and provider season statistics from the iOS port. */
class NativePremierLeagueProvider(private val client: NativePremierLeagueClient,
    private val now: () -> Long = ::nowMillis,
    private val details: (suspend (Match) -> GameCentre?)? = null,
    private val cachedMatch: (suspend (String) -> Match?)? = null) : SportsProvider {
    override val league = NativePremierLeagueMapping.league
    override val providerId = "EPL"
    override val capabilities = buildSet { addAll(setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams, SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players)); if (details != null) add(SportsCapability.GameDetails) }
    override suspend fun scores(date: Long?): List<Match> = range(date ?: now(), 1, 10_000)
    override suspend fun schedule(start: Long, days: Int): List<Match> = range(start, days.coerceIn(1, 366), 120_000)
    private suspend fun range(start: Long, days: Int, ttl: Long): List<Match> {
        val end = NativePremierLeagueMapping.addDays(start, days - 1)
        val firstDay = NativePremierLeagueMapping.day(start); val lastDay = NativePremierLeagueMapping.day(end)
        val games = LinkedHashMap<String, Match>()
        for (season in setOf(NativePremierLeagueMapping.season(start), NativePremierLeagueMapping.season(end))) {
            val rows = client.matches(season, NativePremierLeagueMapping.day(NativePremierLeagueMapping.addDays(start, -1)), NativePremierLeagueMapping.day(NativePremierLeagueMapping.addDays(end, 1)), ttl)
            for (row in rows) {
                val game = NativePremierLeagueMapping.game(row) ?: throw IOException("Premier League match data is incomplete")
                if (NativePremierLeagueMapping.day(game.startMillis) in firstDay..lastDay) games[game.id] = game
            }
        }
        return games.values.sortedBy { it.startMillis }
    }
    override suspend fun teams(): List<Team> {
        val raw = client.get("/api/v1/competitions/8/seasons/${NativePremierLeagueMapping.season(now())}/teams", mapOf("_limit" to "100"), 86_400_000)
        val rows = raw["data"] as? JsonArray ?: throw IOException("Premier League team directory unavailable")
        if (rows.size > 100) throw IOException("Premier League team directory exceeds limit")
        return rows.map { NativePremierLeagueMapping.team(it) ?: throw IOException("Premier League team identity missing") }.distinctBy { it.id }
    }
    override suspend fun standings(): List<StandingGroup> {
        val raw = client.get("/api/v5/competitions/8/seasons/${NativePremierLeagueMapping.season(now())}/standings", mapOf("live" to "false"), 900_000)
        val expectedSeason = NativePremierLeagueMapping.season(now()).toString()
        if (listOfNotNull(raw["season"].stringOrNull(), raw["season"]["id"].stringOrNull()).any { it != expectedSeason } ||
            listOfNotNull(raw["competition"].stringOrNull(), raw["competition"]["id"].stringOrNull()).any { it != "8" } ||
            raw["live"].stringOrNull()?.lowercase()?.let { it !in setOf("false", "0") } == true) throw IOException("Premier League official-table identity mismatch")
        val tables = raw["tables"] as? JsonArray ?: throw IOException("Premier League standings unavailable")
        if (tables.size > 32) throw IOException("Premier League standings exceeds limit")
        val rows = tables.flatMap { (it["entries"] as? JsonArray) ?: throw IOException("Premier League standings entries missing") }
        if (rows.size > 100) throw IOException("Premier League standings exceeds limit")
        val mapped = rows.map { row ->
            val team = NativePremierLeagueMapping.team(row["team"]) ?: throw IOException("Premier League standings team missing")
            val values = linkedMapOf<String, String>()
            for ((source, target) in listOf("position" to "leaguerank", "played" to "gamesplayed", "won" to "wins", "drawn" to "ties", "lost" to "losses", "goalsFor" to "pointsfor", "goalsAgainst" to "pointsagainst", "points" to "points")) row["overall"][source].stringOrNull()?.let { values[target] = it }
            val forGoals = values["pointsfor"]?.toIntOrNull(); val against = values["pointsagainst"]?.toIntOrNull()
            if (forGoals != null && against != null) values["pointdifferential"] = "${forGoals - against}"
            StandingRow(team.id, team.name, team.abbreviation, team.logoUrl,
                listOfNotNull(values["wins"], values["ties"], values["losses"]).takeIf { it.size == 3 }?.joinToString("-") ?: "–", values)
        }.distinctBy { it.teamId }
        return listOf(StandingGroup("Premier League", mapped.sortedBy { it.values["leaguerank"]?.toIntOrNull() ?: Int.MAX_VALUE }, "EPL"))
    }
    override suspend fun roster(teamId: String): List<RosterGroup> {
        val id = NativePremierLeagueMapping.rawId(teamId) ?: throw IOException("An EPL team identity is required")
        val raw = client.get("/api/v2/competitions/8/seasons/${NativePremierLeagueMapping.season(now())}/teams/$id/squad", ttl = 3_600_000)
        if ((raw["teamId"] ?: raw["team"]["id"]).stringOrNull()?.let { it != id } == true) throw IOException("Premier League squad identity mismatch")
        val rows = raw["players"] as? JsonArray ?: throw IOException("Premier League squad unavailable")
        if (rows.size > 256) throw IOException("Premier League squad exceeds limit")
        val people = rows.map { row ->
            val person = NativePremierLeagueMapping.id(row["id"]) ?: throw IOException("Premier League player identity missing")
            val name = row["name"]["display"].stringOrNull()?.takeIf(String::isNotBlank) ?: listOf(row["name"]["first"].string(), row["name"]["last"].string()).filter(String::isNotBlank).joinToString(" ")
            if (name.isBlank()) throw IOException("Premier League player name missing")
            Athlete("epl:$person", name, row["shirtNum"].string(), row["position"].string(), positionName = row["position"].stringOrNull(),
                details = row["country"]["country"].string(), age = NativePremierLeagueMapping.age(row["dates"]["birth"].stringOrNull(), now()),
                height = row["height"].stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 50.0..250.0 }?.let { "${NativePremierLeagueMapping.statText(row["height"])} cm" },
                weight = row["weight"].stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 20.0..300.0 }?.let { "${NativePremierLeagueMapping.statText(row["weight"])} kg" },
                birthPlace = row["countryOfBirth"].stringOrNull())
        }.distinctBy { it.id }
        val order = listOf("Goalkeeper", "Defender", "Midfielder", "Forward")
        return people.groupBy { it.position.ifBlank { "Squad" } }.toList().sortedWith(compareBy({ order.indexOf(it.first).takeIf { index -> index >= 0 } ?: order.size }, { it.first }))
            .map { (name, players) -> RosterGroup(if (name == "Squad") name else "${name}s", players) }
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = NativePremierLeagueMapping.rawId(athlete.id) ?: throw IOException("An EPL player identity is required")
        val season = NativePremierLeagueMapping.season(now())
        val raw = client.get("/api/v1/competitions/8/seasons/$season/players/$id/stats", ttl = 3_600_000)
        if (listOfNotNull(raw["playerId"].stringOrNull(), raw["id"].stringOrNull(), raw["player"]["id"].stringOrNull()).any { it != id } ||
            raw["player"].isPresent && NativePremierLeagueMapping.id(raw["player"]["id"]) == null ||
            NativePremierLeagueMapping.reportedSeasons(raw).any { it != season.toString() }) throw IOException("Premier League statistics identity/season mismatch")
        val stats = raw["stats"] as? JsonObject ?: throw IOException("Premier League player statistics unavailable")
        if (stats.size > 512) throw IOException("Premier League player statistics exceeds limit")
        val values = stats.entries.sortedBy { it.key }.mapNotNull { (key, value) -> NativePremierLeagueMapping.statText(value)?.let { humanize(key) to it } }.toMap()
        val headline = listOf("goals", "goalAssists", "gamesPlayed", "cleanSheets").mapNotNull { key -> NativePremierLeagueMapping.statText(stats[key])?.let { humanize(key) to it } }.toMap()
        val reportedName = raw["player"]["name"].stringOrNull()?.takeIf(String::isNotBlank) ?: listOf(raw["player"]["firstName"].string(), raw["player"]["lastName"].string()).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank)
        val bio = linkedMapOf("Source" to "EPL", "Statistics season" to "$season–${(season + 1).toString().takeLast(2)}")
        raw["player"]["country"]["country"].stringOrNull()?.let { bio["Nationality"] = it }
        raw["player"]["currentTeam"]["name"].stringOrNull()?.let { bio["Current club"] = it }
        return AthleteOverview(athlete.copy(name = reportedName ?: athlete.name, position = raw["player"]["position"].stringOrNull() ?: athlete.position), bio, values,
            statlineLabel = "$season–${(season + 1).toString().takeLast(2)} season", headlineStats = headline)
    }
    override suspend fun gameCentre(match: Match) = details?.invoke(match) ?: throw IOException("Premier League game detail unavailable")
    suspend fun calendarGame(id: String): Match? {
        val raw = NativePremierLeagueMapping.rawId(id) ?: return null
        cachedMatch?.invoke(raw)?.takeIf { it.id == id }?.let { return it }
        return NativePremierLeagueMapping.game(client.get("/api/v2/matches/$raw", ttl = 15_000))?.takeIf { it.id == id }
    }
    private fun humanize(key: String) = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar(Char::uppercase)
}
