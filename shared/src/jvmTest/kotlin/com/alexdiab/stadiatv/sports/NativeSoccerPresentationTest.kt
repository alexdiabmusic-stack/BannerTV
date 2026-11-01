package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.*
import org.junit.Test

/** Constructed incidents exercise presentation rules; they are not claimed real provider matches. */
class NativeSoccerPresentationTest {
    private fun play(id: String, period: Int, kind: SoccerEventKind) = Play(id, "", "", "", null, null, null, false,
        periodNumber = period, soccerEvent = SoccerEvent(kind))

    @Test fun addedTimeUsesTheSuppliedPeriodAndNeverGuessesTheHalf() {
        assertEquals("45+2′", NativeSoccerPresentation.eventClock("47", 1))
        assertEquals("90+4′", NativeSoccerPresentation.eventClock("94", 2))
        assertEquals("105+2′", NativeSoccerPresentation.eventClock("107", 3))
        assertEquals("120+3′", NativeSoccerPresentation.eventClock("123", 4))
        assertEquals("45+2′", NativeSoccerPresentation.eventClock("45+2", 1))
        assertEquals("47′", NativeSoccerPresentation.eventClock("47", 0))
        assertEquals("", NativeSoccerPresentation.eventClock("47:invalid", 1))
        assertEquals("", NativeSoccerPresentation.eventClock(null, 1))
    }

    @Test fun sectionsKeepChronologicalProviderOrderAndUnknownPeriodsRemainVisible() {
        val plays = listOf(play("other", 0, SoccerEventKind.Unknown), play("second", 2, SoccerEventKind.Substitution),
            play("card", 1, SoccerEventKind.YellowCard), play("goal", 1, SoccerEventKind.Goal))
        val all = NativeSoccerPresentation.sections(plays)
        assertEquals(listOf(1, 2, 0), all.map { it.number })
        assertEquals(listOf("goal", "card"), all.first().events.map { it.id })
        assertEquals(listOf("card"), NativeSoccerPresentation.sections(plays, NativeSoccerPresentation.Filter.Cards).single().events.map { it.id })
        assertEquals(listOf("goal", "card"), NativeSoccerPresentation.keyEvents(plays).map { it.id })
        assertTrue(NativeSoccerPresentation.Filter.Goals.includes(play("penalty", 2, SoccerEventKind.MissedPenalty)))
        assertFalse(NativeSoccerPresentation.Filter.Shots.includes(play("card", 1, SoccerEventKind.RedCard)))
    }

    @Test fun overviewStatsAreCuratedWithoutInventingMissingMetrics() {
        val stats = listOf(StatComparison("Corners", "1", "2"), StatComparison("Passes", "200", "300"),
            StatComparison("Possession", "40%", "60%"), StatComparison("Expected goals", "0.20", "1.20"))
        assertEquals(listOf("Expected goals", "Possession", "Corners"), NativeSoccerPresentation.keyStats(stats).map { it.label })
    }

    @Test fun officialsDistinguishInvalidAndEmptyResourcesAndPreserveProviderRoles() {
        assertNull(NativeSoccerDetails.officials(parseJson("{}")))
        assertNull(NativeSoccerDetails.officials(parseJson("""{"matchId":"other","matchOfficials":[]}"""), "selected"))
        assertEquals(emptyList<SoccerOfficial>(), NativeSoccerDetails.officials(parseJson("""{"matchOfficials":[]}""")))
        val result = NativeSoccerDetails.officials(parseJson("""{"matchOfficials":[{"official":{"name":" Test Ref "},"type":"Referee"},{"official":{"name":"Test VAR"},"type":"VAR"},{"official":{"name":" "}},{"official":{"name":"Test Ref"},"type":"Referee"}]}"""))!!
        assertEquals(listOf(SoccerOfficial("Test Ref", "Referee"), SoccerOfficial("Test VAR", "VAR")), result)
    }
}
