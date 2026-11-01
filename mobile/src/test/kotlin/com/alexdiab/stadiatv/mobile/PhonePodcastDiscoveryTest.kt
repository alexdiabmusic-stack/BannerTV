package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.domain.League
import org.junit.Assert.*
import org.junit.Test

class PhonePodcastDiscoveryTest {
    @Test fun malformedBlankTeamNameDoesNotTurnEveryCatalogFeedIntoATeamRecommendation() {
        val team = FavoriteTeam("hockey/nhl", "nhl:9", " ")
        assertTrue(phonePodcastCatalog(PhonePodcastTopic("team", "Unknown", team.league, team),
            listOf(PodcastShow("https://a.example/feed", "Generic hockey")), emptyList()).isEmpty())
        assertEquals("All sports", phonePodcastTopics(emptyList(), emptyList()).first().label)
    }
    @Test fun favoriteTopicsAreScopedAndPlacedNextToTheirLeagues() {
        val ottawa = FavoriteTeam("hockey/nhl", "nhl:9", "Ottawa Senators")
        val sameNumber = FavoriteTeam("baseball/mlb", "mlb:9", "Different team")
        val topics = phonePodcastTopics(listOf(ottawa, sameNumber, ottawa), listOf(League.find("soccer/esp.1")!!))
        assertEquals("all", topics.first().id); assertEquals(topics.size, topics.distinctBy { it.id }.size)
        assertEquals(2, topics.count { it.team != null })
        assertEquals("league:hockey/nhl", topics[topics.indexOfFirst { it.team == ottawa } - 1].id)
        assertTrue(topics.any { it.id == "league:soccer/esp.1" })
    }
    @Test fun leagueTopicsUseSportCatalogRatherThanUnrelatedFollowedSports() {
        val nhl = League.find("hockey/nhl")!!; val nba = League.find("basketball/nba")!!
        val shows = listOf(PodcastShow("https://a.example/feed", "Hockey show", sport = "Hockey"),
            PodcastShow("https://b.example/feed", "Basketball show", sport = "Basketball"))
        assertEquals(listOf("Hockey show"), phonePodcastCatalog(PhonePodcastTopic("nhl", "NHL", nhl), shows, listOf(nba)).map { it.title })
        assertEquals(2, phonePodcastCatalog(PhonePodcastTopic("all", "For you"), shows, emptyList()).size)
    }
    @Test fun localTeamFallbackRequiresFullNameOrExactCuratedTagNotGenericLeagueCoverage() {
        val team = FavoriteTeam("hockey/nhl", "nhl:9", "Ottawa Senators")
        val topic = PhonePodcastTopic("team", team.name, team.league, team)
        val shows = listOf(PodcastShow("https://a.example/feed", "Ottawa Senators Weekly"),
            PodcastShow("https://b.example/feed", "Curated team show", tags = listOf("ottawa senators")),
            PodcastShow("https://c.example/feed", "NHL Weekly", tags = listOf("nhl")),
            PodcastShow("https://d.example/feed", "Ottawa Senatorship Weekly"))
        assertEquals(listOf("Ottawa Senators Weekly", "Curated team show"), phonePodcastCatalog(topic, shows, emptyList()).map { it.title })
    }
}
