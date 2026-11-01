package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistParsingTest {
    private val playlist = Playlist("p1", "Test", PlaylistKind.M3U)

    @Test
    fun parsesM3uMetadataAndUrls() {
        val body = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Fallback\" tvg-logo=\"https://img/logo.png\" group-title=\"Sports\",TSN 1\nhttps://example.com/live/1.m3u8\n"

        val channels = M3uParser.parse(body, playlist).channels

        assertEquals(1, channels.size)
        assertEquals("TSN 1", channels[0].name)
        assertEquals("Sports", channels[0].group)
        assertEquals("https://img/logo.png", channels[0].logoUrl)
    }

    @Test
    fun readsPlaylistGuideUrlAndChannelIdentity() {
        val body = "#EXTM3U x-tvg-url=\"https://example.com/guide.xml.gz\"\n" +
            "#EXTINF:-1 tvg-id=\"ESPN.us\" tvg-language=\"en\",US ★ ESPN FHD\nhttps://example.com/espn.m3u8\n"

        val result = M3uParser.parse(body, playlist)

        assertEquals("ESPN.us", result.channels[0].tvgId)
        assertEquals("en", result.channels[0].language)
        assertEquals("ESPN", ChannelNames.normalize(result.channels[0].name))
        assertTrue(result.epgUrls.contains("https://example.com/guide.xml.gz"))
    }

    @Test
    fun collectsPerStreamHeadersFromEveryDialect() {
        val body = """
            #EXTM3U
            #EXTINF:-1,Vlc Style
            #EXTVLCOPT:http-user-agent=VLC/3.0
            #EXTVLCOPT:http-referrer=https://ref.example/
            #EXTVLCOPT:http-reconnect=true
            https://a.example/1.m3u8
            #EXTINF:-1,Kodi Style
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Kodi&Origin=https%3A%2F%2Fo.example
            https://a.example/2.m3u8
            #EXTINF:-1,Pipe Style
            https://a.example/3.m3u8|User-Agent=Pipe&Referer=https://p.example/
            #EXTINF:-1,Json Style
            #EXTHTTP:{"User-Agent":"Json","Cookie":"a=b"}
            https://a.example/4.m3u8
            #EXTINF:-1,Clean
            https://a.example/5.m3u8
        """.trimIndent()

        val channels = M3uParser.parse(body, playlist).channels

        assertEquals("VLC/3.0", channels[0].headers["User-Agent"])
        assertEquals("https://ref.example/", channels[0].headers["Referer"])
        assertNull("transport options are not headers", channels[0].headers["http-reconnect"])
        assertEquals("Kodi", channels[1].headers["User-Agent"])
        assertEquals("https://o.example", channels[1].headers["Origin"])
        assertEquals("Pipe", channels[2].headers["User-Agent"])
        assertEquals("https://a.example/3.m3u8", channels[2].streamUrl)
        assertEquals("Json", channels[3].headers["User-Agent"])
        assertEquals("a=b", channels[3].headers["Cookie"])
        assertTrue("headers apply to one stream only", channels[4].headers.isEmpty())
    }

    @Test
    fun channelIdsAreStableAndUnique() {
        val body = "#EXTM3U\n#EXTINF:-1,A\nhttps://x/1.m3u8\n#EXTINF:-1,A again\nhttps://x/1.m3u8\n#EXTINF:-1,B\nhttps://x/2.m3u8\n"

        val first = M3uParser.parse(body, playlist).channels
        val second = M3uParser.parse(body, playlist).channels

        assertEquals(3, first.map { it.id }.toSet().size)
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun ignoresNonHttpEntriesAndRespectsTheChannelCap() {
        val body = "#EXTM3U\n#EXTINF:-1,Local\nfile:///sdcard/x.mp4\n#EXTINF:-1,Good\nhttp://x/1.m3u8\n"
        assertEquals(listOf("Good"), M3uParser.parse(body, playlist).channels.map { it.name })
    }

    @Test
    fun badgeCorruptionIsRepaired() {
        assertEquals("Muskingum at Ohio State", StreamAvailabilityStore.repairBadgeCorruption("MUS ★kingum at Ohio State"))
        assertEquals("US ★ ESPN", StreamAvailabilityStore.repairBadgeCorruption("US ★ ESPN"))
    }

    @Test
    fun parsesXmltvTimesWithAndWithoutOffsets() {
        assertEquals(Dates.parseIso("2026-10-14T23:30Z"), XmltvParser.parseTime("20261014233000 +0000"))
        assertEquals(Dates.parseIso("2026-10-14T23:30Z"), XmltvParser.parseTime("202610141930 -0400"))
        assertEquals(Dates.parseIso("2026-10-14T23:30Z"), XmltvParser.parseTime("20261014233000"))
        assertNull(XmltvParser.parseTime("nope"))
    }

    @Test
    fun xmltvKeepsOnlyAcceptedChannelsInsideTheWindow() {
        val xml = """
            <tv>
              <channel id="ESPN.us"><display-name>ESPN</display-name></channel>
              <channel id="Other.us"><display-name>Other</display-name></channel>
              <programme start="20261014230000 +0000" stop="20261015020000 +0000" channel="ESPN.us"><title>NHL : A at B</title><desc>Live game.</desc><category>Sports</category></programme>
              <programme start="20261014230000 +0000" stop="20261015020000 +0000" channel="Other.us"><title>Ignored</title></programme>
              <programme start="20200101000000 +0000" stop="20200101010000 +0000" channel="ESPN.us"><title>Ancient</title></programme>
            </tv>
        """.trimIndent()
        val kept = ArrayList<Programme>()
        val sink = object : XmltvParser.Sink {
            override fun channel(info: XmltvParser.ChannelInfo) = info.id == "ESPN.us"
            override fun acceptsUnannounced(channelId: String) = false
            override fun programme(programme: Programme) { kept += programme }
        }
        val windowStart = requireNotNull(Dates.parseIso("2026-10-14T18:00Z"))
        val windowEnd = requireNotNull(Dates.parseIso("2026-10-16T00:00Z"))

        XmltvParser.parse(xml.byteInputStream(), windowStart, windowEnd, sink)

        assertEquals(1, kept.size)
        assertEquals("NHL : A at B", kept[0].title)
        assertEquals("Sports", kept[0].category)
    }
}
