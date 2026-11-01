package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.foundation.nowMillis

/** De-duplication and ranking for headlines gathered from several publishers. */
object NewsRanking {
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
        "cmpid", "campaign", "source", "ref", "referrer", "fbclid", "gclid",
    )
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9 ]")
    private val WHITESPACE = Regex("\\s+")
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000.0
    private const val MAX_SUFFIX = 24

    /** Drops articles whose URL or headline was already seen, keeping the first (highest-priority) copy. */
    fun deduplicate(articles: List<Article>): List<Article> {
        val seenUrls = HashSet<String>()
        val seenTitles = HashSet<String>()
        return articles.filter { article ->
            val urlKey = canonicalUrl(article.url)
            val titleKey = normalizeTitle(article.headline)
            if (urlKey.isNotEmpty() && urlKey in seenUrls) return@filter false
            if (titleKey in seenTitles) return@filter false
            if (urlKey.isNotEmpty()) seenUrls += urlKey
            seenTitles += titleKey
            true
        }
    }

    /**
     * Freshest and most trusted first, with a per-publisher cap so one outlet's burst of headlines
     * can't push everyone else off the screen.
     */
    fun rank(articles: List<Article>, limit: Int, now: Long = nowMillis()): List<Article> {
        val sources = articles.map { it.source }.distinct().size.coerceAtLeast(1)
        val perSource = maxOf(3, limit / sources)
        val counts = HashMap<String, Int>()
        return articles
            .sortedByDescending { score(it, now) }
            .filter { article ->
                val taken = counts[article.source] ?: 0
                if (taken >= perSource) false else { counts[article.source] = taken + 1; true }
            }
            .take(limit)
    }

    private fun score(article: Article, now: Long): Double {
        val ageDays = article.publishedMillis?.let { (now - it).coerceAtLeast(0) / DAY_MILLIS }
        val freshness = if (ageDays == null) 0.0 else (1.0 - ageDays).coerceAtLeast(0.0) * 50
        return freshness + NewsSource.priorityOf(article.source) * 20
    }

    internal fun canonicalUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        val withoutFragment = url.substringBefore('#')
        val base = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=').lowercase() !in TRACKING_PARAMS }
        return (if (query.isEmpty()) base else base + "?" + query.joinToString("&")).lowercase()
    }

    internal fun normalizeTitle(title: String): String {
        var text = title.lowercase()
        // Strip short publisher suffixes such as " - ESPN" or " | FOX Sports"; a long tail is part of the headline.
        for (separator in listOf(" - ", " | ", " – ")) {
            val index = text.lastIndexOf(separator)
            if (index > 0 && text.length - index <= MAX_SUFFIX) text = text.substring(0, index)
        }
        return text.replace(NON_ALPHANUMERIC, "").replace(WHITESPACE, " ").trim()
    }
}
