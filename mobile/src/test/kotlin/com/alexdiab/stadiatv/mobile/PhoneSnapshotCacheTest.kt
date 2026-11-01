@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.alexdiab.stadiatv.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class PhoneSnapshotCacheTest {
    @Test fun `partial results remain useful but retry after error cooldown not full success TTL`() = runTest {
        var clock = 1L; var complete = false; var calls = 0
        val cache = PhoneSnapshotCache<String, Boolean>(this, ttlMillis = 3_600_000, now = { clock }, isComplete = { it }) { calls++; complete }
        cache.refresh("pool"); advanceUntilIdle()
        assertEquals(false, cache.state.value["pool"]!!.value); assertTrue(cache.state.value["pool"]!!.failed); assertEquals(0L, cache.state.value["pool"]!!.checkedAt)
        clock += 59_999; cache.refresh("pool"); advanceUntilIdle(); assertEquals(1, calls)
        clock++; complete = true; cache.refresh("pool"); advanceUntilIdle()
        assertEquals(2, calls); assertFalse(cache.state.value["pool"]!!.failed); assertEquals(clock, cache.state.value["pool"]!!.checkedAt)
    }
    @Test fun `partial refresh does not redate last complete request`() = runTest {
        var clock = 100L; var complete = true
        val cache = PhoneSnapshotCache<String, Boolean>(this, now = { clock }, isComplete = { it }) { complete }
        cache.refresh("pool"); advanceUntilIdle()
        clock = 200; complete = false; cache.refresh("pool", true); advanceUntilIdle()
        assertTrue(cache.state.value["pool"]!!.failed); assertEquals(100L, cache.state.value["pool"]!!.checkedAt)
    }
    @Test fun `duplicate loads coalesce and successful snapshots honor TTL`() = runTest {
        var calls = 0; var clock = 1L
        val cache = PhoneSnapshotCache<String, Int>(this, now = { clock }) { calls++; delay(100); calls }
        cache.refresh("nhl"); cache.refresh("nhl"); advanceUntilIdle()
        assertEquals(1, calls); assertEquals(1, cache.state.value["nhl"]!!.value)
        clock += 299_999; cache.refresh("nhl"); advanceUntilIdle(); assertEquals(1, calls)
        clock++; cache.refresh("nhl"); advanceUntilIdle(); assertEquals(2, calls)
    }
    @Test fun `failure retains good snapshot and exposes manual retry with error cooldown`() = runTest {
        var fail = false; var clock = 1L; var calls = 0
        val cache = PhoneSnapshotCache<String, Int>(this, now = { clock }) { calls++; if (fail) error("offline") else 42 }
        cache.refresh("nhl"); advanceUntilIdle(); fail = true; clock++
        cache.refresh("nhl", true); advanceUntilIdle()
        val retained = cache.state.value["nhl"]!!
        assertEquals(42, retained.value); assertTrue(retained.failed); assertFalse(retained.loading); assertEquals(1L, retained.checkedAt)
        cache.refresh("nhl"); advanceUntilIdle(); assertEquals(2, calls)
        fail = false; cache.refresh("nhl", true); advanceUntilIdle(); assertFalse(cache.state.value["nhl"]!!.failed)
    }
    @Test fun `timeout ends spinner and permits retry`() = runTest {
        val cache = PhoneSnapshotCache<String, Int>(this, timeoutMillis = 100) { delay(10_000); 42 }
        cache.refresh("slow"); advanceUntilIdle()
        assertTrue(cache.state.value["slow"]!!.failed); assertFalse(cache.state.value["slow"]!!.loading)
    }
    @Test fun `capacity evicts and cancels old requests without stale completions replacing a new generation`() = runTest {
        var aCalls = 0
        val cache = PhoneSnapshotCache<String, String>(this, capacity = 1) { key -> if (key == "a") aCalls++; delay(100); "$key-$aCalls" }
        cache.refresh("a"); runCurrent(); cache.refresh("b"); runCurrent(); cache.refresh("a"); advanceUntilIdle()
        assertEquals(setOf("a"), cache.state.value.keys); assertEquals("a-2", cache.state.value["a"]!!.value)
        assertFalse(cache.state.value["a"]!!.loading)
    }
    @Test fun `only two requests are in flight at once`() = runTest {
        var active = 0; var peak = 0
        val cache = PhoneSnapshotCache<String, Int>(this) { active++; peak = maxOf(active, peak); delay(100); active--; 42 }
        repeat(8) { cache.refresh("$it") }; advanceUntilIdle()
        assertEquals(2, peak); assertEquals(8, cache.state.value.size); assertTrue(cache.state.value.values.all { it.value == 42 && !it.loading })
    }
}
