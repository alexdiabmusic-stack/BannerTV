package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.live.Programme
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneProgrammeStatusTest {
    private val listing = Programme("guide", 1_000L, 2_000L, "NHL Hockey LIVE")

    @Test fun futureLiveTitleDoesNotMeanOnNow() {
        assertEquals(PhoneProgrammeStatus.Upcoming, phoneProgrammeStatus(listing, 999L))
    }

    @Test fun exactStartIsIncludedAndExactEndIsNot() {
        assertEquals(PhoneProgrammeStatus.OnNow, phoneProgrammeStatus(listing, 1_000L))
        assertEquals(PhoneProgrammeStatus.OnNow, phoneProgrammeStatus(listing, 1_999L))
        assertEquals(PhoneProgrammeStatus.Ended, phoneProgrammeStatus(listing, 2_000L))
    }

    @Test fun emptyAndReversedTimesCannotClaimUpcomingOrLive() {
        assertEquals(PhoneProgrammeStatus.Unknown, phoneProgrammeStatus(listing.copy(endMillis = 1_000L), 0L))
        assertEquals(PhoneProgrammeStatus.Unknown, phoneProgrammeStatus(listing.copy(endMillis = 900L), 1_000L))
    }
}
