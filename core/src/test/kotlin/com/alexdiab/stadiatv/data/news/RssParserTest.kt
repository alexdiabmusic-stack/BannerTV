package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RssParserTest {

    private fun rss(vararg items: String) = """
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:dc="http://purl.org/dc/elements/1.1/">
  <channel>
    <title>Publisher</title>
    <image><url>http://example.com/logo.gif</url><title>Logo title</title><link>http://example.com</link></image>
    ${items.joinToString("\n")}
  </channel>
</rss>
""".trim()

    @Test
    fun `reads a BBC style item with CDATA and a media thumbnail`() {
        val items = RssParser.parse(rss("""
            <item>
              <title><![CDATA[Red Bull F1 boss Mekies gets new deal to 2031]]></title>
              <description><![CDATA[Contract extended until the end of 2031.]]></description>
              <link>https://www.bbc.co.uk/sport/formula1/articles/c84g1rxr0dpno?at_medium=RSS&amp;at_campaign=rss</link>
              <guid isPermaLink="false">https://www.bbc.co.uk/sport/formula1/articles/c84g1rxr0dpno#0</guid>
              <pubDate>Wed, 14 Oct 2026 17:02:47 GMT</pubDate>
              <media:thumbnail width="240" height="134" url="https://ichef.bbci.co.uk/ace/standard/240/a.jpg"/>
            </item>
        """.trimIndent()))

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("Red Bull F1 boss Mekies gets new deal to 2031", item.title)
        assertEquals("Contract extended until the end of 2031.", item.description)
        assertEquals("https://www.bbc.co.uk/sport/formula1/articles/c84g1rxr0dpno?at_medium=RSS&at_campaign=rss", item.link)
        assertEquals("https://ichef.bbci.co.uk/ace/standard/240/a.jpg", item.imageUrl)
        assertEquals(Dates.parseIso("2026-10-14T17:02:47Z"), item.publishedMillis)
    }

    @Test
    fun `channel level image never leaks into items`() {
        val items = RssParser.parse(rss("<item><title>Only headline</title><link>https://e.com/a</link></item>"))
        assertEquals("Only headline", items.single().title)
        assertNull(items.single().imageUrl)
    }

    @Test
    fun `decodes numeric entities and trims padded CBS text`() {
        val items = RssParser.parse(rss("""
            <item>
              <title>
                Today&amp;#039;s top games to watch
              </title>
              <link> https://www.cbssports.com/nhl/news/a/ </link>
              <pubDate> Wed, 14 Oct 2026 19:30:51 +0000 </pubDate>
              <dc:creator> Scott Erskine </dc:creator>
              <enclosure url="https://sportshub.cbsistatic.com/i/a.png" length="2599502" type="image/png" />
            </item>
        """.trimIndent()))

        val item = items.single()
        assertEquals("Today's top games to watch", item.title)
        assertEquals("https://www.cbssports.com/nhl/news/a/", item.link)
        assertEquals("Scott Erskine", item.author)
        assertEquals("https://sportshub.cbsistatic.com/i/a.png", item.imageUrl)
        assertEquals(Dates.parseIso("2026-10-14T19:30:51Z"), item.publishedMillis)
    }

    @Test
    fun `British Summer Time is read as UTC plus one`() {
        assertEquals(Dates.parseIso("2026-10-13T12:00:00Z"), Dates.parseFeedDate("Tue, 13 Oct 2026 13:00:00 BST"))
    }

    @Test
    fun `non image enclosures and video media are not used as artwork`() {
        val items = RssParser.parse(rss("""
            <item>
              <title>Clip</title>
              <enclosure url="https://e.com/clip.mp3" type="audio/mpeg"/>
              <media:content url="https://e.com/clip.mp4" medium="video" type="video/mp4"/>
            </item>
        """.trimIndent()))
        assertNull(items.single().imageUrl)
    }

    @Test
    fun `falls back to the first image in the description html`() {
        val items = RssParser.parse(rss("""
            <item>
              <title>Story</title>
              <description><![CDATA[<p><img src="https://e.com/lead.jpg"/>Opening paragraph &amp; more.</p>]]></description>
            </item>
        """.trimIndent()))
        assertEquals("https://e.com/lead.jpg", items.single().imageUrl)
        assertEquals("Opening paragraph & more.", items.single().description)
    }

    @Test
    fun `reads an Atom entry`() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom feed</title>
              <entry>
                <title>Atom headline</title>
                <link rel="self" href="https://e.com/self"/>
                <link rel="alternate" href="https://e.com/article"/>
                <id>urn:uuid:1</id>
                <updated>2026-10-14T10:00:00Z</updated>
                <summary>Short summary</summary>
                <author><name>Jo Writer</name></author>
              </entry>
            </feed>
        """.trimIndent()
        val item = RssParser.parse(xml).single()
        assertEquals("Atom headline", item.title)
        assertEquals("https://e.com/article", item.link)
        assertEquals("urn:uuid:1", item.guid)
        assertEquals("Jo Writer", item.author)
        assertEquals(Dates.parseIso("2026-10-14T10:00:00Z"), item.publishedMillis)
    }

    @Test
    fun `html entities in plain text do not abort the feed`() {
        val items = RssParser.parse(rss(
            "<item><title>First&nbsp;story</title><link>https://e.com/1</link></item>",
            "<item><title>Second story</title><link>https://e.com/2</link></item>",
        ))
        assertEquals(2, items.size)
    }

    @Test
    fun `a truncated feed still yields the items before the damage`() {
        val xml = rss(
            "<item><title>Complete</title><link>https://e.com/1</link></item>",
            "<item><title>Cut off mid",
        )
        val items = RssParser.parse(xml)
        assertEquals(listOf("Complete"), items.map { it.title })
    }

    @Test
    fun `items without a headline are dropped and the cap is honoured`() {
        val many = (1..10).map { "<item><title>Story $it</title></item>" }.toTypedArray()
        val xml = rss("<item><description>No title</description></item>", *many)
        val items = RssParser.parse(xml, maxItems = 4)
        assertEquals(4, items.size)
        assertTrue(items.all { it.title.startsWith("Story") })
    }
}
