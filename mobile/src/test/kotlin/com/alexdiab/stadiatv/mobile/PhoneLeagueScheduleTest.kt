package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class PhoneLeagueScheduleTest {
    @Test fun fantasyEightDayWindowHasItsOwnCacheIdentityAndIncludesTheLastLocalCalendarDay() {
        val first = Dates.parseIso("2026-10-24T00:00:00Z")!!
        val week = PhoneLeagueScheduleQuery("hockey/nhl", first)
        val fantasy = week.copy(days = 8)
        assertNotEquals(week, fantasy); assertEquals(8, fantasy.days)
        assertEquals(Dates.addDays(first, 8), fantasy.endExclusive)
    }
    @Test fun scheduleRequestWindowsAreBoundedInsteadOfAllowingUnboundedProviderLoads() {
        for (days in listOf(-1, 0, 15, Int.MAX_VALUE)) assertTrue(runCatching { PhoneLeagueScheduleQuery("hockey/nhl", 1, days) }.isFailure)
    }
    @Test fun rangeUsesSevenLocalCalendarDaysAcrossDstNotFixed24HourOffsets() {
        val old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("America/Toronto"))
        try {
            val first = phoneCalendarDay(2026, 9, 31)!!
            val range = PhoneLeagueScheduleQuery("basketball/nba", first)
            assertEquals(Dates.parseIso("2026-11-07T00:00:00-05:00"), range.endExclusive)
            assertEquals(169 * 3_600_000L, range.endExclusive - range.first)
        } finally { TimeZone.setDefault(old) }
    }
    @Test fun nativePickerDateDoesNotSilentlyRollInvalidDatesIntoAnotherMonth() {
        val zone = TimeZone.getTimeZone("UTC")
        assertNull(phoneCalendarDay(2026, 1, 29, zone)); assertNull(phoneCalendarDay(2026, 12, 1, zone))
        assertNull(phoneCalendarDay(2201, 0, 1, zone)); assertNull(phoneCalendarDay(2026, 0, -1, zone))
        assertEquals(Dates.parseIso("2024-02-29T00:00:00Z"), phoneCalendarDay(2024, 1, 29, zone))
    }
    @Test fun historicalCalendarPageIdentityDoesNotCollideWithCurrentOrAnotherGameDate() {
        val league = League.find("basketball/nba")!!
        val current = PhonePage.LeagueHub(league, "Schedule")
        assertNotEquals(phonePageStateKey(current), phonePageStateKey(current.copy(calendarDate = 1000)))
        assertNotEquals(phonePageStateKey(current.copy(calendarDate = 1000)), phonePageStateKey(current.copy(calendarDate = 2000)))
        assertFalse(phonePageStateKey(current.copy(calendarDate = 1000)).contains("basketball"))
    }
}
