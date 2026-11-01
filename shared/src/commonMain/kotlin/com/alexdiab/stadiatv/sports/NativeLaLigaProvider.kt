package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Official discovery/profile authority. FotMob may enrich a verified game, never replace this catalogue. */
class NativeLaLigaProvider(private val client: NativeLaLigaClient, private val now: () -> Long = ::nowMillis,
    private val details: (suspend (Match) -> GameCentre?)? = null, private val cachedMatch: (suspend (String) -> Match?)? = null) : SportsProvider {
    override val league = NativeLaLigaMapping.league
    override val providerId = "LaLiga"
    override val capabilities = buildSet {
        addAll(setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams, SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players))
        if (details != null) add(SportsCapability.GameDetails)
    }
    data class OfficialGame(val match: Match, val payload: JsonElement, val checkedAt: Long)
    private data class Season(val at: Long, val games: List<OfficialGame>)
    private val seasonLock = Mutex()
    private val seasonCache = LinkedHashMap<Int, Season>()
    private val tableLock = Mutex()
    private val tables = LinkedHashMap<Int, Pair<Long, JsonElement>>()
    private val directoryLock = SportsLock()
    private val teamSlugs = LinkedHashMap<Pair<Int, String>, String>()
    private fun recordTeam(year: Int, raw: JsonElement?) {
        val id = NativeLaLigaMapping.rawId(raw["id"].string()) ?: return
        val slug = raw["slug"].stringOrNull()?.takeIf { it.matches(Regex("[a-z0-9-]{1,120}")) } ?: return
        directoryLock.locked {
            teamSlugs[year to id] = slug
            while (teamSlugs.size > 128) teamSlugs.remove(teamSlugs.keys.first())
        }
    }
    private fun validateYear(raw: JsonElement, year: Int) {
        val years = listOfNotNull(raw["year"].stringOrNull(), raw["season"]["year"].stringOrNull(), raw["subscription"]["year"].stringOrNull())
        if (years.any { it != "$year" }) throw IOException("La Liga response season mismatch")
    }
    private suspend fun seasonGames(year: Int): List<OfficialGame> = seasonLock.withLock {
        seasonCache[year]?.takeIf { now() - it.at in 0 until 300_000 }?.let { return@withLock it.games }
        val slug = client.subscription(year) ?: throw IOException("La Liga $year season unavailable")
        // This cache owns freshness. A second HTTP TTL could re-date an older page after eviction.
        val rows = client.pages("/api/v1/matches", "matches", mapOf("subscription" to slug, "competition" to "primera-division"), maxPages = 12, maxRows = 1200)
        val checkedAt = now()
        val seen = HashSet<String>()
        val games = rows.map { raw ->
            val game = NativeLaLigaMapping.match(raw, year, slug) ?: throw IOException("La Liga fixture game/competition/season identity missing")
            if (!seen.add(game.id)) throw IOException("La Liga season contains duplicate game identities")
            OfficialGame(game, raw, checkedAt)
        }.sortedBy { it.match.startMillis }
        rows.forEach { recordTeam(year, it["home_team"]); recordTeam(year, it["away_team"]) }
        seasonCache[year] = Season(checkedAt, games)
        while (seasonCache.size > 3) seasonCache.entries.minByOrNull { it.value.at }?.key?.let { seasonCache.remove(it) }
        games
    }
    override suspend fun scores(date: Long?): List<Match> {
        val first = Dates.startOfDay(date ?: now()); val end = Dates.addDays(first, 1)
        return range(first, end)
    }
    override suspend fun schedule(start: Long, days: Int): List<Match> {
        val first = Dates.startOfDay(start)
        return range(first, Dates.addDays(first, days.coerceIn(1, 366)))
    }
    private suspend fun range(first: Long, end: Long): List<Match> {
        val rows = LinkedHashMap<String, Match>()
        for (year in NativeLaLigaMapping.season(first)..NativeLaLigaMapping.season(end - 1)) {
            for (game in seasonGames(year)) if (game.match.startMillis in first until end) rows[game.match.id] = game.match
        }
        return rows.values.sortedBy { it.startMillis }
    }
    private suspend fun standingSnapshot(year: Int): Pair<Long, JsonElement> = tableLock.withLock {
        tables[year]?.takeIf { now() - it.first in 0 until 900_000 }?.let { return@withLock it }
        val slug = client.subscription(year) ?: throw IOException("La Liga $year season unavailable")
        val raw = client.get("/api/v1/subscriptions/$slug/standing")
        validateYear(raw, year)
        NativeLaLigaMapping.standings(raw)
        raw["standings"].array.forEach { recordTeam(year, it["team"]) }
        val result = now() to raw
        tables[year] = result
        while (tables.size > 3) tables.remove(tables.keys.first())
        result
    }
    private suspend fun officialStandings(year: Int) = standingSnapshot(year).second
    suspend fun table(match: Match): SoccerTable {
        require(match.league == league && match.nativeReference?.provider == providerId)
        val year = requireNotNull(match.nativeReference.season)
        val (at, raw) = standingSnapshot(year)
        val group = NativeLaLigaMapping.standings(raw)
        val rows = group.rows.map { row ->
            fun n(key: String) = row.values[key]?.toIntOrNull()
            SoccerTableRow(row.teamId, row.teamName, n("leaguerank"), n("gamesplayed"), n("wins"), n("ties"), n("losses"),
                n("pointsfor"), n("pointsagainst"), n("points"), null)
        }
        return SoccerTable(year, false, at, rows)
    }
    override suspend fun teams(): List<Team> {
        val raw = officialStandings(NativeLaLigaMapping.season(now()))
        NativeLaLigaMapping.standings(raw) // Validate completeness before exposing even a partial directory.
        return raw["standings"].array.map { NativeLaLigaMapping.team(it["team"]) ?: throw IOException("La Liga club identity missing") }.distinctBy { it.id }
    }
    override suspend fun standings(): List<StandingGroup> = listOf(NativeLaLigaMapping.standings(officialStandings(NativeLaLigaMapping.season(now()))))
    override suspend fun roster(teamId: String): List<RosterGroup> {
        val id = NativeLaLigaMapping.rawId(teamId)?.takeIf { teamId.startsWith("laliga:") } ?: throw IOException("A native La Liga club identity is required")
        val year = NativeLaLigaMapping.season(now())
        var teamSlug = directoryLock.locked { teamSlugs[year to id] }
        if (teamSlug == null) { teams(); teamSlug = directoryLock.locked { teamSlugs[year to id] } }
        val slug = teamSlug ?: throw IOException("This club has no current-season La Liga squad identity")
        val season = client.subscription(year) ?: throw IOException("La Liga $year season unavailable")
        val rows = client.pages("/api/v1/teams/$slug/squad", "squads", mapOf("subscription" to season), 3_600_000, maxPages = 8, maxRows = 256)
        val people = NativeLaLigaMapping.squad(rows, teamId, now())
        val order = listOf("Goalkeeper", "Defender", "Midfielder", "Forward")
        return people.groupBy { it.position.ifBlank { "Squad" } }.toList().sortedWith(compareBy({ order.indexOf(it.first).takeIf { index -> index >= 0 } ?: order.size }, { it.first }))
            .map { (position, players) -> RosterGroup(if (position == "Squad") position else "${position}s", players) }
    }
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = NativeLaLigaMapping.rawId(athlete.id, true)?.takeIf { athlete.id.startsWith("laliga:") } ?: throw IOException("An official La Liga Opta person identity is required")
        val year = NativeLaLigaMapping.season(now()); val slug = client.subscription(year) ?: throw IOException("La Liga $year season unavailable")
        val signatures = HashSet<String>(); var offset = 0; var total: Int? = null
        repeat(12) {
            val raw = client.get("/api/v1/subscriptions/$slug/players/stats", mapOf("limit" to "100", "offset" to "$offset"), 3_600_000)
            validateYear(raw, year)
            val rows = raw["player_stats"] as? JsonArray ?: throw IOException("La Liga player-stat page missing")
            if (rows.size > 100 || offset + rows.size > 1200) throw IOException("La Liga player-stat lookup exceeds bounds")
            val reported = raw["total"]?.let { it.string().toIntOrNull()?.takeIf { count -> count in 0..1200 } ?: throw IOException("La Liga player-stat total unavailable") }
            if (reported != null && offset + rows.size > reported) throw IOException("La Liga player-stat page exceeds reported total")
            if (total != null && reported != null && total != reported) throw IOException("La Liga player-stat total changed during lookup")
            if (reported != null) total = reported
            val signature = rows.joinToString("|") { it["opta_id"].string() }
            if (rows.isNotEmpty() && !signatures.add(signature)) throw IOException("La Liga player-stat page repeated")
            NativeLaLigaMapping.playerStatistics(raw, id)?.let { (stats, headline) ->
                val bio = linkedMapOf("Source" to "LaLiga", "Statistics season" to "$year–${(year + 1).toString().takeLast(2)}")
                athlete.details.takeIf { it.matches(Regex("[A-Z]{2,3}")) }?.let { bio["Nationality code"] = it }
                return AthleteOverview(athlete, bio, stats, statlineLabel = "$year–${(year + 1).toString().takeLast(2)} season", headlineStats = headline)
            }
            offset += rows.size
            if (total != null && offset >= total!! || total == null && rows.size < 100) throw IOException("La Liga has not published season statistics for this player")
            if (rows.isEmpty()) throw IOException("La Liga player-stat lookup was truncated")
        }
        throw IOException("La Liga player-stat lookup exceeds page limit")
    }
    override suspend fun gameCentre(match: Match) = details?.invoke(match) ?: throw IOException("La Liga detailed match data unavailable")
    suspend fun officialGame(id: String, year: Int): OfficialGame? {
        if (NativeLaLigaMapping.rawId(id) != id) return null
        return seasonGames(year).firstOrNull { it.match.nativeReference?.gameId == id }
    }
    suspend fun calendarGame(id: String, date: Long? = null): Match? {
        if (!id.startsWith("laliga:")) return null
        val raw = NativeLaLigaMapping.rawId(id) ?: return null
        cachedMatch?.invoke(raw)?.takeIf { it.id == id }?.let { return it }
        val year = NativeLaLigaMapping.season(date ?: now())
        for (candidate in if (date == null) listOf(year, year - 1, year + 1) else listOf(year)) {
            if (client.subscription(candidate) == null) continue
            officialGame(raw, candidate)?.let { return it.match }
        }
        return null
    }
    suspend fun rounds(year: Int): List<NativeLaLigaMapping.Round> {
        val slug = client.subscription(year) ?: throw IOException("La Liga $year season unavailable")
        return NativeLaLigaMapping.rounds(client.get("/api/v1/subscriptions/$slug/rounds", ttl = 86_400_000))
    }
}
