package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.SavedProvider
import com.alexdiab.stadiatv.util.Dates
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional regression against the user's saved provider sample. The data stays outside the repository; set
 * STADIA_PROVIDER_SAMPLE_DIR to a directory containing get_live_streams.json, get_live_categories.json and xmltv.xml.
 */
class ProviderEpgMatchRealDataTest {
    @Test
    fun ottawaListingSelectsOnlyTheTsnFeedNamedByProviderXmltv() {
        val dir = System.getenv("STADIA_PROVIDER_SAMPLE_DIR")?.let(::File)
        val xml = dir?.let { File(it, "xmltv.xml") }
        assumeTrue("no provider sample", dir != null && xml?.exists() == true)
        val dataDir = requireNotNull(dir)
        val guideFile = requireNotNull(xml)

        val channels = SavedProvider.channels(dataDir).filter { it.tvgId?.startsWith("TSN", ignoreCase = true) == true }
        val wanted = channels.mapNotNull { it.tvgId?.lowercase() }.toSet()
        val programmes = ArrayList<Programme>()
        XmltvParser.parse(
            guideFile.inputStream(),
            utc("2026-10-13T20:00Z"),
            utc("2026-10-14T05:00Z"),
            object : XmltvParser.Sink {
                override fun channel(info: XmltvParser.ChannelInfo) = info.id.lowercase() in wanted
                override fun acceptsUnannounced(channelId: String) = channelId.lowercase() in wanted
                override fun programme(programme: Programme) { programmes += programme }
            },
        )

        val matchingGuideIds = programmes
            .filter { it.title.contains("Ottawa Senators", ignoreCase = true) && it.title.contains("St. Louis Blues", ignoreCase = true) }
            .mapTo(HashSet()) { it.guideId.lowercase() }
        assertTrue("sample must contain the Ottawa fixture on TSN5", "tsn5.ca" in matchingGuideIds)

        val event = LinkerEvent(
            id = "401892494",
            league = "hockey/nhl",
            kickoffMillis = utc("2026-10-13T23:30Z"),
            home = LinkerTeam("Ottawa Senators", "Senators"),
            away = LinkerTeam("St. Louis Blues", "Blues"),
            broadcasts = listOf("TSN"),
        )
        val linker = StreamLinker(
            channels.map { LinkerStream(it.key, it.name, it.group, it.tvgId) },
            programmes.map { LinkerProgramme(it.guideId, it.startMillis, it.endMillis, it.title, it.description) },
        )
        val feeds = linker.link(event)
        val tsn5 = feeds.firstOrNull { it.key == "g:tsn5.ca" }
        assertNotNull("TSN5 must be a direct guide match", tsn5)
        assertEquals(LinkTier.LiveListing, tsn5!!.tier)
        assertTrue(tsn5.confidence >= 0.9)
        assertTrue("both provider TSN5 qualities should be playable", tsn5.streamIds.size >= 2)
        assertTrue(
            "no unrelated TSN schedule may be reported as a live listing: ${feeds.map { it.key }}",
            feeds.filter { it.tier == LinkTier.LiveListing }.all { it.key.removePrefix("g:") in matchingGuideIds },
        )
    }

    private fun utc(value: String): Long = requireNotNull(Dates.parseIso(value))
}
