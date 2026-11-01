package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.domain.Athlete
import com.alexdiab.stadiatv.domain.AthleteOverview
import com.alexdiab.stadiatv.domain.GameCentre
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Injury
import com.alexdiab.stadiatv.domain.LeaderBoard
import com.alexdiab.stadiatv.domain.LeaderRow
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.LiveContext
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.Racer
import com.alexdiab.stadiatv.domain.RosterGroup
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.domain.StandingGroup
import com.alexdiab.stadiatv.domain.StandingRow
import com.alexdiab.stadiatv.domain.Team
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.net.SportsHttp
import com.alexdiab.stadiatv.net.array
import com.alexdiab.stadiatv.net.bool
import com.alexdiab.stadiatv.net.firstNonBlank
import com.alexdiab.stadiatv.net.get
import com.alexdiab.stadiatv.net.int
import com.alexdiab.stadiatv.net.isPresent
import com.alexdiab.stadiatv.net.mapObjects
import com.alexdiab.stadiatv.net.obj
import com.alexdiab.stadiatv.net.path
import com.alexdiab.stadiatv.net.string
import com.alexdiab.stadiatv.net.stringOrNull
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement

/**
 * ESPN's public site API. This is the universal fallback behind every native provider, so a league
 * can never be selectable and then silently empty.
 */
class EspnApi(private val http: SportsHttp) {

    // region Scoreboards

    suspend fun scoreboard(league: League, date: Long? = null): List<Match> {
        val query = date?.let { "dates=${Dates.compact(it)}&" }.orEmpty()
        val root = http.getJson("$SITE/${league.path}/scoreboard?${query}limit=1000", ttlMillis = SCORE_TTL)
        return parseMatches(root, league)
    }

    /** Mirrors the iOS scoreboard loader: 30-day chunks fetched concurrently, plus a fresh "today" request. */
    suspend fun schedule(league: League, start: Long, days: Int): List<Match> = coroutineScope {
        val queries = rangeQueries(start, days)
        val gate = Semaphore(4)
        val results = queries.map { query ->
            async {
                gate.withPermit {
                    runCatching {
                        val ttl = if (query.contains("-")) SCHEDULE_TTL else SCORE_TTL
                        parseMatches(http.getJson("$SITE/${league.path}/scoreboard?dates=$query&limit=1000", ttlMillis = ttl), league)
                    }
                }
            }
        }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull() ?: IllegalStateException("No schedule")
        results.flatMap { it.getOrDefault(emptyList()) }
            .associateBy { it.id }.values
            .sortedBy { it.startMillis }
    }

    // endregion

    // region News / Teams

    suspend fun news(league: League, limit: Int = 20): List<Article> {
        val root = http.getJson("$SITE/${league.path}/news?limit=$limit", ttlMillis = NEWS_TTL)
        return parseNews(root, league)
    }

    suspend fun teams(league: League): List<Team> {
        if (league.group == SportGroup.Golf || league.group == SportGroup.Tennis || league.group == SportGroup.Racing) return emptyList()
        val root = http.getJson("$SITE/${league.path}/teams?limit=1000", ttlMillis = TEAMS_TTL)
        return parseTeams(root, league)
    }

    // endregion

    // region Stats

    suspend fun standings(league: League): List<StandingGroup> {
        val root = http.getJson("https://site.api.espn.com/apis/v2/sports/${league.path}/standings", ttlMillis = STANDINGS_TTL)
        return parseStandings(root)
    }

    suspend fun roster(league: League, teamId: String): List<RosterGroup> {
        val root = http.getJson("$SITE/${league.path}/teams/$teamId/roster", ttlMillis = ROSTER_TTL)
        return parseRoster(root)
    }

    suspend fun leaders(league: League): List<LeaderBoard> {
        val root = http.getJson(
            "https://site.web.api.espn.com/apis/common/v3/sports/${league.path}/statistics/byathlete?limit=50",
            ttlMillis = STANDINGS_TTL,
        )
        return parseLeaders(root, league)
    }

    suspend fun injuries(league: League): List<Injury> {
        val root = http.getJson("$SITE/${league.path}/injuries", ttlMillis = STANDINGS_TTL)
        return parseInjuries(root)
    }

    suspend fun athleteOverview(league: League, athlete: Athlete): AthleteOverview {
        val root = http.getJson(
            "https://site.web.api.espn.com/apis/common/v3/sports/${league.path}/athletes/${athlete.id}/overview",
            ttlMillis = ROSTER_TTL,
        )
        return parseAthleteOverview(root, athlete, league)
    }

    suspend fun summary(match: Match): GameCentre {
        if (match.nativeReference != null) return GameCentre.EMPTY
        val root = http.getJson("$SITE/${match.league.path}/summary?event=${match.id}", ttlMillis = SUMMARY_TTL)
        return EspnSummary.parse(root, match)
    }

    suspend fun field(league: League): List<Racer> {
        val root = http.getJson("$SITE/${league.path}/scoreboard", ttlMillis = SCORE_TTL)
        return parseField(root, league)
    }

    // endregion

    companion object {
        const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
        private const val SCORE_TTL = 10_000L
        private const val SUMMARY_TTL = 12_000L
        private const val SCHEDULE_TTL = 120_000L
        private const val NEWS_TTL = 180_000L
        private const val TEAMS_TTL = 6 * 3600_000L
        private const val STANDINGS_TTL = 15 * 60_000L
        private const val ROSTER_TTL = 60 * 60_000L

        // region Date ranges

        /**
         * ESPN accepts `yyyyMMdd` or `yyyyMMdd-yyyyMMdd` (max ~30 days) for `dates`. A long range is split
         * into chunks, and an extra single-day request for today is added so live scores are never
         * served from a long-range cache entry.
         */
        fun rangeQueries(start: Long, days: Int, now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): List<String> {
            val requested = days.coerceAtLeast(1)
            val first = Dates.startOfDay(start)
            val result = mutableListOf<String>()
            var cursor = first
            var remaining = requested
            while (remaining > 0) {
                val chunk = minOf(30, remaining)
                val chunkEnd = Dates.addDays(cursor, chunk - 1)
                result += if (chunk == 1) Dates.compact(cursor) else "${Dates.compact(cursor)}-${Dates.compact(chunkEnd)}"
                cursor = Dates.addDays(cursor, chunk)
                remaining -= chunk
            }
            val last = Dates.addDays(first, requested - 1)
            val today = Dates.startOfDay(now)
            if (requested > 1 && today in first..last) {
                val todayQuery = Dates.compact(today)
                if (todayQuery !in result) result += todayQuery
            }
            return result
        }

        // endregion

        // region Matches

        fun parseMatches(root: JsonElement?, league: League): List<Match> {
            val events = root["events"].array
            val result = ArrayList<Match>(events.size)
            events.forEachIndexed { index, event ->
                val competition = selectCompetition(event, league) ?: return@forEachIndexed
                val id = event["id"].stringOrNull() ?: "${league.path}-$index"
                val start = Dates.parseIso(event["date"].stringOrNull()) ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
                val status = competition["status"] ?: event["status"]
                val type = status["type"]
                val state = when (type["state"].string("pre")) {
                    "in" -> GameState.LIVE
                    "post" -> GameState.FINAL
                    else -> GameState.PRE
                }
                val detail = if (state == GameState.PRE) {
                    Dates.kickoff(start)
                } else firstNonBlank(type["shortDetail"].stringOrNull(), type["detail"].stringOrNull(), "Upcoming")

                val competitors = competition["competitors"].array
                var home = TeamSide("TBD", "TBD", "TBD")
                var away = TeamSide("TBD", "TBD", "TBD")
                when (league.group) {
                    SportGroup.Golf, SportGroup.Racing -> {
                        val leaders = parseIndividualLeaders(competitors, golf = league.group == SportGroup.Golf)
                        leaders.getOrNull(0)?.let { away = it }
                        leaders.getOrNull(1)?.let { home = it }
                    }
                    else -> competitors.forEach { competitor ->
                        val side = parseSide(competitor)
                        if (competitor["homeAway"].string() == "home") home = side else away = side
                    }
                }
                val broadcasts = competition["broadcasts"].array
                    .flatMap { it["names"].array.map { name -> name.string() } }
                    .filter { it.isNotEmpty() }
                    .distinct()

                result += Match(
                    id = id,
                    league = league,
                    startMillis = start,
                    name = event["name"].string("Match"),
                    shortName = event["shortName"].string(event["name"].string("Match")),
                    state = state,
                    statusDetail = detail,
                    home = home,
                    away = away,
                    broadcasts = broadcasts,
                    venue = competition.path("venue", "fullName").stringOrNull(),
                    live = if (state == GameState.LIVE) liveContext(status, competition["situation"], league) else LiveContext.EMPTY,
                )
            }
            return result
        }

        private fun liveContext(status: JsonElement?, situation: JsonElement?, league: League): LiveContext {
            val lines = mutableListOf<String>()
            when (league.group) {
                SportGroup.Football -> {
                    situation["shortDownDistanceText"].stringOrNull()?.let(lines::add)
                    if (situation["isRedZone"].bool()) lines += "Red zone"
                }
                SportGroup.Baseball -> {
                    if (situation.isPresent) {
                        val outs = situation["outs"].int(-1)
                        if (outs >= 0) lines += "$outs ${if (outs == 1) "out" else "outs"}"
                        val balls = situation["balls"].int(-1)
                        val strikes = situation["strikes"].int(-1)
                        if (balls >= 0 && strikes >= 0) lines += "$balls-$strikes"
                        val bases = buildList {
                            if (situation["onFirst"].bool()) add("1st")
                            if (situation["onSecond"].bool()) add("2nd")
                            if (situation["onThird"].bool()) add("3rd")
                        }
                        if (bases.isNotEmpty()) lines += "Runners on ${bases.joinToString(", ")}"
                    }
                }
                else -> Unit
            }
            return LiveContext(
                clock = status["displayClock"].stringOrNull(),
                period = status["period"].int(0).takeIf { it > 0 }?.toString(),
                situation = lines,
            )
        }

        private fun selectCompetition(event: JsonElement?, league: League): JsonElement? {
            val competitions = event["competitions"].array
            if (competitions.isEmpty()) return null
            if (league.group != SportGroup.Racing) return competitions.first()
            // Racing weekends list several sessions; the headline one is the race.
            return competitions.firstOrNull { session ->
                val type = session["type"]
                (type["abbreviation"].string() + " " + type["text"].string()).lowercase().contains("race")
            } ?: competitions.last()
        }

        private fun parseSide(competitor: JsonElement?): TeamSide {
            val team = competitor["team"]
            val name = team["displayName"].string(team["name"].string("Team"))
            return TeamSide(
                displayName = name,
                shortName = team["shortDisplayName"].string(team["name"].string(name)),
                abbreviation = team["abbreviation"].string(name),
                logoUrl = logoUrl(team),
                score = competitor["score"].stringOrNull(),
                record = competitor["records"][0]["summary"].stringOrNull(),
                isWinner = competitor["winner"].bool(),
                teamId = team["id"].stringOrNull(),
                colorHex = team["color"].stringOrNull(),
            )
        }

        private fun parseIndividualLeaders(competitors: List<JsonElement>, golf: Boolean): List<TeamSide> {
            val ordered = competitors.sortedBy { it["order"].int(Int.MAX_VALUE) }
            val sides = ArrayList<TeamSide>(2)
            for (competitor in ordered) {
                val athlete = competitor["athlete"] ?: competitor["driver"] ?: continue
                val name = athlete["displayName"].string(athlete["fullName"].string("Competitor"))
                val order = competitor["order"].int(sides.size + 1)
                val logo = athlete.path("flag", "href").stringOrNull()
                    ?: if (!golf) logoUrl(competitor["manufacturer"]) else null
                sides += TeamSide(
                    displayName = name,
                    shortName = athlete["shortName"].string(name),
                    abbreviation = athlete["abbreviation"].string(athlete["shortName"].string(name)),
                    logoUrl = logo,
                    score = competitor["score"].stringOrNull() ?: competitor["displayValue"].stringOrNull(),
                    record = if (order in 1 until Int.MAX_VALUE) "#$order" else null,
                    teamId = athlete["id"].stringOrNull(),
                )
                if (sides.size == 2) break
            }
            return sides
        }

        private fun logoUrl(value: JsonElement?): String? {
            val url = value["logo"].stringOrNull() ?: value["logos"][0].let { it["href"].stringOrNull() ?: it["url"].stringOrNull() }
            return url?.let(::darkVariant)
        }

        private val DARK_CAPABLE_LOGO = Regex("""^(https://a\.espncdn\.com/i/teamlogos/(?:nhl|nba|wnba|nfl|mlb)/)500/(?:scoreboard/)?([^/]+\.png)$""")

        /**
         * Navy and black crests (Capitals, Spurs...) disappear on a dark screen. ESPN publishes a
         * `500-dark` set for the US leagues, verified for every team, so use it where it exists.
         */
        fun darkVariant(url: String): String = DARK_CAPABLE_LOGO.matchEntire(url)?.let { "${it.groupValues[1]}500-dark/${it.groupValues[2]}" } ?: url

        // endregion

        // region News / Teams

        fun parseNews(root: JsonElement?, league: League?): List<Article> =
            root["articles"].array.mapIndexedNotNull { index, value ->
                value.obj ?: return@mapIndexedNotNull null
                Article(
                    id = value["id"].stringOrNull() ?: "${league?.path}-news-$index",
                    headline = value["headline"].string("Sports update"),
                    description = value["description"].string(),
                    publishedMillis = Dates.parseIso(value["published"].stringOrNull()),
                    url = value.path("links", "web", "href").stringOrNull(),
                    imageUrl = value["images"][0]["url"].stringOrNull(),
                    league = league,
                    byline = value["byline"].stringOrNull(),
                    isPremium = value["premium"].bool(),
                    categories = value["categories"].array.mapNotNull { it["description"].stringOrNull() }.distinct().take(3),
                )
            }

        fun parseTeams(root: JsonElement?, league: League): List<Team> =
            root.path("sports", "0", "leagues", "0", "teams").array.mapIndexedNotNull { index, entry ->
                val value = entry["team"] ?: return@mapIndexedNotNull null
                val name = value["displayName"].string(value["name"].string("Team"))
                Team(
                    id = value["id"].stringOrNull() ?: "${league.path}-team-$index",
                    name = name,
                    shortName = value["shortDisplayName"].string(name),
                    abbreviation = value["abbreviation"].string(),
                    logoUrl = logoUrl(value),
                    league = league,
                    color = value["color"].stringOrNull(),
                )
            }.sortedBy { it.name.lowercase() }

        // endregion

        // region Standings / roster / leaders / injuries / athletes

        fun parseStandings(root: JsonElement?): List<StandingGroup> {
            val groups = mutableListOf<StandingGroup>()
            collectStandings(root, root["name"].string("Standings"), groups)
            return groups
        }

        private fun collectStandings(node: JsonElement?, inherited: String, out: MutableList<StandingGroup>) {
            val entries = node["standings"]["entries"].array
            if (entries.isNotEmpty()) {
                val rows = entries.mapNotNull { entry ->
                    val team = entry["team"] ?: return@mapNotNull null
                    val values = LinkedHashMap<String, String>()
                    entry["stats"].array.forEach { stat ->
                        val key = stat["type"].string(stat["name"].string())
                        val display = stat["displayValue"].string()
                        if (key.isNotEmpty() && display.isNotEmpty()) values[key.lowercase()] = display
                    }
                    var record = values["overall"] ?: values["total"].orEmpty()
                    if (record.isEmpty() && values["wins"] != null && values["losses"] != null) {
                        record = "${values["wins"]}-${values["losses"]}"
                    }
                    if (record.isEmpty()) record = values["points"] ?: values["winpercent"] ?: "—"
                    StandingRow(
                        teamId = team["id"].string(team["uid"].string()),
                        teamName = team["displayName"].string(team["name"].string("Team")),
                        abbreviation = team["abbreviation"].string(),
                        logoUrl = logoUrl(team),
                        record = record,
                        values = values,
                    )
                }
                if (rows.isNotEmpty()) out += StandingGroup(node["name"].string(inherited), rows, "ESPN")
            }
            node["children"].array.forEach { collectStandings(it, inherited, out) }
        }

        fun parseRoster(root: JsonElement?): List<RosterGroup> {
            val groups = mutableListOf<RosterGroup>()
            val flat = mutableListOf<Athlete>()
            root["athletes"].array.forEach { value ->
                val items = value["items"]
                if (items.isPresent) {
                    val athletes = items.array.mapNotNull(::athlete)
                    if (athletes.isNotEmpty()) groups += RosterGroup(value["position"].string("Players"), athletes)
                } else athlete(value)?.let(flat::add)
            }
            if (flat.isNotEmpty()) groups += RosterGroup("Roster", flat)
            return groups
        }

        private val LEADER_STATS = listOf(
            "avgPoints", "avgRebounds", "avgAssists", "goals", "assists", "points", "passingYards", "passingTouchdowns",
            "rushingYards", "receivingYards", "totalTackles", "sacks", "interceptions", "homeRuns", "RBIs",
            "battingAverage", "hits", "wins", "strikeouts", "ERA", "saves",
        )

        private class StatLocation(val category: String, val index: Int, val display: String)

        fun parseLeaders(root: JsonElement?, league: League): List<LeaderBoard> {
            val categories = root["categories"].array
            val athletes = root["athletes"].array
            if (categories.isEmpty() || athletes.isEmpty()) return emptyList()
            val locations = LinkedHashMap<String, StatLocation>()
            categories.forEach { category ->
                val categoryName = category["name"].string()
                val names = category["names"].array
                val displays = category["displayNames"].array
                names.forEachIndexed { index, name ->
                    locations[name.string()] = StatLocation(categoryName, index, displays.getOrNull(index).string(name.string()))
                }
            }
            val boards = mutableListOf<LeaderBoard>()
            for (stat in LEADER_STATS) {
                val location = locations[stat] ?: continue
                val ranked = athletes.mapNotNull { entry ->
                    val info = entry["athlete"] ?: return@mapNotNull null
                    val totals = entry["categories"].array.firstOrNull { it["name"].string() == location.category }?.get("totals").array
                    val raw = totals.getOrNull(location.index).string()
                    val numeric = raw.replace(",", "").toDoubleOrNull() ?: return@mapNotNull null
                    val athlete = athlete(info) ?: return@mapNotNull null
                    numeric to Triple(athlete.copy(headshotUrl = headshotUrl(league, athlete.id)), info["teamShortName"].string(), raw)
                }.sortedByDescending { it.first }
                if (ranked.size < 3) continue
                boards += LeaderBoard(
                    title = location.display,
                    rows = ranked.take(10).mapIndexed { index, (_, triple) ->
                        LeaderRow(triple.first, triple.second, triple.third, index + 1)
                    },
                )
                if (boards.size == 6) break
            }
            return boards
        }

        fun parseInjuries(root: JsonElement?): List<Injury> =
            root["injuries"].array.flatMap { group ->
                val team = group["displayName"].string()
                group["injuries"].array.mapNotNull { item ->
                    val athlete = athlete(item["athlete"]) ?: return@mapNotNull null
                    Injury(
                        athlete = athlete,
                        team = team,
                        status = item["status"].string("Unknown"),
                        detail = item["shortComment"].string(item["longComment"].string()),
                        date = item["date"].string(),
                        source = "ESPN",
                    )
                }
            }.sortedWith(
                compareByDescending<Injury> { it.status.lowercase().contains("out") }.thenBy { it.athlete.name.lowercase() },
            )

        fun parseAthleteOverview(root: JsonElement?, athlete: Athlete, league: League): AthleteOverview {
            val statistics = root["statistics"]
            val labels = statistics["displayNames"].array.ifEmpty { statistics["labels"].array }
            val values = statistics.path("splits", "0", "stats").array
            val stats = LinkedHashMap<String, String>()
            for (i in 0 until minOf(labels.size, values.size)) stats[labels[i].string()] = values[i].string()
            val news = root["news"].array.take(6).mapNotNull { item ->
                item.obj ?: return@mapNotNull null
                Article(
                    id = item["id"].string(),
                    headline = item["headline"].string(),
                    description = item["description"].string(),
                    publishedMillis = Dates.parseIso(item["published"].stringOrNull()),
                    url = item.path("links", "web", "href").stringOrNull(),
                    imageUrl = item["images"][0]["url"].stringOrNull(),
                    league = league,
                    byline = item["byline"].stringOrNull(),
                )
            }
            val shortLabels = statistics["labels"].array
            val headline = (0 until minOf(shortLabels.size, values.size, 4)).associate { shortLabels[it].string() to values[it].string() }.filterKeys(String::isNotBlank)
            return AthleteOverview(athlete, emptyMap(), stats, news,
                statlineLabel = statistics["displayName"].stringOrNull() ?: statistics.path("season", "displayName").stringOrNull(), headlineStats = headline)
        }

        fun parseField(root: JsonElement?, league: League): List<Racer> {
            val competitions = root.path("events", "0", "competitions").array
            if (competitions.isEmpty()) return emptyList()
            val selected = if (league.group == SportGroup.Racing) {
                competitions.firstOrNull { c -> (c["type"]["abbreviation"].string() + c["type"]["text"].string()).lowercase().contains("race") }
                    ?: competitions.last()
            } else competitions.last()
            return selected["competitors"].array.mapIndexedNotNull { index, value ->
                val info = value["athlete"] ?: value["driver"] ?: return@mapIndexedNotNull null
                val athlete = athlete(info) ?: return@mapIndexedNotNull null
                val order = value["order"].int(index + 1)
                Racer(
                    athlete = athlete,
                    rank = if (order > 0) order.toString() else "—",
                    score = value["score"].string(value["displayValue"].string()),
                    manufacturer = value["vehicle"]["manufacturer"].stringOrNull()
                        ?: value["vehicle"]["team"].stringOrNull()
                        ?: value["manufacturer"]["name"].stringOrNull()
                        ?: "Independent",
                )
            }.sortedBy { it.rank.toIntOrNull() ?: Int.MAX_VALUE }
        }

        fun athlete(value: JsonElement?): Athlete? {
            value.obj ?: return null
            val id = value["id"].string()
            val name = value["displayName"].string(value["fullName"].string())
            if (id.isEmpty() || name.isEmpty()) return null
            val details = buildList {
                if (value["age"].isPresent) add("Age ${value["age"].int()}")
                value["displayHeight"].stringOrNull()?.let(::add)
                value["displayWeight"].stringOrNull()?.let(::add)
                value["college"]["name"].stringOrNull()?.let(::add)
            }
            return Athlete(
                id = id,
                name = name,
                jersey = value["jersey"].string(),
                position = value["position"]["abbreviation"].string(value["position"]["displayName"].string()),
                headshotUrl = value["headshot"]["href"].stringOrNull(),
                details = details.joinToString(" • "),
                positionName = value["position"]["displayName"].stringOrNull(),
                age = value["age"].stringOrNull()?.toIntOrNull()?.takeIf { it > 0 },
                height = value["displayHeight"].stringOrNull(), weight = value["displayWeight"].stringOrNull(),
                college = value["college"]["name"].stringOrNull(), experienceYears = value["experience"]["years"].stringOrNull()?.toIntOrNull()?.takeIf { it >= 0 },
                birthPlace = listOf(value["birthPlace"]["city"].string(), value["birthPlace"]["state"].string(), value["birthPlace"]["country"].string()).filter(String::isNotBlank).joinToString(", ").takeIf(String::isNotBlank),
                injured = value["injured"].bool() || value["injuries"].array.isNotEmpty(),
            )
        }

        private fun headshotUrl(league: League, id: String): String? {
            val slug = when (league.path) {
                "football/nfl" -> "nfl"
                "basketball/nba" -> "nba"
                "baseball/mlb" -> "mlb"
                "hockey/nhl" -> "nhl"
                else -> if (league.group == SportGroup.Soccer) "soccer" else return null
            }
            return "https://a.espncdn.com/i/headshots/$slug/players/full/$id.png"
        }

        // endregion
    }
}
