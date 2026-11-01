package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.LineupBuilder
import org.junit.Assert.*
import org.junit.Test

class GuideInputsTest {
    private val builder = LineupBuilder()
    private val channels = listOf(
        Channel("one", "TSN 5", "https://streams.example/one.ts", null, "Sports", "test", "Constructed", tvgId = "TSN5.ca"),
        Channel("two", "Sportsnet One", "https://streams.example/two.ts", null, "Sports", "test", "Constructed", tvgId = "SportsnetOne.ca"),
    )
    private val complete = PlaylistsState(channels = channels, hasLoadedCache = true)

    @Test fun evenAMatchingFirstPlaylistCannotStartTheGuideBeforeAllSavedCachesLoad() {
        val partial = PlaylistsState(channels = channels.take(1), hasLoadedCache = false)
        assertFalse(guideInputsReady(builder.analyse(partial.channels), partial))
        assertFalse(guideInputsReady(builder.analyse(channels), complete.copy(hasLoadedCache = false)))
    }

    @Test fun fullPlaylistMustAlsoFinishChannelAnalysisBeforeGuideCompaction() {
        assertFalse(guideInputsReady(builder.analyse(channels.take(1)), complete))
        assertTrue(guideInputsReady(builder.analyse(channels), complete))
    }

    @Test fun sameCountChangedIdsUrlsHeadersOrGuideBindingsAreNotReady() {
        val analysed = builder.analyse(channels)
        for (replacement in listOf(channels.last().copy(id = "changed"), channels.last().copy(streamUrl = "https://streams.example/renewed.ts"),
            channels.last().copy(headers = mapOf("X-Constructed" to "renewed")), channels.last().copy(tvgId = "AnotherGuide.ca"))) {
            assertFalse(guideInputsReady(analysed, complete.copy(channels = listOf(channels.first(), replacement))))
        }
        assertFalse(guideInputsReady(analysed, complete.copy(channels = channels.reversed())))
    }

    @Test fun backgroundRefreshAndOneFailedPlaylistDoNotBlockAnAlreadyCompleteCachedLineup() {
        assertTrue(guideInputsReady(builder.analyse(channels), complete.copy(loading = setOf("test"), errors = mapOf("old" to "Unavailable"))))
    }

    @Test fun noEmptyBootstrapIsEverTreatedAsACompleteGuideInput() {
        assertFalse(guideInputsReady(emptyList(), PlaylistsState()))
        assertFalse(guideInputsReady(emptyList(), PlaylistsState(hasLoadedCache = true)))
    }
}
