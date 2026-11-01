package com.alexdiab.stadiatv.net

import org.junit.Assert.*
import org.junit.Test

class HttpRetryAfterTest {
    private val noon = 1792756800000L // 2026-10-23 12:00:00 UTC
    @Test fun numericDeltaPreservesServerDelayAndRejectsInvalidOrOverflowValues() {
        assertEquals(300L, HttpRetryAfter.seconds(" 300 ", noon))
        assertEquals(0L, HttpRetryAfter.seconds("0", noon))
        assertNull(HttpRetryAfter.seconds("-1", noon)); assertNull(HttpRetryAfter.seconds("1.5", noon))
        assertNull(HttpRetryAfter.seconds("9".repeat(129), noon)); assertNull(HttpRetryAfter.seconds(null, noon))
    }
    @Test fun httpDateWaitsUntilDeadlineIncludingPartialSecondsRatherThanRetryingEarly() {
        assertEquals(300L, HttpRetryAfter.seconds("Fri, 23 Oct 2026 12:05:00 GMT", noon))
        assertEquals(300L, HttpRetryAfter.seconds("Fri, 23 Oct 2026 12:05:00 GMT", noon + 1))
        assertEquals(0L, HttpRetryAfter.seconds("Fri, 23 Oct 2026 11:59:59 GMT", noon))
    }
    @Test fun malformedDatesDoNotBecomeSilentRolloverOrConsumeOnlyPartOfTheHeader() {
        assertNull(HttpRetryAfter.seconds("Sun, 31 Feb 2026 12:00:00 GMT", noon))
        assertNull(HttpRetryAfter.seconds("Fri, 23 Oct 2026 12:05:00 GMT trailing", noon))
        assertNull(HttpRetryAfter.seconds("not a date", noon))
    }
}
