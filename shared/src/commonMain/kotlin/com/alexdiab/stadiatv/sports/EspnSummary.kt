package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.BoxGroup
import com.alexdiab.stadiatv.domain.BoxRow
import com.alexdiab.stadiatv.domain.GameCentre
import com.alexdiab.stadiatv.domain.LeaderComparison
import com.alexdiab.stadiatv.domain.LeaderEntry
import com.alexdiab.stadiatv.domain.LineScore
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.Play
import com.alexdiab.stadiatv.domain.SportGroup
import com.alexdiab.stadiatv.domain.StatComparison
import com.alexdiab.stadiatv.domain.TeamBox
import com.alexdiab.stadiatv.domain.GameDrive
import com.alexdiab.stadiatv.domain.FootballDetail
import com.alexdiab.stadiatv.domain.FootballField
import com.alexdiab.stadiatv.domain.SoccerDetail
import com.alexdiab.stadiatv.domain.SoccerLineup
import com.alexdiab.stadiatv.domain.SoccerLineupPlayer
import com.alexdiab.stadiatv.domain.SoccerComment
import com.alexdiab.stadiatv.domain.SportPlayer
import com.alexdiab.stadiatv.domain.ShootoutKick
import com.alexdiab.stadiatv.domain.GameVideo
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import com.alexdiab.stadiatv.net.double
import com.alexdiab.stadiatv.net.array
import com.alexdiab.stadiatv.net.bool
import com.alexdiab.stadiatv.net.get
import com.alexdiab.stadiatv.net.int
import com.alexdiab.stadiatv.net.isPresent
import com.alexdiab.stadiatv.net.path
import com.alexdiab.stadiatv.net.string
import com.alexdiab.stadiatv.net.stringOrNull
import kotlinx.serialization.json.JsonElement

/** Parses ESPN's `summary` payload into a [GameCentre]. Every sport uses the same envelope with different richness. */
object EspnSummary {
    fun parse(root: JsonElement?, match: Match): GameCentre {
        if (root == null) return GameCentre.EMPTY
        return GameCentre(
            lineScore = lineScore(root, match),
            teamStats = teamStats(root, match),
            leaders = leaders(root, match),
            plays = plays(root, match),
            boxScore = boxScore(root),
            headline = root.path("article", "headline").stringOrNull(),
            gameInfo = buildList {
                root.path("gameInfo", "venue", "fullName").stringOrNull()?.let { add("Venue" to it) }
                root.path("gameInfo", "attendance").stringOrNull()?.let { add("Attendance" to it) }
                root.path("gameInfo", "weather", "displayValue").stringOrNull()?.let { add("Weather" to it) }
                val officials = root.path("gameInfo", "officials").array.mapNotNull { it["displayName"].stringOrNull() }
                if (officials.isNotEmpty()) add("Officials" to officials.joinToString(" · "))
            },
            drives = (root.path("drives", "previous").array + listOfNotNull(root.path("drives", "current"))).mapIndexed { index, drive ->
                GameDrive(drive["id"].string("drive-$index"), drive.path("team", "abbreviation").string(), drive["result"].string(), drive["description"].string(), drive["offensivePlays"].int(), drive["yards"].string(), drive.path("timeElapsed", "displayValue").string())
            }.associateBy { it.id }.values.toList(),
            winProbability = root["winprobability"].array.mapNotNull { point -> point["homeWinPercentage"].double(Double.NaN).takeIf { it.isFinite() && it in 0.0..1.0 }?.toFloat() },
            football = football(root, match),
            soccer = soccer(root, match),
            videos = root["videos"].array.mapNotNull { video ->
                val url = video.path("links", "web", "href").stringOrNull() ?: return@mapNotNull null
                if (!url.startsWith("https://")) return@mapNotNull null
                GameVideo(video["id"].string(url), video["headline"].string("Watch highlight"), url, video["thumbnail"].stringOrNull())
            },
        )
    }

    // region Line score

    private fun lineScore(root: JsonElement, match: Match): LineScore? {
        val competitors = root.path("header", "competitions", "0", "competitors").array
        if (competitors.isEmpty()) return null
        val away = competitors.firstOrNull { it["homeAway"].string() == "away" } ?: competitors.getOrNull(1)
        val home = competitors.firstOrNull { it["homeAway"].string() == "home" } ?: competitors.getOrNull(0)
        val awayScores = away["linescores"].array.map { it["displayValue"].string("-") }
        val homeScores = home["linescores"].array.map { it["displayValue"].string("-") }
        val count = maxOf(awayScores.size, homeScores.size)
        if (count == 0) return null
        return LineScore(
            periods = periodLabels(match.league.group, count),
            away = awayScores + List(count - awayScores.size) { "-" },
            home = homeScores + List(count - homeScores.size) { "-" },
            awayTotal = away["score"].string(match.away.score.orEmpty()),
            homeTotal = home["score"].string(match.home.score.orEmpty()),
            awayLabel = match.away.abbreviation,
            homeLabel = match.home.abbreviation,
        )
    }

    fun periodLabels(group: SportGroup, count: Int): List<String> = List(count) { index ->
        when (group) {
            SportGroup.Hockey -> if (index < 3) "${index + 1}" else if (index == 3) "OT" else "SO"
            SportGroup.Basketball, SportGroup.Football -> if (index < 4) "${index + 1}" else if (index == 4) "OT" else "OT${index - 3}"
            SportGroup.Soccer -> when (index) { 0 -> "1H"; 1 -> "2H"; 2 -> "ET1"; 3 -> "ET2"; else -> "PK" }
            else -> "${index + 1}"
        }
    }

    // endregion

    // region Team stats and leaders

    private fun teamStats(root: JsonElement, match: Match): List<StatComparison> {
        val teams = root.path("boxscore", "teams").array
        if (teams.size < 2) return emptyList()
        val away = teams.firstOrNull { it.path("team", "id").string() == match.away.teamId } ?: teams.firstOrNull { it["homeAway"].string() == "away" } ?: teams[0]
        val home = teams.firstOrNull { it.path("team", "id").string() == match.home.teamId } ?: teams.firstOrNull { it["homeAway"].string() == "home" } ?: teams[1]
        val homeByName = home["statistics"].array.associateBy { it["name"].string() }
        return away["statistics"].array.mapNotNull { stat ->
            val name = stat["name"].string()
            val awayValue = stat["displayValue"].stringOrNull() ?: return@mapNotNull null
            val homeValue = homeByName[name]?.get("displayValue").stringOrNull() ?: return@mapNotNull null
            val label = stat["label"].stringOrNull() ?: stat["displayName"].stringOrNull() ?: name.replaceFirstChar(Char::uppercase)
            StatComparison(label, awayValue, homeValue)
        }.distinctBy { it.label }
    }

    private fun leaders(root: JsonElement, match: Match): List<LeaderComparison> {
        val teams = root["leaders"].array
        if (teams.isEmpty()) return emptyList()
        fun entries(side: JsonElement?): Map<String, LeaderEntry> {
            val out = LinkedHashMap<String, LeaderEntry>()
            side["leaders"].array.forEach { category ->
                val top = category["leaders"][0] ?: return@forEach
                val athlete = top["athlete"]
                val name = athlete["displayName"].stringOrNull() ?: return@forEach
                val label = category["displayName"].stringOrNull() ?: category["name"].string().replaceFirstChar(Char::uppercase)
                out[label] = LeaderEntry(
                    name = name,
                    value = top["displayValue"].string(),
                    detail = top["summary"].stringOrNull() ?: athlete.path("position", "abbreviation").string(),
                    headshotUrl = athlete.path("headshot", "href").stringOrNull(),
                )
            }
            return out
        }
        val away = entries(teams.firstOrNull { it.path("team", "id").string() == match.away.teamId } ?: teams.getOrNull(0))
        val home = entries(teams.firstOrNull { it.path("team", "id").string() == match.home.teamId } ?: teams.getOrNull(1))
        return (away.keys + home.keys).distinct().take(6).map { LeaderComparison(it, away[it], home[it]) }
    }

    // endregion

    // region Plays

    private fun plays(root: JsonElement, match: Match): List<Play> {
        val drivePlays = (root.path("drives", "previous").array + listOfNotNull(root.path("drives", "current"))).flatMap { it["plays"].array }
        val raw = root["plays"].array.ifEmpty { drivePlays }.ifEmpty { root["keyEvents"].array }
        if (raw.isEmpty()) return emptyList()
        return raw.mapIndexedNotNull { index, play ->
            val text = play["text"].stringOrNull() ?: play["shortText"].stringOrNull() ?: play.path("type", "text").stringOrNull() ?: return@mapIndexedNotNull null
            Play(
                id = play["id"].stringOrNull() ?: "play-$index",
                period = periodText(play, match),
                clock = play.path("clock", "displayValue").string(),
                text = text,
                teamId = play.path("team", "id").stringOrNull(),
                awayScore = play["awayScore"].stringOrNull(),
                homeScore = play["homeScore"].stringOrNull(),
                isScoring = play["scoringPlay"].bool() || play["scoreValue"].int() > 0,
                type = play.path("type", "text").string(),
                periodNumber = play.path("period", "number").int(),
                sortOrder = index.toDouble(),
            )
        }.associateBy { it.id }.values.toList().takeLast(2_000).reversed()
    }

    private fun periodText(play: JsonElement, match: Match): String {
        val number = play.path("period", "number").int(0)
        if (number <= 0) return play.path("period", "displayValue").string()
        return periodLabels(match.league.group, number).last().let { label -> if (match.league.group == SportGroup.Baseball) "Inning $number" else label }
    }

    // endregion

    // region Box score

    private fun boxScore(root: JsonElement): List<TeamBox> =
        root.path("boxscore", "players").array.mapNotNull { team ->
            val groups = team["statistics"].array.mapNotNull { group ->
                val labels = group["labels"].array.map { it.string() }
                val rows = group["athletes"].array.mapNotNull { entry ->
                    val athlete = entry["athlete"]
                    val name = athlete["displayName"].stringOrNull() ?: return@mapNotNull null
                    BoxRow(name, athlete.path("position", "abbreviation").string(), entry["stats"].array.map { it.string() }, athlete["id"].stringOrNull(), athlete.path("headshot", "href").stringOrNull(), entry["starter"].bool(), entry["didNotPlay"].bool())
                }
                if (rows.isEmpty()) return@mapNotNull null
                val title = group["text"].stringOrNull() ?: group["name"].string().replaceFirstChar(Char::uppercase)
                BoxGroup(title, labels, rows, group["totals"].array.map { it.string() })
            }
            if (groups.isEmpty()) null
            else TeamBox(team.path("team", "displayName").string("Team"), team.path("team", "abbreviation").string(), groups)
        }

    // endregion

    private fun football(root: JsonElement, match: Match): FootballDetail? {
        if (match.league.group != SportGroup.Football) return null
        val situation = root["situation"] ?: root.path("header", "competitions", "0", "situation")
        val last = (root.path("drives", "previous").array + listOfNotNull(root.path("drives", "current"))).flatMap { it["plays"].array }.lastOrNull()
        val field = last["end"]
        val distanceToGoal = (situation["yardsToEndzone"] ?: field["yardsToEndzone"]).stringOrNull()?.toFloatOrNull()?.takeIf { it in 0f..100f }
        val down = (situation["down"] ?: field["down"]).stringOrNull()?.toIntOrNull()?.takeIf { it in 1..4 }
        return FootballDetail(
            if (down != null) FootballField(situation["possession"].stringOrNull() ?: field.path("team", "id").stringOrNull(), situation["possessionText"].string(), distanceToGoal?.let { 100 - it }, (situation["distance"] ?: field["distance"]).stringOrNull()?.toIntOrNull(), down, situation["isGoalToGo"].bool()) else null,
            root.path("header", "season", "year").stringOrNull()?.toIntOrNull(), root.path("header", "season", "type").stringOrNull(), root.path("header", "week").stringOrNull()?.toIntOrNull(),
        )
    }

    private fun soccer(root: JsonElement, match: Match): SoccerDetail? {
        if (match.league.group != SportGroup.Soccer) return null
        val lineups = root["rosters"].array.mapNotNull { team ->
            val id = team.path("team", "id").stringOrNull() ?: return@mapNotNull null
            if (id != match.home.teamId && id != match.away.teamId) return@mapNotNull null
            val players = team["roster"].array.mapNotNull { row ->
                val athlete = row["athlete"]; val playerId = athlete["id"].stringOrNull() ?: return@mapNotNull null
                val player = SportPlayer(playerId, athlete["displayName"].string("Unknown player"), row["jersey"].string(), row.path("position", "displayName").string(), athlete["links"].array.firstOrNull()?.get("href").stringOrNull())
                SoccerLineupPlayer(player, row["starter"].bool(), row["captain"].bool())
            }
            SoccerLineup(id == match.home.teamId, team["formation"].stringOrNull(), players)
        }
        val comments = root["commentary"].array.mapIndexedNotNull { index, row ->
            val text = row["text"].stringOrNull() ?: return@mapIndexedNotNull null
            SoccerComment("espn:${match.id}:${row["sequence"].string("$index")}", row.path("time", "displayValue").string(), text, row.path("type", "text").string(), Dates.parseIso(row["wallclock"].string()))
        }.associateBy { it.id }.values.toList().asReversed().take(500)
        val kicks = root["keyEvents"].array.filter { it["shootout"].bool() }.mapIndexed { index, row -> ShootoutKick(row["id"].string("kick-$index"), row.path("team", "id").string() == match.home.teamId, row["participants"].array.firstOrNull().path("athlete", "displayName").string(), row["scoringPlay"].bool(), index) }
        return SoccerDetail(lineups, comments, shootout = kicks)
    }
}
