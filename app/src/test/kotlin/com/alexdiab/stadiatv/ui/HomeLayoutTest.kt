package com.alexdiab.stadiatv.ui

import com.alexdiab.stadiatv.ui.home.Daypart
import com.alexdiab.stadiatv.ui.home.HomeLayout
import com.alexdiab.stadiatv.ui.home.HomePart
import com.alexdiab.stadiatv.ui.nav.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun theHourPicksThePartOfTheDay() {
        assertEquals(Daypart.LateNight, Daypart.at(2))
        assertEquals(Daypart.Morning, Daypart.at(5))
        assertEquals(Daypart.Morning, Daypart.at(11))
        assertEquals(Daypart.Afternoon, Daypart.at(12))
        assertEquals(Daypart.Evening, Daypart.at(17))
        assertEquals(Daypart.Evening, Daypart.at(23))
        assertEquals(Daypart.LateNight, Daypart.at(0))
    }

    @Test
    fun liveGamesAlwaysLeadBecauseTheyCannotWait() {
        for (daypart in Daypart.entries) assertEquals(HomePart.LiveAround, HomeLayout.plan(daypart, hasLive = true).first())
    }

    @Test
    fun withNothingLiveTheMorningLooksBackAndTheEveningLooksAhead() {
        assertEquals(HomePart.Results, HomeLayout.plan(Daypart.Morning, hasLive = false).first())
        assertEquals(HomePart.Results, HomeLayout.plan(Daypart.LateNight, hasLive = false).first())
        assertEquals(HomePart.Picks, HomeLayout.plan(Daypart.Evening, hasLive = false).first())
        assertEquals(HomePart.Picks, HomeLayout.plan(Daypart.Afternoon, hasLive = false).first())
    }

    @Test
    fun everyPlanHasEachBandAtMostOnceAndKeepsResumeHandy() {
        for (daypart in Daypart.entries) for (live in listOf(true, false)) {
            val plan = HomeLayout.plan(daypart, live)
            assertEquals(plan.toSet().size, plan.size)
            assertTrue(HomePart.Continue in plan.take(3))
            assertEquals(live, HomePart.LiveAround in plan)
        }
    }

    @Test
    fun titlesFollowTheDayAndCountsReadNaturally() {
        assertEquals("Your Sports Tonight", Daypart.Evening.title)
        assertEquals("Your Sports This Morning", Daypart.Morning.title)
        assertEquals("Tonight's Top Picks", HomeLayout.picksTitle(Daypart.Evening))
        assertEquals("Today's Top Picks", HomeLayout.picksTitle(Daypart.Afternoon))
        assertEquals("Last Night's Results", HomeLayout.resultsTitle(Daypart.Morning))
        assertEquals("1 live game", HomeLayout.liveCountLabel(1))
        assertEquals("3 live games", HomeLayout.liveCountLabel(3))
    }

    @Test
    fun fiveDestinationsWithSettingsAsASecondaryIcon() {
        assertEquals(listOf(Tab.Home, Tab.Sports, Tab.LiveTv, Tab.Guide, Tab.MyBanner), Tab.primary)
        assertTrue(Tab.Settings.secondary)
        assertFalse(Tab.Settings in Tab.primary)
    }
}
