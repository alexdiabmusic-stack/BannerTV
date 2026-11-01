package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeSportsParityTest {
    private val baseball = Match("espn-1", League.find("baseball/mlb")!!, 0, "Mets at Nationals", "NYM @ WSH", GameState.LIVE, "", TeamSide("Nationals", "Nationals", "WSH", teamId = "20"), TeamSide("Mets", "Mets", "NYM", teamId = "21"))
    private fun fixture(): JsonElement = parseJson(javaClass.getResource("/parity/mlb-live-late.json")!!.readText())

    @Test fun `MLB native fixture retains all at bats real pitch geometry and provider scoped identity`() {
        val game = NativeBaseball.parse(fixture(), baseball, 1000)!!
        assertEquals("744834", game.providerStamp!!.gameId)
        assertEquals("espn-1", game.liveMatch!!.id)
        assertEquals(61, game.baseball!!.atBats.size)
        assertTrue(game.baseball!!.atBats.flatMap { it.pitches }.any { it.location != null })
        assertTrue(game.boxScore.isNotEmpty())
        assertTrue(game.boxScore.flatMap { it.groups }.flatMap { it.rows }.all { it.athleteId?.startsWith("mlb:") == true && it.profileUrl?.startsWith("https://www.mlb.com/player/") == true })
    }

    @Test fun `MLB wrong team response is not accepted even when its game id is numeric`() {
        assertNull(NativeBaseball.parse(fixture(), baseball.copy(home = baseball.home.copy(abbreviation = "BOS")), 1000))
    }

    @Test fun `MLB reducer rejects outdated timestamp inning rollback truncated current AB and final rollback`() {
        val old = NativeBaseball.parse(fixture(), baseball, 1000)!!
        assertSame(old, NativeBaseball.reduce(old, old.copy(providerStamp = old.providerStamp!!.copy(requestedAt = 999))))
        assertSame(old, NativeBaseball.reduce(old, old.copy(baseball = old.baseball!!.copy(inning = 1))))
        assertSame(old, NativeBaseball.reduce(old, old.copy(baseball = old.baseball!!.copy(atBats = old.baseball!!.atBats.dropLast(1)))))
        val final = old.copy(providerStamp = old.providerStamp!!.copy(final = true))
        assertSame(final, NativeBaseball.reduce(final, old))
        val corrected = old.copy(liveMatch = old.liveMatch!!.copy(home = old.liveMatch!!.home.copy(score = "0")), providerStamp = old.providerStamp!!.copy(requestedAt = 1001))
        assertEquals("0", NativeBaseball.reduce(old, corrected).liveMatch!!.home.score)
    }

    @Test fun `MLB between innings clears runners rather than displaying prior occupied bases`() {
        val root = fixture().jsonObject; val live = root["liveData"]!!.jsonObject; val line = live["linescore"]!!.jsonObject
        val changed = JsonObject(root + ("liveData" to JsonObject(live + ("linescore" to JsonObject(line + mapOf("inningState" to JsonPrimitive("Middle"), "outs" to JsonPrimitive(3)))))))
        val game = NativeBaseball.parse(changed, baseball, 1000)!!.baseball!!
        assertTrue(game.betweenInnings); assertTrue(game.bases.all { it == null }); assertEquals("Bases empty", game.baseDescription)
    }

    @Test fun `MLB current duplicate cannot regress completed at bat or fabricate missing pitch location`() {
        val raw = parseJson("""{"allPlays":[{"about":{"atBatIndex":1,"inning":1,"halfInning":"top","isComplete":true},"playEvents":[{"isPitch":true,"index":0,"pitchNumber":1,"pitchData":{"strikeZoneTop":3.5,"strikeZoneBottom":1.5}}]}],"currentPlay":{"about":{"atBatIndex":1,"isComplete":false}}}""")
        val atBat = NativeBaseball.atBats(raw, "1").single()
        assertTrue(atBat.complete); assertNull(atBat.pitches.single().location)
    }

    @Test fun `NHL four digit situation validates goalie and skater positions`() {
        assertEquals("5-on-4", NativeHockey.situation("1541")!!.strength)
        assertEquals(true, NativeHockey.situation("0651")!!.awayGoaliePulled)
        assertNull(NativeHockey.situation("1001")); assertNull(NativeHockey.situation("154")); assertNull(NativeHockey.situation("1x41"))
    }

    @Test fun `football full history includes plays nested in previous and current drives`() {
        val root = parseJson("""{"drives":{"previous":[{"id":"a","plays":[{"id":"1","text":"Run","period":{"number":1}}]}],"current":{"id":"b","plays":[{"id":"2","text":"Pass","period":{"number":2},"end":{"down":2,"distance":4,"yardsToEndzone":20,"team":{"id":"1"}}}]}}}""")
        val match = baseball.copy(league = League.find("football/nfl")!!)
        val result = EspnSummary.parse(root, match)
        assertEquals(listOf("2", "1"), result.plays.map { it.id })
        assertEquals(80f, result.football!!.field!!.yardsFromOwnGoal!!, 0f)
        assertEquals(4, result.football!!.field!!.yardsToFirst)
    }

    @Test fun `soccer fallback does not invent pitch rows from an unstructured formation`() {
        val root = parseJson("""{"rosters":[{"team":{"id":"20"},"formation":"4-3-3","roster":[{"starter":true,"jersey":"1","athlete":{"id":"7","displayName":"Keeper"}}]}],"commentary":[{"sequence":1,"text":"Kick off","time":{"displayValue":"1'"}}]}""")
        val result = EspnSummary.parse(root, baseball.copy(league = League.find("soccer/eng.1")!!))
        val lineup = result.soccer!!.lineups.single()
        assertEquals("4-3-3", lineup.formation); assertFalse(lineup.hasPitch); assertEquals("Keeper", lineup.players.single().player.name)
        assertEquals("Kick off", result.soccer!!.commentary.single().text)
    }

    @Test fun `NFL native GSIS totals match the iOS gamebook assertions`() {
        val raw = parseJson(javaClass.getResource("/parity/nfl-regular-final.json")!!.readText()).array.single()
        val match = baseball.copy(league = League.find("football/nfl")!!, home = TeamSide("Philadelphia Eagles", "Eagles", "PHI", teamId = "21"), away = TeamSide("Dallas Cowboys", "Cowboys", "DAL", teamId = "6"))
        val game = NativeFootball.parse(raw, match, 1000)!!
        assertTrue(game.providerStamp!!.final)
        val passing = game.boxScore.first { it.abbreviation == "DAL" }.groups.first { it.title == "Passing" }.rows
        val prescott = passing.first { it.name.contains("Prescott", true) }
        assertEquals("21/34", prescott.stats[0]); assertEquals("188", prescott.stats[1])
        val hurts = game.boxScore.first { it.abbreviation == "PHI" }.groups.first { it.title == "Rushing" }.rows.first { it.name.contains("Hurts", true) }
        assertEquals(listOf("14", "62", "2"), hurts.stats)
        val old = game.copy(providerStamp = game.providerStamp!!.copy(sequence = 200))
        assertSame(old, NativeFootball.reduce(old, game.copy(providerStamp = game.providerStamp!!.copy(sequence = 100, requestedAt = 2000))))
    }

    @Test fun `NFL field position requires possession and known team abbreviation`() {
        assertEquals(85f, NativeFootball.field("DAL 15", "home", "PHI", "DAL", "PHI", 1, 10, false)!!.yardsFromOwnGoal!!, 0f)
        assertEquals(15f, NativeFootball.field("PHI 15", "home", "PHI", "DAL", "PHI", 1, 10, false)!!.yardsFromOwnGoal!!, 0f)
        assertNull(NativeFootball.field("FUTURE 15", "home", "PHI", "DAL", "PHI", 1, 10, false)!!.yardsFromOwnGoal)
        assertNull(NativeFootball.field("DAL 15", null, null, "DAL", "PHI", 1, 10, false)!!.yardsFromOwnGoal)
    }

    @Test fun `EPL pitch uses native row membership and commentary deduplicates language copies`() {
        val lineup = NativeSoccer.lineup(parseJson("""{"players":[{"id":"g","firstName":"Goal","lastName":"Keeper"},{"id":"d","lastName":"Defender"},{"id":"s","lastName":"Sub"}],"formation":{"formation":"1-1","lineup":[["g"],["d"]],"subs":["s"]}}"""), true)!!
        assertTrue(lineup.hasPitch); assertEquals(0, lineup.players.first().row); assertFalse(lineup.players.last().starter)
        val comments = NativeSoccer.comments(parseJson("""{"data":[{"timestamp":"2026-10-17 15:00:00","type":"goal","comment":"Goal"},{"timestamp":"2026-10-17 15:00:00","type":"goal","comment":"Gol"}]}"""), "m")
        assertEquals(1, comments.size); assertEquals("Goal", comments.single().text)
    }

    private fun nba(): GameCentre {
        val game = parseJson(javaClass.getResource("/parity/boxscore-live.constructed.json")!!.readText())["game"]!!
        val pbp = parseJson(javaClass.getResource("/parity/playbyplay-live.constructed.json")!!.readText())["game"]!!
        val match = baseball.copy(league = League.find("basketball/nba")!!, home = TeamSide("Los Angeles Lakers", "Lakers", "LAL", teamId = "13"), away = TeamSide("Boston Celtics", "Celtics", "BOS", teamId = "2"))
        return NativeBasketball.parse(game, pbp, match, 1000)!!
    }

    @Test fun `NBA native fixture retains on court bonus timeouts starter DNP and every action`() {
        val data = nba()
        assertEquals("5:12", data.liveMatch!!.live.clock)
        assertEquals(11, data.plays.size)
        assertEquals(true, data.basketball!!.homeBonus)
        assertEquals(4, data.basketball!!.homeTimeouts)
        assertEquals(3, data.basketball!!.onCourt.size)
        val rows = data.boxScore.last().groups.single().rows
        assertTrue(rows.first().starter); assertTrue(rows.last().didNotPlay)
        assertEquals("28", rows.first().stats.first()) // Current iOS BasketballBoxScoreView uses whole minutes.
        assertEquals("Jayson Tatum", data.leaders.first().away!!.name)
    }

    @Test fun `basketball corrected non shot removes its plotted point while stable play identity is retained`() {
        val old = nba(); val shotPlay = old.plays.first { it.shot != null }
        val corrected = old.copy(plays = old.plays.map { if (it.id == shotPlay.id) it.copy(shot = null, text = "Overturned") else it }, providerStamp = old.providerStamp!!.copy(requestedAt = 1001))
        val reduced = NativeBasketball.reduce(old, corrected)
        assertEquals(old.plays.size, reduced.plays.size)
        assertEquals("Overturned", reduced.plays.first { it.id == shotPlay.id }.text)
        assertFalse(reduced.shots.any { it.id == shotPlay.shot!!.id })
    }

    @Test fun `basketball rejects clock only rollback but accepts corroborated official review`() {
        val old = nba()
        val lateClock = old.copy(liveMatch = old.liveMatch!!.copy(live = old.liveMatch!!.live.copy(clock = "6:00")), providerStamp = old.providerStamp!!.copy(requestedAt = 1001))
        assertSame(old, NativeBasketball.reduce(old, lateClock))
        val corrected = lateClock.copy(liveMatch = lateClock.liveMatch!!.copy(home = lateClock.liveMatch!!.home.copy(score = "80")))
        assertEquals("6:00", NativeBasketball.reduce(old, corrected).liveMatch!!.live.clock)
    }
}
