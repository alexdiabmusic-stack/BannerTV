package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamAvailabilityThreadTest {
    private fun match(state: GameState = GameState.LIVE) = Match("constructed", League.find("hockey/nhl")!!, 1, "Rangers at Senators", "NYR @ OTT", state, "",
        TeamSide("Ottawa Senators", "Senators", "OTT"), TeamSide("New York Rangers", "Rangers", "NYR"))
    private val channel = Channel("test", "TSN 5", "https://streams.example/channel.ts", null, "Sports", "test", "Test playlist")
    @Test fun immediateScreenEnsureBuildsAndRanksOffItsCallingThread(): Unit = runBlocking {
        val caller = Thread.currentThread()
        var mapperThread: Thread? = null
        val store = StreamAvailabilityStore(this)
        store.ensure(match(), listOf(channel), emptyList(), { mapperThread = Thread.currentThread(); "guide" }, 1)
        assertNotNull(mapperThread); assertNotSame(caller, mapperThread)
        assertTrue(store.sources.value.containsKey(match().key))
    }
    @Test fun finalFixtureNeverBuildsAPlaylistIndexOrPublishesALiveChannel(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        store.ensure(match(GameState.FINAL), listOf(channel), emptyList(), { error("Final fixture must not index") }, 1)
        assertTrue(store.sources.value.isEmpty())
    }
    @Test fun teammatesReuseOneLazyGuideProjectionUntilThePlaylistOrGuideRevisionChanges(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        var projections = 0
        val project = { projections++; emptyList<Programme>() }
        repeat(30) { store.ensure(match().copy(id = "fixture-$it"), listOf(channel), project, { "guide" }, 1) }
        assertEquals(1, projections)
        store.ensure(match(), listOf(channel), project, { "guide" }, 2)
        assertEquals(2, projections)
        store.ensure(match(), listOf(channel.copy(name = "Renamed stream")), project, { "guide" }, 2)
        assertEquals(3, projections)
    }
    @Test fun aRescheduledSameIdDoesNotReuseAnOldConfirmedGuideListing(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        val game = match()
        val programmes = listOf(Programme("guide", game.startMillis, game.startMillis + 3 * 3_600_000, "NHL Hockey: New York Rangers at Ottawa Senators"))
        store.ensure(game, listOf(channel), programmes, { "guide" }, 1)
        assertEquals(1, store.sourcesFor(game).confirmed.size)
        assertTrue(store.sourcesFor(game.copy(state = GameState.FINAL)).sources.isEmpty())
        store.invalidate()
        assertTrue(store.sourcesFor(game).sources.isEmpty())
        store.ensure(game, listOf(channel), programmes, { "guide" }, 1)
        assertEquals(1, store.sourcesFor(game).confirmed.size)
        val changed = game.copy(startMillis = game.startMillis + 24 * 3_600_000)
        assertTrue(store.sourcesFor(changed).confirmed.isEmpty()) // Before the async ensure can run.
        store.ensure(changed, listOf(channel), programmes, { "guide" }, 1)
        assertTrue(store.sourcesFor(changed).confirmed.isEmpty())
        assertTrue(store.sources.value.getValue(game.key).confirmed.isEmpty()) // Actual published recomputation too.
    }
    @Test fun correctedOpponentsInvalidateSameIdConfirmationEvenWithAnUnchangedGuide(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        val game = match()
        val programmes = listOf(Programme("guide", game.startMillis, game.startMillis + 3 * 3_600_000, "NHL Hockey: New York Rangers at Ottawa Senators"))
        store.ensure(game, listOf(channel), programmes, { "guide" }, 1)
        assertEquals(1, store.sourcesFor(game).confirmed.size)
        val changed = game.copy(away = TeamSide("Pittsburgh Penguins", "Penguins", "PIT"))
        assertTrue(store.sourcesFor(changed).confirmed.isEmpty())
        store.ensure(changed, listOf(channel), programmes, { "guide" }, 1)
        assertTrue(store.sourcesFor(changed).confirmed.isEmpty())
        assertTrue(store.sources.value.getValue(game.key).confirmed.isEmpty())
    }
    @Test fun samePlaylistIdsWithRefreshedPlaybackUrlAndHeadersReplaceOldChannelReferences(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        val game = match()
        val programmes = listOf(Programme("guide", game.startMillis, game.startMillis + 3 * 3_600_000, "NHL Hockey: New York Rangers at Ottawa Senators"))
        store.ensure(game, listOf(channel), programmes, { "guide" }, 1)
        val refreshed = channel.copy(streamUrl = "https://streams.example/refreshed.ts", headers = mapOf("X-Test-Playback" to "new"))
        store.ensure(game, listOf(refreshed), programmes, { "guide" }, 1)
        val accepted = store.sourcesFor(game).confirmed.single().channel
        assertEquals(channel.key, accepted.key); assertEquals(refreshed.streamUrl, accepted.streamUrl)
        assertEquals(refreshed.headers, accepted.headers)
    }
}
