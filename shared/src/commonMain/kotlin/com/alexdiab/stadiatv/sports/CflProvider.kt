package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

class CflProvider(private val http: SportsHttp, private val now: () -> Long = ::nowMillis) : SportsProvider {
    override val league = requireNotNull(League.find("football/cfl"))
    override val providerId = "CFL"
    override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams,
        SportsCapability.Standings, SportsCapability.Rosters, SportsCapability.Players, SportsCapability.Leaders, SportsCapability.GameDetails)
    private suspend fun get(path: String, ttl: Long) = http.getJson("https://echo.pims.cfl.ca/api/$path",
        headers = mapOf("Accept" to "application/json", "User-Agent" to "BannerTV/1.0"), ttlMillis = ttl, maxResponseBytes = 4_194_304)
    private fun rows(raw: JsonElement?) = raw as? JsonArray ?: throw SportsIOException("CFL: missing provider records")
    private fun year(at: Long) = SportsTime.format("yyyy", at).toInt()
    private suspend fun season(year: Int): Int = rows(get("seasons", 86_400_000)).singleOrNull { it["year"].int() == year }
        ?.get("ID").string().toIntOrNull() ?: throw SportsIOException("CFL: no unique season for that year")
    private suspend fun directory(key: String, ttl: Long) = rows(get(key,ttl)).associateBy { it["ID"].string() }
    private fun rawId(id: String) = id.takeIf { it.startsWith("cfl:") }?.removePrefix("cfl:")
        ?.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) } ?: throw SportsIOException("CFL: native identity required")
    private suspend fun fixtures(year: Int): List<Match> = coroutineScope {
        val teams = async { directory("teams", 3_600_000) }; val venues = async { directory("venues", 86_400_000) }
        val season = season(year)
        val raw = rows(get("fixtures?season_id=$season&limit=300", 300_000))
        val clubs = teams.await(); val places = venues.await()
        raw.mapNotNull { game(it, clubs, places, season) }.sortedBy { it.startMillis }
    }
    override suspend fun scores(date: Long?) = fixtures(year(date ?: now())).filter { date == null || SportsTime.compact(it.startMillis) == SportsTime.compact(date) }
    override suspend fun schedule(start: Long, days: Int): List<Match> {
        val first = SportsTime.startOfDay(start); val end = SportsTime.addDays(start, days.coerceIn(1,366))
        return (year(first)..year(end - 1)).flatMap { fixtures(it) }.filter { it.startMillis in first until end }.distinctBy { it.key }.sortedBy { it.startMillis }
    }
    private fun team(raw: JsonElement?): Team? {
        val id = raw["ID"].stringOrNull() ?: return null; val abbr = raw["abbreviation"].string("CFL")
        return Team("cfl:$id", raw["clubname"].string(raw["name"].string(abbr)), abbr, abbr, null, league)
    }
    override suspend fun teams() = directory("teams", 3_600_000).values.mapNotNull(::team).sortedBy { it.name }
    private fun game(f: JsonElement, clubs: Map<String,JsonElement>, venues: Map<String,JsonElement>, season: Int): Match? {
        val id = f["ID"].stringOrNull() ?: return null
        if (f["season_id"].int(season) != season) return null
        val start = SportsTime.parseIso(f["start_at"].stringOrNull()) ?: return null
        val home = team(clubs[f["home_team_id"].string()]) ?: return null
        val away = team(clubs[f["away_team_id"].string()]) ?: return null
        val status = f["game_status"].stringOrNull()
        val state = when (status?.uppercase()) {
            "FINAL","FINISHED" -> GameState.FINAL
            "IN PROGRESS","INPROGRESS","LIVE","HALFTIME" -> GameState.LIVE
            null -> if (start <= now()) GameState.LIVE else GameState.PRE
            else -> GameState.PRE
        }
        fun side(t: Team, score: Int?, other: Int?) = TeamSide(t.name,t.abbreviation,t.abbreviation,t.logoUrl,score?.toString(),
            isWinner = state == GameState.FINAL && score != null && other != null && score > other, teamId = t.id)
        val hs = f["home_team_score"].string().toIntOrNull(); val ascore = f["away_team_score"].string().toIntOrNull()
        val zone = listOf(home,away).firstNotNullOfOrNull { t -> when (clubs[t.id.removePrefix("cfl:")]?.get("team_zone").string().lowercase()) {
            "eastern" -> "EAST"; "western" -> "WEST"; else -> null } } ?: "DIVISION"
        val label = when (f["game_type_id"].int(-1)) { 0 -> "PRESEASON"; 1 -> "REGULAR SEASON"; 2,3 -> "$zone SEMI-FINAL"; 4,5 -> "$zone FINAL"; 6 -> "GREY CUP"; else -> "CFL" }
        return Match("cfl:$id",league,start,"${away.name} at ${home.name}","${away.abbreviation} @ ${home.abbreviation}",state,status ?: label,
            side(home,hs,ascore),side(away,ascore,hs),f["broadcasting_options"].array.mapNotNull { it.stringOrNull() },
            venues[f["venue_id"].string()]?.get("name").stringOrNull(),LiveContext(clock = f["game_clock"].stringOrNull()),NativeGameReference(providerId,id,season,week=f["week"].string().toIntOrNull()))
    }
    override suspend fun gameCentre(match: Match): GameCentre {
        val ref = match.nativeReference?.takeIf { it.provider.equals(providerId,true) && it.gameId == rawId(match.id) && match.league == league }
            ?: throw SportsIOException("CFL: mismatched fixture identity")
        val f = get("fixtures/${ref.gameId}", 0)
        val fresh = game(f,directory("teams",3_600_000),directory("venues",86_400_000),ref.season ?: season(year(match.startMillis)))
            ?.takeIf { it.key == match.key && it.home.teamId == match.home.teamId && it.away.teamId == match.away.teamId }
            ?: throw SportsIOException("CFL: fixture does not match")
        return GameCentre(liveMatch = fresh, gameInfo = listOfNotNull(fresh.venue?.let { "Venue" to it }))
    }
    override suspend fun standings(): List<StandingGroup> {
        val clubs = directory("teams",3_600_000)
        val divisions = get("standings/${year(now())}",900_000).path("data","divisions").obj ?: throw SportsIOException("CFL: missing divisions")
        return divisions.entries.sortedBy { it.key }.mapNotNull { (division,data) ->
            val entries = rows(data["standings"]).mapNotNull { row ->
                val id = row["team_id"].stringOrNull() ?: return@mapNotNull null
                val abbr = row["abbreviation"].string(clubs[id]?.get("abbreviation").string("CFL"))
                val keys = listOf("wins","losses","ties","winning_percentage","points_for","points_against","points","games_played","place")
                StandingRow("cfl:$id", clubs[id]?.get("clubname").string(abbr), abbr, null, keys.take(3).mapNotNull { row[it].stringOrNull() }.joinToString("-"),
                    keys.mapNotNull { k -> row[k].stringOrNull()?.let { k to it } }.toMap())
            }
            entries.takeIf { it.isNotEmpty() }?.let { StandingGroup(division.uppercase(),it,"CFL") }
        }
    }
    override suspend fun roster(teamId: String) = rows(get("teams/${rawId(teamId)}/roster",14_400_000)["rosterplayers"])
        .groupBy { it["position"].string("Players") }.entries.sortedBy { it.key }.map { (position,rows) ->
            RosterGroup(position,rows.mapNotNull { r ->
                val id = r["player_id"].stringOrNull() ?: return@mapNotNull null
                Athlete("cfl:$id", name(r), r["jersey_no"].string(),position,positionName=position,
                    height=r["height_ft"].stringOrNull()?.let { "$it′ ${r["height_in"].int()}″" },weight=r["weight_lbs"].stringOrNull()?.let { "$it lb" },
                    college=r["college"].stringOrNull(),injured=r["state"].string()=="injured_list")
            })
        }
    private fun name(r: JsonElement) = listOfNotNull(r["firstname"].stringOrNull(),r["lastname"].stringOrNull()).joinToString(" ").ifBlank { "Unknown player" }
    suspend fun teamRecords(year: Int) = rows(get("stats/teamrecords?season_id=${season(year)}&limit=20",3_600_000))
    suspend fun playerRecords(year: Int, teamId: String? = null) = rows(get("stats/playerrecords?season_id=${season(year)}&limit=100" +
        (teamId?.let { "&team_id=${rawId(it)}" } ?: ""),3_600_000))
    override suspend fun athlete(athlete: Athlete): AthleteOverview {
        val id = rawId(athlete.id)
        val row = playerRecords(year(now())).firstOrNull { it["player_id"].string()==id } ?: throw SportsIOException("CFL: player record unavailable")
        val stats = row["position"].stringOrNull()?.let { mapOf("Position" to it) }.orEmpty()
        return AthleteOverview(athlete,stats=stats,headlineStats=stats,statlineLabel="Player")
    }
    override suspend fun leaders(): List<LeaderBoard> {
        val raw = http.getJson("https://api.stats.cfl.ca/stats/leaders/${year(now())}",ttlMillis=900_000,maxResponseBytes=4_194_304)
        return listOf("offence","defence","special_teams").flatMap { group -> raw[group].array.mapNotNull { c ->
            val title = c["category"].stringOrNull() ?: return@mapNotNull null
            LeaderBoard("$group · $title",c["leaders"].array.mapIndexedNotNull { i,r ->
                val id = r["player_id"].stringOrNull() ?: return@mapIndexedNotNull null
                val value = r["statValue"].stringOrNull()?.takeIf { it.toDoubleOrNull()?.isFinite()==true } ?: return@mapIndexedNotNull null
                LeaderRow(Athlete("cfl:$id",name(r),position=r["position"].string()),r["team_id"].string(),value,i+1)
            })
        } }
    }
}
