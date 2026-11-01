package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.util.Dates
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class CatchupResolverTest {
    private val start = requireNotNull(Dates.parseIso("2026-10-21T19:00:00Z"))
    private val show = Programme("test", start, start + 3_600_000, "Test programme")
    private val now = start + 86_400_000
    private fun channel(spec: CatchupSpec? = CatchupSpec("xtream", 3), url: String = "https://provider.example:8080/prefix/live/u%2Fs/p%3F%23%25/123.m3u8") = Channel("123", "Channel", url, null, "Sports", "playlist", "Test", catchup = spec)

    @Test fun xtreamRetainsEncodedCredentialsAndUsesUtcCeilingMinutes() {
        val result = CatchupResolver.resolve(channel(), show.copy(endMillis = show.endMillis + 1), now)
        assertTrue(result.available)
        val url = result.url!!.toHttpUrl()
        assertEquals("/prefix/timeshift/u%2Fs/p%3F%23%25/61/2026-10-21:19-00/123.m3u8", url.encodedPath)
        assertEquals(8080, url.port)
        assertFalse(result.reason.contains("provider.example"))
    }
    @Test fun capabilityAndProgrammeTimeAreRequired() {
        assertFalse(CatchupResolver.resolve(channel(null), show, now).available)
        assertFalse(CatchupResolver.resolve(channel(), show, start + 100).available)
        assertFalse(CatchupResolver.resolve(channel(), show.copy(endMillis = start), now).available)
        assertFalse(CatchupResolver.resolve(channel(CatchupSpec("xtream", 1)), show, now + 1).available)
        assertTrue(CatchupResolver.resolve(channel(CatchupSpec("xtream", 0)), show, now).available)
    }
    @Test fun displayShiftIsRemovedBeforeArchiveRequest() {
        val shifted = show.copy(startMillis = show.startMillis + 10_800_000, endMillis = show.endMillis + 10_800_000)
        assertEquals(CatchupResolver.resolve(channel(), show, now).url, CatchupResolver.resolve(channel(), shifted, now, 180).url)
    }
    @Test fun everyIosTemplateTokenIsTranslatedWithoutGuessingUnknownTokens() {
        val source = "https://archive.example/play?start={start}&end={end}&utc={utc}&local={lutc}&stamp={timestamp}&duration={duration}&minutes={durationMinutes}&offset={offset}"
        val url = CatchupResolver.resolve(channel(CatchupSpec("default", 7, source)), show, now).url!!.toHttpUrl()
        assertEquals((start / 1000).toString(), url.queryParameter("start")); assertEquals("3600", url.queryParameter("duration")); assertEquals("60", url.queryParameter("minutes")); assertEquals("-60", url.queryParameter("offset"))
        assertFalse(CatchupResolver.resolve(channel(CatchupSpec("default", 7, "https://archive.example/{unknown}")), show, now).available)
        assertFalse(CatchupResolver.resolve(channel(CatchupSpec("default", 7, "file:///private/{start}")), show, now).available)
    }
    @Test fun appendPreservesExistingProviderQueryAndRelativeSourcesResolveOnProviderHost() {
        val url = CatchupResolver.resolve(channel(CatchupSpec("append", 3, "?utc={utc}&duration={duration}"), "https://provider.example/live.m3u8?token=private"), show, now).url!!.toHttpUrl()
        assertEquals("private", url.queryParameter("token")); assertEquals("3600", url.queryParameter("duration"))
        assertEquals("https://provider.example:8080/archive/${start / 1000}.m3u8", CatchupResolver.resolve(channel(CatchupSpec("default", 3, "/archive/{start}.m3u8")), show, now).url)
    }
    @Test fun unsupportedModesMalformedPathsAndArchivesNeverInventRequests() {
        for (spec in listOf(CatchupSpec("unknown"), CatchupSpec("shift"))) {
            assertFalse(CatchupResolver.resolve(channel(spec, "https://provider.example/plain.m3u8"), show, now).available)
        }
        assertFalse(CatchupResolver.resolve(channel(url = "https://provider.example/live/u/p/not-a-number.m3u8"), show, now).available)
    }
    @Test fun m3uMetadataIsPerStreamAndExplicitOffOverridesGlobalDefaults() {
        assertEquals("default", CatchupSpec.m3u("true", "3", null)!!.strategy)
        assertEquals("default", CatchupSpec.m3u("1", "3", null)!!.strategy)
        val m3u = """#EXTM3U catchup="default" catchup-days="3" catchup-source="https://provider.example/archive/{start}.m3u8"
            #EXTINF:-1 catchup="none",Live only
            https://provider.example/one.m3u8
            #EXTINF:-1 catchup-days="1",One day
            https://provider.example/two.m3u8
            #EXTINF:-1,Inherited
            https://provider.example/three.m3u8
        """.trimIndent()
        val list = M3uParser.parse(m3u, Playlist("p", "Test", PlaylistKind.M3U)).channels
        assertNull(list[0].catchup); assertEquals(1, list[1].catchup!!.days); assertEquals(3, list[2].catchup!!.days)
        val separate = M3uParser.parse("""#EXTM3U
            #EXTINF:-1 catchup-template="https://provider.example/{start}",Archive
            https://provider.example/a.m3u8
            #EXTINF:-1,Live
            https://provider.example/b.m3u8
        """.trimIndent(), Playlist("p", "Test", PlaylistKind.M3U)).channels
        assertNotNull(separate[0].catchup); assertNull(separate[1].catchup)
    }
    @Test fun guideRetainsOlderListingsOnlyForDeclaredArchivesAndBoundsTheHistory() {
        val builder = com.alexdiab.stadiatv.lineup.LineupBuilder()
        val archived = channel().copy(name = "ESPN", tvgId = "ESPN.us", catchup = CatchupSpec("xtream", 3))
        val live = channel(null).copy(id = "other", name = "ESPN 2", tvgId = "ESPN2.us")
        val xml = """<tv><channel id="ESPN.us"><display-name>ESPN</display-name></channel><channel id="ESPN2.us"><display-name>ESPN 2</display-name></channel>
            <programme start="20261021190000 +0000" stop="20261021200000 +0000" channel="ESPN.us"><title>Archive sports</title></programme>
            <programme start="20261021190000 +0000" stop="20261021200000 +0000" channel="ESPN2.us"><title>Not archived</title></programme>
            <programme start="20261010190000 +0000" stop="20261010200000 +0000" channel="ESPN.us"><title>Outside bounded history</title></programme>
        </tv>""".trimIndent()
        val result = GuideLoader(builder).load(listOf(GuideLoader.Source("fixture") { xml.byteInputStream() }), builder.analyse(listOf(archived, live)), setOf("US"), now)
        assertEquals(listOf("Archive sports"), result.snapshot.programmes("ESPN.us").map { it.title })
        assertTrue(result.snapshot.programmes("ESPN2.us").isEmpty())
    }
}
