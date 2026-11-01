package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.parseJson
import org.junit.Assert.*
import org.junit.Test

/** Constructed ESPN/NHL-shaped profiles, with no claim of recorded current player statistics. */
class AthleteParityTest {
    @Test fun rosterPreservesOptionalBiographyAndReportedInjury() {
        val person = EspnApi.athlete(parseJson("""{"id":"123","displayName":"Example Player","jersey":"9","age":25,"displayHeight":"6' 2\"","displayWeight":"200 lbs","college":{"name":"Example College"},"position":{"abbreviation":"C","displayName":"Center"},"experience":{"years":0},"birthPlace":{"city":"Ottawa","state":"ON","country":"Canada"},"injuries":[{"status":"Out"}]}"""))!!
        assertEquals(25, person.age); assertEquals("Center", person.positionName); assertEquals(0, person.experienceYears)
        assertEquals("Ottawa, ON, Canada", person.birthPlace); assertTrue(person.injured)
        val minimal = EspnApi.athlete(parseJson("""{"id":"1","displayName":"Minimal"}"""))!!
        assertNull(minimal.age); assertNull(minimal.height); assertNull(minimal.experienceYears); assertFalse(minimal.injured)
    }
    @Test fun overviewKeepsFullStatisticNamesAndSeparateProviderHeadlineLabels() {
        val root = parseJson("""{"statistics":{"displayName":"2026 Regular Season","displayNames":["Points Per Game","Assists Per Game"],"labels":["PPG","APG"],"splits":[{"stats":["20.5","7.1"]}]}}""")
        val result = EspnApi.parseAthleteOverview(root, Athlete("1", "Example"), League.find("basketball/nba")!!)
        assertEquals("2026 Regular Season", result.statlineLabel)
        assertEquals("20.5", result.stats["Points Per Game"]); assertEquals("7.1", result.headlineStats["APG"])
    }
    @Test fun nativeHockeyProfileProvidesRealSeasonHeadlineValuesAndFormatsRatios() {
        val result = NativeHockeyProvider.parsePlayer(parseJson("""{"playerId":123,"firstName":{"default":"Example"},"lastName":{"default":"Goalie"},"position":"G","featuredStats":{"season":20262027,"regularSeason":{"subSeason":{"wins":3,"savePctg":0.923,"goalsAgainstAvg":2.1}}}}"""))
        assertEquals("20262027", result.statlineLabel); assertEquals(".923", result.stats["Save %"])
        assertEquals(setOf("Wins", "Save %", "Goals against average"), result.headlineStats.keys)
        assertFalse(result.headlineStats.containsKey("Shutouts"))
    }
}
