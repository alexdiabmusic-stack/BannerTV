package com.alexdiab.stadiatv.data.odds

import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.net.array
import com.alexdiab.stadiatv.net.get
import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OddsServiceTest {
    private fun side(name: String, short: String, abbreviation: String) = TeamSide(name, short, abbreviation)

    private fun match(league: String, away: TeamSide, home: TeamSide) = Match(
        id = "1", league = League.find(league)!!, startMillis = 0, name = "", shortName = "", state = GameState.PRE,
        statusDetail = "", home = home, away = away,
    )

    private val nhl = match(
        "hockey/nhl",
        side("Montreal Canadiens", "Canadiens", "MTL"),
        side("Washington Capitals", "Capitals", "WSH"),
    )

    private fun events(json: String) = parseJson(json)["data"].array

    private val listing = """
        {"success": true, "data": [
          {"eventId": "e1", "bookmakers": [{"bookmakerId": "fanduel", "bookmakerName": "FanDuel", "sourceType": "sportsbook",
            "markets": [{"marketType": "moneyline", "outcomes": [{"name": "Dallas Stars", "price": 132}, {"name": "Colorado Avalanche", "price": -160}]}]}]},
          {"eventId": "e2", "bookmakers": [
            {"bookmakerId": "fanduel", "bookmakerName": "FanDuel", "sourceType": "sportsbook",
              "markets": [{"marketType": "moneyline", "outcomes": [{"name": "Montréal Canadiens", "price": 101}, {"name": "Washington Capitals", "price": -122}]}]},
            {"bookmakerId": "draftkings", "bookmakerName": "DraftKings", "sourceType": "sportsbook",
              "markets": [{"marketType": "moneyline", "outcomes": [{"name": "Montreal Canadiens", "price": 105}, {"name": "Washington Capitals", "price": -126}]}]}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `finds the event for a match despite accents`() {
        val event = OddsService.bestEvent(nhl, events(listing))
        assertEquals("e2", event["eventId"].let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun `prefers DraftKings and maps each price to its side`() {
        val event = OddsService.bestEvent(nhl, events(listing))
        val odds = OddsService.display(event, nhl)!!
        assertEquals("DraftKings", odds.bookmaker)
        assertEquals(105, odds.away)
        assertEquals(-126, odds.home)
        assertNull(odds.draw)
    }

    @Test
    fun `a match no event mentions has no odds`() {
        val unrelated = match("hockey/nhl", side("Boston Bruins", "Bruins", "BOS"), side("San Jose Sharks", "Sharks", "SJ"))
        assertNull(OddsService.bestEvent(unrelated, events(listing)))
    }

    @Test
    fun `a two letter code does not match inside another name`() {
        val saints = match("football/nfl", side("New Orleans Saints", "Saints", "NO"), side("Houston Texans", "Texans", "HOU"))
        val json = """{"data": [{"eventId": "x", "bookmakers": [{"bookmakerId": "b", "bookmakerName": "B", "markets": [
            {"marketType": "moneyline", "outcomes": [{"name": "Denver Broncos", "price": 110}, {"name": "Kansas City Chiefs", "price": -130}]}]}]}]}"""
        assertNull(OddsService.bestEvent(saints, events(json)))
    }

    @Test
    fun `soccer style three way markets keep the draw`() {
        val arsenal = match("soccer/eng.1", side("Arsenal", "Arsenal", "ARS"), side("Chelsea", "Chelsea", "CHE"))
        val json = """{"data": [{"eventId": "s", "bookmakers": [{"bookmakerId": "draftkings", "bookmakerName": "DraftKings", "markets": [
            {"marketType": "moneyline", "outcomes": [{"name": "Arsenal", "price": 140}, {"name": "Draw", "price": 230}, {"name": "Chelsea", "price": 190}]}]}]}]}"""
        val odds = OddsService.display(events(json).first(), arsenal)!!
        assertEquals(140, odds.away)
        assertEquals(230, odds.draw)
        assertEquals(190, odds.home)
    }

    @Test
    fun `prices print the way sportsbooks do`() {
        assertEquals("+105", formatAmericanOdds(105))
        assertEquals("-126", formatAmericanOdds(-126))
    }

    @Test
    fun `leagues the API does not cover have no id`() {
        assertEquals("nhl", OddsService.leagueId(League.find("hockey/nhl")!!))
        assertNull(OddsService.leagueId(League.find("soccer/eng.1")!!))
    }
}
