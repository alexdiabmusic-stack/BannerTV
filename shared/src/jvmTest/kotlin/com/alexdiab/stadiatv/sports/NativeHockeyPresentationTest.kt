package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeHockeyPresentationTest {
    private val match = Match("nhl:2023020204", League.find("hockey/nhl")!!, 1699660800000, "Wild at Sabres", "MIN @ BUF", GameState.FINAL, "Final",
        TeamSide("Buffalo Sabres", "Sabres", "BUF", teamId = "nhl:7"), TeamSide("Minnesota Wild", "Wild", "MIN", teamId = "nhl:30"))
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/nhl-$name.recorded.json")!!.readText())
    private fun player(id: String, team: String, group: String, vararg stats: Pair<String, String>) = HockeyPlayerStats(Athlete("nhl:$id", "Player $id"), team, group, stats.map { HockeyStat(it.first, it.first, it.second) })

    @Test fun recordedBoxScoreSuppliesFullPlayersAndFourNativeLeaders() {
        val pbp = fixture("final-play-by-play"); val box = fixture("final-boxscore")
        val roster = pbp["rosterSpots"].array.associateBy { it["playerId"].string() }
        val players = NativeHockeyPresentation.players(box, roster, match)
        assertTrue(players.size >= 36); assertTrue(players.all { it.player.id.startsWith("nhl:") })
        assertTrue(players.any { it.group == "Defensemen" && it.stats.any { stat -> stat.key == "shifts" } })
        val leaders = NativeHockeyPresentation.leaders(players, match)
        assertTrue(leaders.size in 2..4); assertTrue(leaders.all { it.player.name != "Unknown player" })
        assertEquals(setOf("MIN", "BUF"), leaders.filter { it.role == "GOALIE" }.map { it.team }.toSet())
        assertTrue(leaders.any { it.player.name == "Kirill Kaprizov" })
    }
    @Test fun skaterRankingUsesPointsThenGoalsThenShotsAndNoScorelessLeaders() {
        val players = listOf(player("1", "MIN", "Forwards", "points" to "2", "goals" to "0", "sog" to "10"),
            player("2", "MIN", "Forwards", "points" to "2", "goals" to "1", "assists" to "1", "sog" to "2"),
            player("3", "MIN", "Defensemen", "points" to "2", "goals" to "1", "assists" to "1", "sog" to "4"),
            player("4", "BUF", "Forwards", "points" to "0", "goals" to "0"))
        val result = NativeHockeyPresentation.leaders(players, match)
        assertEquals(1, result.size); assertEquals("nhl:3", result.single().player.id); assertEquals("1 G · 1 A", result.single().line)
    }
    @Test fun goalieUsesShotsFacedNotSavePercentageOrBenchAppearance() {
        val players = listOf(player("1", "MIN", "Goalies", "shotsAgainst" to "20", "saves" to "17", "savePctg" to ".850"),
            player("2", "MIN", "Goalies", "shotsAgainst" to "10", "saves" to "10", "savePctg" to "1.000"),
            player("3", "BUF", "Goalies", "shotsAgainst" to "0", "saves" to "0"))
        val result = NativeHockeyPresentation.leaders(players, match)
        assertEquals(1, result.size); assertEquals("nhl:1", result.single().player.id); assertEquals("17 SV · .850", result.single().line)
    }
    @Test fun keyStatsHaveIosOrderPowerPlaySecondaryPercentAndGiveawaysMeaning() {
        val raw = listOf(StatComparison("PIM", "2", "4", "pim"), StatComparison("Giveaways", "8", "4", "giveaways"),
            StatComparison("PP%", "33.3%", "50%", "powerPlayPctg"), StatComparison("PP", "1/3", "1/2", "powerPlay"),
            StatComparison("Shots", "20", "30", "sog"), StatComparison("Hits", "10", "11", "hits"))
        val rows = NativeHockeyPresentation.keyStats(raw)
        assertEquals(listOf("sog", "powerPlay", "hits", "giveaways"), rows.map { it.id })
        assertEquals("33.3%", rows[1].awaySecondary); assertEquals("50%", rows[1].homeSecondary); assertTrue(rows.last().lowerIsBetter)
    }
    @Test fun teamStatsPreferReportedShotsAndAggregateOnlyRealPlayerValues() {
        val players = listOf(player("1", "MIN", "Forwards", "hits" to "4", "giveaways" to "1"), player("2", "MIN", "Defensemen", "hits" to "3"), player("3", "BUF", "Forwards", "hits" to "5"))
        val result = NativeHockeyPresentation.teamStats(listOf(StatComparison("Shots", "99", "99", "sog")), parseJson("""{"awayTeam":{"sog":24},"homeTeam":{"sog":18}}"""), players, match)
        assertEquals("24", result.first().away); assertEquals("18", result.first().home)
        assertEquals("7", result.single { it.id == "hits" }.away); assertEquals("5", result.single { it.id == "hits" }.home)
        assertFalse(result.any { it.id == "giveaways" }) // Opponent did not supply that field; don't invent zero.
    }
    @Test fun emptyFreshBoxClearsOldLeadersButFailedBoxPreservesThem() {
        val pbp = fixture("final-play-by-play"); val landing = fixture("final-landing"); val box = fixture("final-boxscore")
        val old = NativeHockey.parse(pbp, landing, box, null, match, 100)!!
        val failed = NativeHockey.reduce(old, NativeHockey.parse(pbp, landing, null, null, match, 101)!!)
        assertEquals(old.hockey!!.leaders, failed.hockey!!.leaders)
        val emptyBox = JsonObject(box.jsonObject + ("playerByGameStats" to JsonObject(emptyMap())))
        val fresh = NativeHockey.reduce(old, NativeHockey.parse(pbp, landing, emptyBox, null, match, 102)!!)
        assertTrue(fresh.hockey!!.players.isEmpty()); assertTrue(fresh.hockey!!.leaders.isEmpty())
    }
    @Test fun playerRatiosUseHumanFormattingWithoutChangingUnknownValues() {
        assertEquals("50.0%", NativeHockeyPresentation.statValue("faceoffWinningPctg", "0.500000"))
        assertEquals("15.0%", NativeHockeyPresentation.statValue("shootingPctg", "0.15"))
        assertEquals(".920", NativeHockeyPresentation.statValue("savePctg", "0.920000"))
        assertEquals("1.000", NativeHockeyPresentation.statValue("savePctg", "1"))
        assertEquals("50%", NativeHockeyPresentation.statValue("faceoffWinningPctg", "50%"))
        assertEquals("unknown", NativeHockeyPresentation.statValue("savePctg", "unknown"))
    }
}
