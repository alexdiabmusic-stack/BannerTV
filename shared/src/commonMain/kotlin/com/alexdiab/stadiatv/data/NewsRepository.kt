package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.data.news.FeedScope
import com.alexdiab.stadiatv.data.news.NewsRanking
import com.alexdiab.stadiatv.data.news.NewsRelevance
import com.alexdiab.stadiatv.data.news.RssFeed
import com.alexdiab.stadiatv.data.news.RssFeedCatalog
import com.alexdiab.stadiatv.data.news.RssItem
import com.alexdiab.stadiatv.data.news.RssDecoder
import com.alexdiab.stadiatv.domain.Article
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.net.SportsHttp
import com.alexdiab.stadiatv.sports.EspnApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Sports news aggregation. ESPN is the primary source and the publishers' RSS feeds (CBS, BBC, Sky)
 * add their headlines. Every source loads concurrently and one failing never blanks the
 * feed; the merged list is de-duplicated and ranked by freshness and source trust.
 */
class NewsRepository(private val espn: EspnApi, private val http: SportsHttp, private val decoder: RssDecoder) {

    suspend fun latest(leagues: List<League>, perLeague: Int = 8): List<Article> {
        if (leagues.isEmpty()) return emptyList()
        val gate = Semaphore(3)
        val results = coroutineScope {
            leagues.map { league ->
                async { gate.withPermit { runCatching { forLeague(league, perLeague) } } }
            }.awaitAll()
        }
        results.firstNotNullOfOrNull { it.exceptionOrNull() as? CancellationException }?.let { throw it }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull() ?: IllegalStateException("No news")
        val merged = NewsRanking.deduplicate(results.flatMap { it.getOrDefault(emptyList()) })
        return merged.sortedByDescending { it.publishedMillis ?: 0L }
    }

    suspend fun forLeague(league: League, limit: Int = 25): List<Article> = coroutineScope {
        val primary = async { runCatching { espn.news(league, limit) } }
        val secondary = RssFeedCatalog.feeds(league).map { feed -> async { runCatching { load(feed, league) } } }
        val results = listOf(primary.await()) + secondary.awaitAll()
        results.firstNotNullOfOrNull { it.exceptionOrNull() as? CancellationException }?.let { throw it }
        // ESPN is first so its copy of a story wins de-duplication over the other outlets'.
        val articles = results.flatMap { it.getOrDefault(emptyList()) }
        if (articles.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        NewsRanking.rank(NewsRanking.deduplicate(articles), limit)
    }

    private suspend fun load(feed: RssFeed, league: League): List<Article> {
        val xml = http.getString(feed.url, headers = FEED_HEADERS, ttlMillis = FEED_TTL, retries = 1)
        return decoder.decode(xml)
            .filter { item -> !NewsRelevance.isPromotion(item.title) }
            .filter { item -> feed.scope == FeedScope.League || NewsRelevance.mentions(league, item.title, item.description) }
            .map { toArticle(it, feed, league) }
    }

    private fun toArticle(item: RssItem, feed: RssFeed, league: League): Article {
        val url = (item.link ?: item.guid)?.takeIf { it.startsWith("http") }?.secure()
        return Article(
            id = "rss:${feed.source.name.lowercase()}:${(item.guid ?: url ?: item.title).hashCode().toUInt().toString(16)}",
            headline = item.title,
            description = item.description,
            publishedMillis = item.publishedMillis,
            url = url,
            imageUrl = item.imageUrl?.secure()?.let(::sharpen),
            league = league,
            byline = item.author?.takeIf { it.isNotBlank() },
            source = feed.source.label,
            categories = item.categories.take(3),
        )
    }

    /** Fire OS blocks cleartext traffic, and every publisher serves https for the same path. */
    private fun String.secure() = if (startsWith("http://")) "https://" + removePrefix("http://") else this

    /** BBC's feed only carries 240px thumbnails; the same image is served at 976px for a TV screen. */
    private fun sharpen(url: String) = url.replace("/ace/standard/240/", "/ace/standard/976/")

    private companion object {
        const val FEED_TTL = 5 * 60_000L
        val FEED_HEADERS = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml, text/xml")
    }
}
