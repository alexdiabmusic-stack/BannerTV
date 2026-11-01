package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.domain.League
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsRankingTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val nhl = League.find("hockey/nhl")!!

    private fun article(
        headline: String,
        source: String = "ESPN",
        url: String? = "https://e.com/${headline.hashCode()}",
        ageHours: Long = 1,
    ) = Article(
        id = "$source-$headline",
        headline = headline,
        description = "",
        publishedMillis = now - ageHours * hour,
        url = url,
        imageUrl = null,
        league = nhl,
        source = source,
    )

    @Test
    fun `tracking parameters and fragments do not make a different url`() {
        val a = article("One", url = "https://e.com/story?id=7&utm_source=rss#top")
        val b = article("Another headline", url = "https://E.com/story?id=7&fbclid=abc")
        assertEquals(listOf("One"), NewsRanking.deduplicate(listOf(a, b)).map { it.headline })
    }

    @Test
    fun `same headline from two publishers keeps the first copy`() {
        val espn = article("Oilers beat Flames 4-2", source = "ESPN")
        val cbs = article("Oilers beat Flames 4-2 | CBS Sports", source = "CBS Sports")
        assertEquals(listOf("ESPN"), NewsRanking.deduplicate(listOf(espn, cbs)).map { it.source })
    }

    @Test
    fun `a long dash tail is part of the headline and is not stripped`() {
        val a = article("Bruins - what the new line combinations mean for the power play this season")
        val b = article("Bruins - a goalie controversy nobody expected in the opening month of the season")
        assertEquals(2, NewsRanking.deduplicate(listOf(a, b)).size)
    }

    @Test
    fun `fresh stories outrank old ones from the same source`() {
        val old = article("Old story", ageHours = 30)
        val fresh = article("Fresh story", ageHours = 2)
        assertEquals(listOf("Fresh story", "Old story"), NewsRanking.rank(listOf(old, fresh), limit = 10, now = now).map { it.headline })
    }

    @Test
    fun `one publisher cannot fill the whole list`() {
        val flood = (1..12).map { article("CBS story $it", source = "CBS Sports", ageHours = 1) }
        val others = listOf(article("ESPN story", source = "ESPN", ageHours = 5), article("BBC story", source = "BBC Sport", ageHours = 6))
        val ranked = NewsRanking.rank(flood + others, limit = 9, now = now)
        assertEquals(3, ranked.count { it.source == "CBS Sports" })
        assertTrue(ranked.any { it.source == "ESPN" })
        assertTrue(ranked.any { it.source == "BBC Sport" })
    }

    @Test
    fun `articles without a date are ranked but never ahead of fresh ones`() {
        val undated = article("Undated").copy(publishedMillis = null)
        val fresh = article("Fresh", ageHours = 1)
        assertEquals("Fresh", NewsRanking.rank(listOf(undated, fresh), limit = 5, now = now).first().headline)
    }

    @Test
    fun `promotions are recognised and real headlines are not`() {
        assertTrue(NewsRelevance.isPromotion("Use BetMGM bonus code CBSSPORTS to get \$1,500 bonus bets"))
        assertFalse(NewsRelevance.isPromotion("Bruins vs. Sharks NHL picks, parlay: Solid late matchup"))
    }

    @Test
    fun `dedicated feeds belong to the league and broad ones must name it`() {
        assertEquals(listOf(FeedScope.League), RssFeedCatalog.feeds(nhl).map { it.scope })
        val mls = League.find("soccer/usa.1")!!
        assertTrue(RssFeedCatalog.feeds(mls).all { it.scope == FeedScope.Mentions })
        val epl = League.find("soccer/eng.1")!!
        assertTrue(RssFeedCatalog.feeds(epl).any { it.scope == FeedScope.League && it.source == NewsSource.Bbc })

        assertTrue(NewsRelevance.mentions(mls, "MLS Cup playoffs begin", ""))
        assertFalse(NewsRelevance.mentions(mls, "Premier League title race", "Arsenal v Spurs"))
        assertFalse(NewsRelevance.mentions(nhl, "NHLPA statement", ""))
    }

    @Test
    fun `every league has at least one feed`() {
        League.ALL.forEach { league -> assertTrue(league.name, RssFeedCatalog.feeds(league).isNotEmpty()) }
    }
}
