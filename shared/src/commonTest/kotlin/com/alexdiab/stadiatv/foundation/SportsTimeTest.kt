package com.alexdiab.stadiatv.foundation

import kotlin.test.*

class SportsTimeTest {
    @Test fun easternScheduleUsesCalendarDaysAcrossDST() {
        val start=SportsTime.parseLocal("2026-11-01", "yyyy-MM-dd", "America/Toronto")!!
        val tomorrow=SportsTime.addLocalDays(start,1,"America/Toronto")
        assertEquals(25*3_600_000L,tomorrow-start)
        assertEquals("2026-11-02",SportsTime.format("yyyy-MM-dd",tomorrow,"America/Toronto"))
    }
    @Test fun fractionalAndOffsetTimestampsHaveTheSameInstantOnEveryPlatform() {
        val expected=SportsTime.parseIso("2026-10-31T23:30:00.123Z")
        assertNotNull(expected)
        assertEquals(expected,SportsTime.parseIso("2026-10-31T19:30:00.1234567-04:00"))
        assertNull(SportsTime.parseIso("not a date"))
        assertNull(SportsTime.parseLocal("2026-02-30","yyyy-MM-dd","UTC"))
    }
    @Test fun wireNumbersAndURIComponentsArePlatformIndependent() {
        assertEquals("12300000",plainNumber("1.23E7"))
        assertEquals("0.00012",plainNumber("1.2e-4"))
        assertEquals("-0.25",decimal(-0.25,2))
        assertEquals("caf%C3%A9%2F%20%26%3A%25",encodeComponent("café/ &:%"))
    }
}
