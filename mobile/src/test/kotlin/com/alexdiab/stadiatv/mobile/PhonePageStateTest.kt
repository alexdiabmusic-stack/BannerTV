package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhonePageStateTest {
    private val nhl = League.find("hockey/nhl")!!
    @Test fun playerScreenStateDoesNotChangeWhenItsNameOrBiographyIsEnriched() {
        val minimal = Athlete("nhl:123", "Unknown player")
        val full = minimal.copy(name = "Example Player", headshotUrl = "https://example.com/player.png", height = "75 in")
        assertEquals(phonePageStateKey(PhonePage.PlayerDetail(nhl, minimal)), phonePageStateKey(PhonePage.PlayerDetail(nhl, full)))
        assertNotEquals(phonePageStateKey(PhonePage.PlayerDetail(nhl, minimal)), phonePageStateKey(PhonePage.PlayerDetail(nhl, minimal.copy(id = "123"))))
    }
    @Test fun gameScoreCorrectionsPreserveUiStateButDifferentGamesDoNotCollide() {
        val game = Match("nhl:2023020204", nhl, 1, "A at B", "A @ B", GameState.LIVE, "", TeamSide("B", "B", "B"), TeamSide("A", "A", "A"))
        assertEquals(phonePageStateKey(PhonePage.Game(game)), phonePageStateKey(PhonePage.Game(game.copy(state = GameState.FINAL, home = game.home.copy(score = "3")))))
        assertNotEquals(phonePageStateKey(PhonePage.Game(game)), phonePageStateKey(PhonePage.Game(game.copy(id = "nhl:2023020205"))))
    }
    @Test fun potentiallySensitiveRouteIdentifiersAreHashedRatherThanSerialized() {
        val page = PhonePage.Podcast(PodcastShow("https://example.com/feed?password=test-only-secret", "Example"))
        val key = phonePageStateKey(page)
        assertTrue(key.matches(Regex("page:[a-f0-9]{64}"))); assertFalse(key.contains("example.com")); assertFalse(key.contains("test-only-secret"))
        assertNotEquals(key, phonePageStateKey(PhonePage.Podcasts))
    }
    @Test fun destinationSectionsHaveSeparateStateWithoutReplacingTheirParents() {
        val key = phonePageStateKey(PhonePage.Stats(nhl.path))
        assertNotEquals(key, phonePageStateKey(PhonePage.Stats("football/nfl")))
        assertNotEquals(phonePageStateKey(PhonePage.LeagueHub(nhl)), phonePageStateKey(PhonePage.LeagueHub(nhl, "Standings")))
    }
    @Test fun historicalNflCalendarSeedsHaveSeparateNavigationState() {
        val nfl = League.find("football/nfl")!!
        val week = com.alexdiab.stadiatv.sports.NflWeek(2025, com.alexdiab.stadiatv.sports.NflSeasonType.REG, 1)
        assertNotEquals(phonePageStateKey(PhonePage.LeagueHub(nfl, "Schedule")), phonePageStateKey(PhonePage.LeagueHub(nfl, "Schedule", week)))
        assertNotEquals(phonePageStateKey(PhonePage.LeagueHub(nfl, "Schedule", week)), phonePageStateKey(PhonePage.LeagueHub(nfl, "Schedule", week.copy(number = 2))))
    }
}
