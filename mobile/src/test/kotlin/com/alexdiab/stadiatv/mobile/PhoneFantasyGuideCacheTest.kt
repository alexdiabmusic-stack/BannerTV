package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.live.Programme
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PhoneFantasyGuideCacheTest {
    private val index = NativeFantasyEventIndex()
    private val listing = Programme("own.guide", 1000, 2000, "Constructed listing")
    private fun evidence(channel: String = "playlist|1") = PhoneFantasyGuideEvidence(channel, "own.guide", listing, index)
    @Test fun computationRunsOffCallerThreadAndNeverMoreThanTwoAtOnce() = runTest {
        val caller = Thread.currentThread(); val active = AtomicInteger(); val peak = AtomicInteger()
        val cache = PhoneFantasyGuideCache { _, _, _ ->
            assertNotEquals(caller, Thread.currentThread())
            val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            try { Thread.sleep(20); emptyList() } finally { active.decrementAndGet() }
        }
        (1..8).map { async { cache.players(evidence("playlist|$it")) } }.awaitAll()
        assertTrue(peak.get() in 1..2)
    }
    @Test fun exactContentReusesCacheButCorrectionsAndPlaylistBindingsDoNotBorrowCounts() = runTest {
        var calls = 0
        val cache = PhoneFantasyGuideCache { _, _, _ -> calls++; emptyList() }
        val first = evidence(); cache.players(first); cache.players(first); assertEquals(1, calls)
        cache.players(first.copy(programme = listing.copy(title = "Corrected"))); assertEquals(2, calls)
        cache.players(first.copy(guideId = "rebound.guide")); assertEquals(3, calls)
        cache.players(first.copy(channelKey = "another-playlist|1")); assertEquals(4, calls)
    }
    @Test fun visibleListingCacheHasABounded128EntryLifetime() = runTest {
        var calls = 0; val cache = PhoneFantasyGuideCache { _, _, _ -> calls++; emptyList() }
        repeat(129) { cache.players(evidence("playlist|$it")) }
        assertEquals(129, calls); cache.players(evidence("playlist|0")); assertEquals(130, calls)
    }
}
