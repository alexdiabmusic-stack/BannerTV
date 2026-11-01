package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.serialization.json.*

/** Native MLS discovery and profiles. Unverified crest/roster endpoints are deliberately not guessed. */
class NativeMlsProvider(private val client: NativeMlsClient, private val now: () -> Long = ::nowMillis,
    private val details: (suspend (Match) -> GameCentre?)? = null, private val cachedMatch: (suspend (String) -> Match?)? = null,
    private val rememberSchedule: (suspend (List<JsonElement>, Long) -> Unit)? = null) : SportsProvider {
    override val league = NativeMlsMapping.league
    override val providerId = "MLS"
    override val capabilities = buildSet {
        addAll(setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams, SportsCapability.Standings, SportsCapability.Players, SportsCapability.Rosters))
        if (details != null) add(SportsCapability.GameDetails)
    }
    override suspend fun scores(date: Long?) = range(date ?: now(), 1, 10_000)
    override suspend fun schedule(start: Long, days: Int) = range(start, days.coerceIn(1, 366), 120_000)
    private suspend fun range(start: Long, days: Int, ttl: Long): List<Match> {
        val first = Dates.startOfDay(start); val end = Dates.addDays(first, days)
        fun year(at: Long) = Dates.format("yyyy", at).toInt()
        fun day(at: Long) = Dates.format("yyyy-MM-dd", at, "UTC")
        val games = LinkedHashMap<String, Match>()
        val accepted = LinkedHashMap<String, JsonElement>()
        // iOS discovery is the verified regular-season surface. An unannounced playoff query can 404
        // and must not force the entire regular-season league feed onto another provider.
        for (year in year(first)..year(end - 1)) for (competition in setOf(NativeMlsClient.REGULAR)) {
            val season = client.season(year, competition) ?: continue
            val rows = client.pages("/matches/seasons/$season", "schedule", mapOf("per_page" to "100", "competition_id" to competition,
                "match_date[gte]" to day(Dates.addDays(first, -1)), "match_date[lte]" to day(end)), ttl)
            for (raw in rows) {
                if (raw["season_id"].string() != season || raw["season"].string().toIntOrNull() != year || raw["competition_id"].string() != competition) throw IOException("MLS schedule season/competition mismatch")
                val game = NativeMlsMapping.game(raw) ?: throw IOException("MLS schedule match identity missing")
                if (game.startMillis in first until end) { games[game.id] = game; accepted[game.id] = raw }
            }
        }
        rememberSchedule?.invoke(accepted.values.toList(), now())
        return games.values.sortedBy { it.startMillis }
    }
    override suspend fun teams(): List<Team> {
        val year = year(); val season = client.season(year) ?: throw IOException("MLS $year season unavailable")
        return client.pages("/clubs/competitions/${NativeMlsClient.REGULAR}/seasons/$season", "clubs", mapOf("per_page" to "100"), 86_400_000, maxPages = 4, maxRows = 100).map { raw ->
            if (raw["competition_id"].string() != NativeMlsClient.REGULAR || raw["season_id"].string() != season || raw["season"].string().toIntOrNull() != year) throw IOException("MLS club season mismatch")
            NativeMlsMapping.club(raw) ?: throw IOException("MLS club identity missing")
        }.distinctBy { it.id }
    }
    override suspend fun standings(): List<StandingGroup> {
        val year = year(); val season = client.season(year) ?: throw IOException("MLS $year season unavailable")
        val path = "/competitions/${NativeMlsClient.REGULAR}/seasons/$season/standings"
        val overall = client.get(path, mapOf("is_live" to "false"), 900_000)
        val conference = client.get(path, mapOf("is_live" to "false", "category" to "conference"), 900_000)
        return NativeMlsStandings.groups(overall, year, season, NativeMlsClient.REGULAR) + NativeMlsStandings.groups(conference, year, season, NativeMlsClient.REGULAR)
    }
    override suspend fun roster(teamId: String): List<RosterGroup> {
        require(teamId.startsWith("mls:") && NativeMlsMapping.rawId(teamId, "CLU") != null)
        throw UnsupportedCapabilityException(SportsCapability.Rosters, "MLS: a verified season roster is unavailable; announced players are available in match lineups")
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = NativeMlsMapping.rawId(athlete.id, "OBJ")?.takeIf { athlete.id.startsWith("mls:") } ?: throw IOException("An MLS person identity is required")
        val year = year(); val season = client.season(year) ?: throw IOException("MLS $year season unavailable")
        val seenMatches = HashSet<String>(); val cursors = HashSet<String>(); var cursor: String? = null; var complete = false; var reports = 0
        val totals = LinkedHashMap<String, Double>()
        // Stream each bounded page; never retain a full season of large raw player reports in memory.
        for (page in 0 until 40) {
            val raw = client.get("/statistics/players/$id/matches", mapOf("competition_id" to NativeMlsClient.REGULAR) + listOfNotNull(cursor?.let { "page_token" to it }).toMap(), 3_600_000)
            val rows = raw["match_log"] as? JsonArray ?: throw IOException("MLS player match reports unavailable")
            if (rows.size > 100) throw IOException("MLS player report page exceeds limit")
            for (row in rows) {
                val matchId = NativeMlsMapping.rawId(row["match_id"].string(), "MAT") ?: throw IOException("MLS player report match identity missing")
                val stats = row["player_statistics"] as? JsonObject ?: throw IOException("MLS player report statistics missing")
                if (stats["player_id"].string() != id) throw IOException("MLS player report person mismatch")
                if (row["competition_id"].string() != NativeMlsClient.REGULAR) throw IOException("MLS player report competition mismatch")
                val reportedYear = row["season"].string().toIntOrNull() ?: throw IOException("MLS player report season missing")
                if (reportedYear != year) continue
                if (row["season_id"].string() != season) throw IOException("MLS player report season identity mismatch")
                if (!seenMatches.add(matchId)) continue
                if (++reports > 2000) throw IOException("MLS player reports exceed limit")
                for (key in ADDITIVE) NativeMlsMapping.number(stats[key])?.let { totals[key] = (totals[key] ?: 0.0) + it }
            }
            cursor = NativeMlsClient.cursor(raw)
            if (cursor == null) { complete = true; break }
            if (!cursors.add(cursor!!)) throw IOException("MLS player report cursor repeated")
        }
        if (!complete) throw IOException("MLS player season reports were truncated")
        val values = totals.mapKeys { NativeMlsDetails.humanize(it.key) }.mapValues { NativeMlsMapping.text(it.value) }
        val headline = listOf("goals", "assists", "normalized_player_minutes", "cards_yellow", "cards_red").mapNotNull { key -> totals[key]?.let { (if (key == "normalized_player_minutes") "Minutes (normalized)" else NativeMlsDetails.humanize(key)) to NativeMlsMapping.text(it) } }.toMap()
        return AthleteOverview(athlete, mapOf("Source" to "MLS", "Statistics season" to "$year", "Reported matches" to "$reports",
            "Coverage" to "Published regular-season match reports; not an appearance count"), values, statlineLabel = "$year reported matches", headlineStats = headline)
    }
    override suspend fun gameCentre(match: Match) = details?.invoke(match) ?: throw IOException("MLS game details unavailable")
    suspend fun calendarGame(id: String): Match? {
        if (!id.startsWith("mls:")) return null
        val raw = NativeMlsMapping.rawId(id, "MAT") ?: return null
        cachedMatch?.invoke(raw)?.takeIf { it.id == id }?.let { return it }
        return NativeMlsMapping.game(client.get("/matches/$raw", ttl = 15_000), true)?.takeIf { it.id == id }
    }
    private fun year() = Dates.format("yyyy", now(), "UTC").toInt()
    companion object {
        /** Counts/distance only. Ratios, ranks, maximum speed and xG efficiency are NOT additive. */
        val ADDITIVE = setOf("goals", "assists", "second_assists", "normalized_player_minutes", "cards_yellow", "cards_red", "cards_yellow_red",
            "shots_at_goal_sum", "shots_on_target", "passes_sum", "passes_successful_sum", "crosses_sum", "crosses_successful_sum",
            "fouls_sum", "fouls_suffered", "offsides", "distance_covered", "xG", "defensive_clearances", "tackling_games_air_won", "penalties_successful", "penalties_sum")
    }
}

object NativeMlsStandings {
    fun groups(raw: JsonElement, year: Int, seasonId: String, competition: String): List<StandingGroup> {
        val tables = raw["tables"] as? JsonArray ?: throw IOException("MLS standings format unavailable")
        if (tables.size > 16) throw IOException("MLS standings exceeds limit")
        return tables.map { table ->
            if (table["season"].string().toIntOrNull() != year || table["season_id"].string() != seasonId || table["competition_id"].string() != competition || table["type"].string() != "table") throw IOException("MLS standings season/competition/type mismatch")
            val entries = table["entries"] as? JsonArray ?: throw IOException("MLS standings entries missing")
            if (entries.size > 100) throw IOException("MLS standings rows exceeds limit")
            val rows = entries.map { row ->
                val id = NativeMlsMapping.qualified(row["team_id"].string(), "CLU") ?: throw IOException("MLS standings club missing")
                val name = row["team"].stringOrNull()?.takeIf(String::isNotBlank) ?: throw IOException("MLS standings club name missing")
                val keys = listOf("position" to "leaguerank", "games_played" to "gamesplayed", "wins" to "wins", "draws" to "ties", "losses" to "losses",
                    "goals_scored" to "pointsfor", "goals_against" to "pointsagainst", "goals_difference" to "pointdifferential", "points" to "points")
                val values = keys.mapNotNull { (source, key) -> row[source].stringOrNull()?.toIntOrNull()?.let { key to "$it" } }.toMap()
                StandingRow(id, name, row["team_three_letter_code"].string(row["team_short_name"].string()), null,
                    listOfNotNull(values["wins"], values["ties"], values["losses"]).takeIf { it.size == 3 }?.joinToString("-") ?: "–", values)
            }
            if (rows.map { it.teamId }.distinct().size != rows.size) throw IOException("MLS standings duplicate club")
            StandingGroup(table["group"].stringOrNull()?.takeIf(String::isNotBlank) ?: "Supporters' Shield", rows.sortedBy { it.values["leaguerank"]?.toIntOrNull() ?: Int.MAX_VALUE }, "MLS")
        }
    }
    fun table(raw: JsonElement, year: Int, seasonId: String, competition: String, live: Boolean, at: Long): SoccerTable {
        val groups = groups(raw, year, seasonId, competition).map { group -> SoccerTableGroup(group.name, group.rows.map { row ->
            fun value(key: String) = row.values[key]?.toIntOrNull()
            SoccerTableRow(row.teamId, row.teamName, value("leaguerank"), value("gamesplayed"), value("wins"), value("ties"), value("losses"), value("pointsfor"), value("pointsagainst"), value("points"), null)
        }) }
        return SoccerTable(year, live, at, groups.flatMap { it.rows }, groups = groups)
    }
}
