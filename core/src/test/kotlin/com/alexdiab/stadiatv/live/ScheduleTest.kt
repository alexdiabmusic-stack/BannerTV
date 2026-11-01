package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.GuideListing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleTest {
    private val minute = 60_000L
    private val start = 29_000_000L // minutes since the epoch

    private fun snapshot(): GuideSnapshot {
        val strings = StringTable()
        val builder = ScheduleBuilder()
        // Added out of order, and with the same start twice (the same listing from two sources).
        builder.add((start + 60).toInt(), 30, strings.intern("News"), -1, strings.intern("News"), -1)
        builder.add(start.toInt(), 60, strings.intern("Hockey"), strings.intern("Bruins at Leafs"), strings.intern("Sports"), strings.intern("Live from Toronto."))
        builder.add((start + 60).toInt(), 30, strings.intern("Duplicate"), -1, -1, -1)
        builder.add((start + 90).toInt(), 30, strings.intern("Weather"), -1, -1, -1)
        return GuideSnapshot(mapOf("tsn1.ca" to builder.build()), strings.toArray())
    }

    @Test
    fun aScheduleIsSortedAndDropsARepeatedStart() {
        val list = snapshot().programmes("TSN1.ca")
        assertEquals(listOf("Hockey", "News", "Weather"), list.map { it.title })
        assertEquals("Bruins at Leafs", list[0].subtitle)
        assertEquals("Live from Toronto.", list[0].description)
        assertEquals(start * minute, list[0].startMillis)
        assertEquals((start + 60) * minute, list[0].endMillis)
        assertNull(list[1].description)
    }

    @Test
    fun nowAndNextFindTheRightProgrammes() {
        val snap = snapshot()
        val (now, next) = snap.nowAndNext("tsn1.ca", (start + 70) * minute)
        assertEquals("News", now?.title)
        assertEquals("Weather", next?.title)
        // Before the first programme there is nothing on, but something next.
        val (none, first) = snap.nowAndNext("tsn1.ca", (start - 10) * minute)
        assertNull(none)
        assertEquals("Hockey", first?.title)
        // After the last, nothing next.
        assertNull(snap.nowAndNext("tsn1.ca", (start + 200) * minute).second)
        // A gap between programmes: nothing on.
        assertNull(snap.nowAndNext("unknown", start * minute).first)
    }

    @Test
    fun aShiftedScheduleMovesEveryTimeButKeepsTheOrder() {
        val snap = snapshot()
        val shifted = snap.programmes("tsn1.ca", offsetMinutes = 180)
        assertEquals((start + 180) * minute, shifted[0].startMillis)
        val (now, _) = snap.nowAndNext("tsn1.ca", (start + 190) * minute, offsetMinutes = 180)
        assertEquals("Hockey", now?.title)
    }

    @Test
    fun compactingKeepsOnlyTheChannelsAndTheirText() {
        val strings = StringTable()
        val a = ScheduleBuilder().apply { add(1, 30, strings.intern("A show"), -1, -1, strings.intern("About A")) }.build()
        val b = ScheduleBuilder().apply { add(1, 30, strings.intern("B show"), -1, -1, strings.intern("About B")) }.build()
        val full = GuideSnapshot(mapOf("a" to a, "b" to b), strings.toArray())
        val kept = full.compact(setOf("b"))
        assertEquals(setOf("b"), kept.schedules.keys)
        assertEquals(listOf("B show", "About B"), kept.strings.toList())
        assertEquals("B show", kept.programmes("b").single().title)
        assertEquals("About B", kept.programmes("b").single().description)
    }

    @Test
    fun theCacheRoundTripsEverything() {
        val file = File.createTempFile("guide", ".bin")
        try {
            val content = GuideCacheContent(
                createdAt = 123L, signature = "sig",
                listings = listOf(GuideListing("TSN1.ca", listOf("TSN 1", "CA ★ TSN 1 HD"), 3), GuideListing("Empty.ca", emptyList(), 0)),
                sporty = setOf("tsn1.ca"), snapshot = snapshot(),
            )
            GuideCache.write(file, content)
            val back = requireNotNull(GuideCache.read(file))
            assertEquals(123L, back.createdAt)
            assertEquals("sig", back.signature)
            assertEquals(listOf("TSN 1", "CA ★ TSN 1 HD"), back.listings[0].names)
            assertEquals(0, back.listings[1].programmeCount)
            assertEquals(setOf("tsn1.ca"), back.sporty)
            assertEquals(listOf("Hockey", "News", "Weather"), back.snapshot.programmes("tsn1.ca").map { it.title })
        } finally {
            file.delete()
        }
    }

    @Test
    fun aDamagedCacheIsIgnored() {
        val file = File.createTempFile("guide", ".bin")
        try {
            file.writeText("not a guide")
            assertNull(GuideCache.read(file))
            assertTrue(!file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun xmltvLetsTheSinkDecideAfterTheWholeChannelListIsKnown() {
        val xml = """
            <tv>
              <channel id="A.us"><display-name>A</display-name></channel>
              <channel id="B.us"><display-name>B</display-name></channel>
              <programme start="20261014230000 +0000" stop="20261015000000 +0000" channel="A.us"><title>Kept</title></programme>
              <programme start="20261014230000 +0000" stop="20261015000000 +0000" channel="B.us"><title>Skipped</title></programme>
            </tv>
        """.trimIndent()
        val channelsSeenWhenDone = ArrayList<String>()
        val announced = ArrayList<String>()
        val kept = ArrayList<String>()
        val sink = object : XmltvParser.Sink {
            override fun channel(info: XmltvParser.ChannelInfo): Boolean { announced += info.id; return false }
            override fun channelsDone() { channelsSeenWhenDone += announced }
            override fun acceptsUnannounced(channelId: String) = channelId == "A.us"
            override fun programme(programme: Programme) { kept += programme.title }
        }
        XmltvParser.parse(xml.byteInputStream(), 0L, Long.MAX_VALUE, sink)
        assertEquals(listOf("A.us", "B.us"), channelsSeenWhenDone)
        assertEquals(listOf("Kept"), kept)
    }
}
