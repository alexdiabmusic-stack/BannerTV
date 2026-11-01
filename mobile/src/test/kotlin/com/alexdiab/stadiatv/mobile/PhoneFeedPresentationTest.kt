package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FeedSections
import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhoneFeedPresentationTest {
    @Test fun coldOfflineAndFailedLoadsAreNotCalledAnEmptyScheduleOrSavedScores() {
        val offline = FeedSections(isLoading = false, refreshFailed = true, error = "Offline")
        assertEquals("Schedule unavailable", phoneScheduleEmptyTitle(offline))
        assertEquals("Scores unavailable · Tap to retry", phoneFeedFailureLabel(offline))
        assertFalse(phoneFeedHasKnownCounts(offline))
        assertEquals("Schedule unavailable", phoneScheduleEmptyTitle(offline.copy(refreshFailed = false)))
    }
    @Test fun successfulEmptyAndInitialLoadingHaveDifferentMeanings() {
        assertEquals("Loading schedule…", phoneScheduleEmptyTitle(FeedSections()))
        assertFalse(phoneFeedHasKnownCounts(FeedSections()))
        val empty = FeedSections(isLoading = false)
        assertEquals("No games today", phoneScheduleEmptyTitle(empty)); assertTrue(phoneFeedHasKnownCounts(empty))
        assertEquals("Nothing scheduled for tomorrow.", phoneScheduleEmptyTitle(empty, "Nothing scheduled for tomorrow."))
    }
    @Test fun aFailedRefreshCanStillLabelItsRealPreviouslyAcceptedCountsAndGames() {
        val side = TeamSide("Team", "Team", "T")
        val game = Match("test", League.find("hockey/nhl")!!, 1000, "Test", "Test", GameState.PRE, "Scheduled", side, side)
        val saved = FeedSections(all = listOf(game), isLoading = false, refreshFailed = true)
        assertEquals("Saved scores · Tap to refresh", phoneFeedFailureLabel(saved)); assertTrue(phoneFeedHasKnownCounts(saved))
        assertEquals("Schedule unavailable", phoneScheduleEmptyTitle(saved))
    }
}
