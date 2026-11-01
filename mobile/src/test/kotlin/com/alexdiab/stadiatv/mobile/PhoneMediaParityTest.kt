package com.alexdiab.stadiatv.mobile

import org.junit.Assert.*
import org.junit.Test

class PhoneMediaParityTest {
    @Test fun `reader extracts article paragraphs and removes navigation scripts and hidden content`() {
        val article = PhoneArticleStore.extract("a", "Title", "https://example.com/a", """<html><nav><p>This navigation text must not be part of a story.</p></nav><article><p>A genuine paragraph with enough content for the reader.</p><script>bad()</script><p hidden>Hidden private or unavailable paragraph goes here.</p><p>Second readable paragraph from the publisher.</p></article></html>""", 1)
        assertEquals(2, article.paragraphs.size)
        assertFalse(article.paragraphs.any { it.contains("Hidden") || it.contains("navigation") || it.contains("bad()") })
    }
    @Test fun `reader does not extract publisher content flagged inaccessible for free`() {
        val article = PhoneArticleStore.extract("a", "Title", "https://example.com/a", """<script type="application/ld+json">{"isAccessibleForFree":false}</script><article><p>Publisher subscription body must not be extracted.</p></article>""", 1)
        assertTrue(article.restricted); assertTrue(article.paragraphs.isEmpty())
    }
    @Test fun `reader empty markup remains empty rather than fabricating article content`() {
        assertTrue(PhoneArticleStore.extract("a", "Headline", "https://example.com", "<html><nav>Only navigation</nav></html>", 1).paragraphs.isEmpty())
    }
    @Test fun `podcast feed retains stable per feed identity audio video dates and duration`() {
        val xml = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><item><guid>one</guid><title>Episode one</title><description><![CDATA[<p>Real episode description</p>]]></description><pubDate>Thu, 22 Oct 2026 10:00:00 +0000</pubDate><itunes:duration>1:02:03</itunes:duration><enclosure url="https://example.com/e.mp3" type="audio/mpeg"/></item><item><guid>video</guid><title>Video episode</title><enclosure url="https://example.com/v.mp4" type="video/mp4"/></item><item><title>Unsafe enclosure</title><enclosure url="file:///private/example" type="audio/mpeg"/></item></channel></rss>"""
        val episodes = PhonePodcastsStore.parseEpisodes(xml, PodcastShow("https://example.com/feed", "Show"))
        assertEquals(2, episodes.size); assertEquals(3723, episodes.first().duration)
        assertEquals("Real episode description", episodes.first().description)
        assertTrue(episodes.first().id.startsWith("https://example.com/feed|")); assertNotNull(episodes.first().published)
        assertTrue(episodes.last().mime.startsWith("video"))
    }
    @Test fun `podcast durations and URLs reject invalid sources`() {
        assertEquals(0, PhonePodcastsStore.duration("-1:20")); assertEquals(0, PhonePodcastsStore.duration("bad")); assertEquals(90, PhonePodcastsStore.duration("1:30"))
        assertFalse(PhonePodcastsStore.safeWebUrl("javascript:evil()")); assertFalse(PhonePodcastsStore.safeWebUrl("content://private")); assertTrue(PhonePodcastsStore.safeWebUrl("https://example.com/feed.xml"))
    }
}
