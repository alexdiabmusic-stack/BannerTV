package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.GuideListing
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuideCacheRoundtripTest {
    @get:Rule val folder = TemporaryFolder()
    private fun content() = GuideCacheContent(1234, "constructed-source-owner", listOf(GuideListing("one", listOf("Sports one"), 2), GuideListing("two", listOf("Sports two"), 1)), setOf("one", "two"),
        GuideSnapshot(mapOf(
            "one" to Schedule(intArrayOf(100, 200), charArrayOf(60.toChar(), 30.toChar()), intArrayOf(0, 1), intArrayOf(-1, 2), intArrayOf(3, 3), intArrayOf(4, -1)),
            "two" to Schedule(intArrayOf(300), charArrayOf(120.toChar()), intArrayOf(1), intArrayOf(-1), intArrayOf(3), intArrayOf(4)),
        ), arrayOf("NHL Hockey: Rangers at Senators", "Équipe Montréal 🏒", "Subtitle", "Sports", "Actual supplied description")))

    @Test fun twoChannelSchedulesAllTextColumnsAndOwnershipSurviveColdDiskRoundtrip() {
        val file = folder.newFile(); val expected = content()
        GuideCache.write(file, expected); val actual = GuideCache.read(file)!!
        assertEquals(expected.createdAt, actual.createdAt); assertEquals(expected.signature, actual.signature)
        assertEquals(expected.sporty, actual.sporty)
        assertEquals(expected.listings.map { it.id to it.names }, actual.listings.map { it.id to it.names })
        for (id in expected.snapshot.schedules.keys) assertEquals(expected.snapshot.programmes(id), actual.snapshot.programmes(id))
        assertEquals(3, actual.snapshot.programmeCount)
        assertEquals(expected.snapshot.nowAndNext("one", 150 * 60_000L), actual.snapshot.nowAndNext("one", 150 * 60_000L))
    }

    @Test fun newerCompleteWriteReplacesOldSnapshotRatherThanMergingRemovedChannels() {
        val file = folder.newFile(); val old = content(); GuideCache.write(file, old)
        val newer = GuideCacheContent(5678, "new-owner", emptyList(), emptySet(), GuideSnapshot.EMPTY)
        GuideCache.write(file, newer)
        val actual = GuideCache.read(file)!!
        assertEquals(5678L, actual.createdAt); assertEquals("new-owner", actual.signature)
        assertEquals(0, actual.snapshot.programmeCount); assertTrue(actual.snapshot.schedules.isEmpty())
    }

    @Test fun truncatedCacheIsRejectedInsteadOfPublishingPartialListings() {
        val file = folder.newFile(); GuideCache.write(file, content())
        file.writeBytes(file.readBytes().dropLast(5).toByteArray())
        assertNull(GuideCache.read(file))
    }
}
