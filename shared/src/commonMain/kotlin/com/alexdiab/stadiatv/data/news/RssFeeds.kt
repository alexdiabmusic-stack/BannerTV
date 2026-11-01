package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.SportGroup

enum class NewsSource(val label: String, val priority: Double) {
    Espn("ESPN", 1.0),
    Cbs("CBS Sports", 0.8),
    Bbc("BBC Sport", 0.7),
    Sky("Sky Sports", 0.65);

    companion object {
        fun priorityOf(label: String): Double = entries.firstOrNull { it.label == label }?.priority ?: 0.5
    }
}

/** How a feed's items relate to the league being loaded. */
enum class FeedScope {
    /** The feed is dedicated to this league, so every item belongs to it. */
    League,

    /** A broader feed (all football, all sport): keep only the items that name the league. */
    Mentions,
}

data class RssFeed(val source: NewsSource, val url: String, val scope: FeedScope)

/**
 * The public RSS feeds each publisher offers for a league, checked against the live endpoints.
 * ESPN stays the primary source; these add CBS, BBC and Sky headlines. (The iOS app also lists NBC
 * Sports and FOX Sports feeds, but both now answer with a 404 page and an empty channel.)
 */
object RssFeedCatalog {
    private const val BBC = "https://feeds.bbci.co.uk/sport"
    private const val SKY = "https://www.skysports.com/rss"
    private const val CBS = "https://www.cbssports.com/rss/headlines"

    private const val SKY_FOOTBALL = 11095
    private const val SKY_FORMULA_1 = 12433
    private const val SKY_PGA_TOUR = 12180

    fun feeds(league: League): List<RssFeed> = when (league.path) {
        "football/nfl" -> listOf(cbs("nfl"))
        "basketball/nba" -> listOf(cbs("nba"))
        "baseball/mlb" -> listOf(cbs("mlb"))
        "hockey/nhl" -> listOf(cbs("nhl"))
        "racing/f1" -> listOf(
            RssFeed(NewsSource.Bbc, "$BBC/formula1/rss.xml", FeedScope.League),
            RssFeed(NewsSource.Sky, "$SKY/$SKY_FORMULA_1", FeedScope.League),
        )
        "golf/pga" -> listOf(
            RssFeed(NewsSource.Bbc, "$BBC/golf/rss.xml", FeedScope.League),
            RssFeed(NewsSource.Sky, "$SKY/$SKY_PGA_TOUR", FeedScope.League),
            cbs("golf"),
        )
        else -> when (league.group) {
            SportGroup.Soccer -> soccer(league)
            // WNBA has no feed of its own; CBS's general feed names it when it matters.
            else -> listOf(RssFeed(NewsSource.Cbs, "$CBS/", FeedScope.Mentions))
        }
    }

    private fun cbs(slug: String) = RssFeed(NewsSource.Cbs, "$CBS/$slug", FeedScope.League)

    private fun soccer(league: League): List<RssFeed> = buildList {
        if (league.path == "soccer/eng.1") {
            add(RssFeed(NewsSource.Bbc, "$BBC/football/premier-league/rss.xml", FeedScope.League))
        }
        add(RssFeed(NewsSource.Bbc, "$BBC/football/rss.xml", FeedScope.Mentions))
        add(RssFeed(NewsSource.Sky, "$SKY/$SKY_FOOTBALL", FeedScope.Mentions))
        add(RssFeed(NewsSource.Cbs, "$CBS/soccer", FeedScope.Mentions))
    }
}

/** Decides whether an item from a feed is about a league, and whether it is worth showing at all. */
object NewsRelevance {
    private val aliases: Map<String, List<String>> = mapOf(
        "football/nfl" to listOf("nfl"),
        "basketball/nba" to listOf("nba"),
        "basketball/wnba" to listOf("wnba"),
        "baseball/mlb" to listOf("mlb"),
        "hockey/nhl" to listOf("nhl"),
        "soccer/eng.1" to listOf("premier league", "epl"),
        "soccer/usa.1" to listOf("mls", "major league soccer"),
        "soccer/esp.1" to listOf("la liga", "laliga"),
        "soccer/uefa.champions" to listOf("champions league", "ucl"),
        "racing/f1" to listOf("formula 1", "formula one", "f1", "grand prix"),
        "golf/pga" to listOf("pga"),
    )

    private val patterns: Map<String, Regex> = aliases.mapValues { (_, words) ->
        Regex(words.joinToString("|", prefix = "\\b(?:", postfix = ")\\b") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    }

    private val PROMOTION = Regex("""bonus code|promo code|bonus bets|sportsbook promo|betting promo""", RegexOption.IGNORE_CASE)

    fun mentions(league: League, vararg text: String): Boolean {
        val pattern = patterns[league.path] ?: return false
        return text.any { pattern.containsMatchIn(it) }
    }

    /** Sportsbook advertising dressed up as an article; it has no place in a news shelf. */
    fun isPromotion(headline: String): Boolean = PROMOTION.containsMatchIn(headline)
}
