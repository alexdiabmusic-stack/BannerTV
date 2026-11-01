package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhoneProfilesPresentationTest {
    @Test fun `official Opta and FotMob match players cannot be mistaken for ESPN or EPL profiles`() {
        val league = League.find("soccer/esp.1")!!
        val official = phoneSoccerAthlete(SportPlayer("laliga:p60772", "Courtois"), true)!!
        assertEquals("laliga:p60772", official.id); assertEquals("LALIGA", phonePlayerSource(league, official))
        assertNull(phoneSoccerAthlete(SportPlayer("fotmob:60772", "Reported player"), true))
        assertNull(phoneSoccerAthlete(SportPlayer("laliga:560", "Squad row, not Opta"), false))
        assertNull(phonePlayerSource(league, Athlete("epl:60772", "Foreign")))
    }
    @Test fun failedRosterIsNotPresentedAsSuccessfulEmptyAndHasOnlyTheStatusRetry() {
        assertNull(phoneRosterEmptyMessage(PhoneSnapshot()))
        assertNull(phoneRosterEmptyMessage(PhoneSnapshot(failed = true)))
        assertNull(phoneRosterEmptyMessage(PhoneSnapshot(value = emptyList(), loading = true)))
        assertNull(phoneRosterEmptyMessage(PhoneSnapshot(value = emptyList(), failed = true)))
        assertNotNull(phoneRosterEmptyMessage(PhoneSnapshot(value = emptyList(), checkedAt = 1000)))
    }
    @Test fun failedNativePlayerProfileStillNamesItsSourceAndDoesNotMixLeaguePrefixes() {
        val nba = League.find("basketball/nba")!!; val wnba = League.find("basketball/wnba")!!
        assertEquals("NBA", phonePlayerSource(nba, Athlete("nba:2544", "Reported")))
        assertEquals("WNBA", phonePlayerSource(wnba, Athlete("wnba:1", "Reported")))
        assertEquals("ESPN", phonePlayerSource(nba, Athlete("1", "Legacy")))
        assertNull(phonePlayerSource(wnba, Athlete("nba:2544", "Foreign")))
        assertEquals("EPL", phonePlayerSource(League.find("soccer/eng.1")!!, Athlete("epl:701", "Reported")))
    }
    @Test fun soccerLineupProfilesStayInAppAndNeverGuessAnIdentityFromANameOrWebUrl() {
        val player = SportPlayer("701", "Real Player", "9", "Forward", "https://www.premierleague.com/players/701")
        assertEquals("epl:701", phoneSoccerAthlete(player, true)!!.id)
        assertEquals("701", phoneSoccerAthlete(player, false)!!.id)
        assertNull(phoneSoccerAthlete(player.copy(id = "../701"), true))
        assertNull(phoneSoccerAthlete(player.copy(id = "", name = "Real Player"), true))
    }
    @Test fun mlsProfilesRetainTheirOpaqueNamespaceAndUseNativeSoccerTabs() {
        val league = League.find("soccer/usa.1")!!
        val person = phoneSoccerAthlete(SportPlayer("mls:MLS-OBJ-000396", "Leo Messi", "10"), false)!!
        assertEquals("mls:MLS-OBJ-000396", person.id); assertEquals("MLS", phonePlayerSource(league, person))
        assertNull(phoneSoccerAthlete(SportPlayer("MLS-OBJ-000396", "Unqualified"), false))
        val tabs = gameTabs(SportGroup.Soccer, GameCentre(providerStamp = ProviderStamp("MLS", "MLS-MAT-0009LX", 1)), false)
        assertEquals(listOf("Overview", "Timeline", "Lineups", "Team stats", "Commentary", "Live table"), tabs)
    }
    @Test fun seasonRangeOnlyFormatsAnActualConsecutiveEightDigitSeason() {
        assertEquals("2026–27", phoneSeasonLabel("20262027")); assertEquals("1999–00", phoneSeasonLabel("19992000"))
        assertEquals("2026 Regular Season", phoneSeasonLabel("2026 Regular Season")); assertEquals("20262030", phoneSeasonLabel("20262030"))
    }
    @Test fun nativeRosterInformationIsNotLabelledAsSeasonPerformance() {
        assertEquals("Player information", phonePlayerStatsTitle("Player information"))
        assertEquals("2026–27 statistics", phonePlayerStatsTitle("20262027"))
        assertEquals("Statistics", phonePlayerStatsTitle(null))
    }
    @Test fun typedFactsRetainRookieAndLongValuesAndNeverInventMissingBiography() {
        val person = Athlete("nhl:1", "Example", height = "75 in", experienceYears = 0, birthPlace = "Fort Wayne, Indiana, USA")
        val overview = AthleteOverview(person, bio = mapOf("Season" to "20262027", "Source" to "NHL"))
        val facts = phonePlayerFacts(person, overview)
        assertEquals("Rookie", facts["Experience"]); assertEquals("Fort Wayne, Indiana, USA", facts["Birthplace"])
        assertFalse(facts.containsKey("Age")); assertFalse(facts.containsKey("Season")); assertEquals("NHL", facts["Source"])
        assertTrue(phonePlayerFacts(Athlete("1", "Minimal"), null).isEmpty())
    }
    @Test fun enrichedHeaderUsesTheSelectedIdentityAndKeepsKnownRosterFields() {
        val person = Athlete("nhl:1", "Example Player", jersey = "9", height = "75 in")
        val loaded = Athlete("nhl:1", "Example Player", headshotUrl = "https://example.com/1.png")
        val merged = phoneProfileAthlete(person, loaded)
        assertEquals("9", merged.jersey); assertEquals("75 in", merged.height); assertEquals(loaded.headshotUrl, merged.headshotUrl)
        assertEquals(person, phoneProfileAthlete(person, loaded.copy(id = "1")))
    }
}
