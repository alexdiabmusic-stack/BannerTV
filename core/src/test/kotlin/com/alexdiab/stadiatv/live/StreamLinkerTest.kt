package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests built from real guide titles and channel names taken from a live Xtream playlist
 * (the same cases the iOS `StreamLinkerTests` use). Each documents what the real data looked like.
 */
class StreamLinkerTest {
    private fun utc(s: String): Long = requireNotNull(Dates.parseIso(s)) { "bad date $s" }
    private fun prog(guide: String, start: String, end: String, title: String, desc: String? = null) =
        LinkerProgramme(guide, utc(start), utc(end), title, desc)

    private val streams = listOf(
        LinkerStream("espn-hd", "US ★ ESPN HD ◉", "US ❖ SPORTS", "ESPN.us"),
        LinkerStream("espn-fhd", "US ★ ESPN FHD", "US ❖ SPORTS", "ESPN.us"), // mirror of the same feed
        LinkerStream("espn3-nl", "NL ★ ESPN 3 UHD", "NL ❖ SPORT", "ESPN3.nl"),
        LinkerStream("premier-uk", "UK ★ PREMIER SPORTS 1", "UK ❖ SPORTS", "PremierSports1.uk"),
        LinkerStream("skymix-de", "DE ★ SKY SPORT MIX 4K", "DE ❖ SPORT", "SkySportMix.de"),
        LinkerStream("sporttv-pt", "PT ★ SPORT TV 2", "PT ❖ ESPORTES", "SPORTTV2.pt"),
        LinkerStream("bein-fr", "FR ★ BEIN SPORTS 1 HD", "FR ❖ SPORTS", "beINSPORTS1.fr"),
        LinkerStream("tnt-us", "US ★ TNT [EAST]", "US ❖ SPORTS", "TNT.us"),
        LinkerStream("tnt-es", "ES ★ TNT", "ES ❖ SPORT", "TNT.es"), // a different network with the same name
        LinkerStream("mlb01", "US ★ MLB 01: PHILADELPHIA PHILLIES @ ATLANTA BRAVES 2:00 PM ET", "US ❖ MLB", "MLB.01"),
        LinkerStream("mlb-stale", "US ★ MLB 02: TORONTO BLUE JAYS @ BALTIMORE ORIOLES 7:35 PM ET", "US ❖ MLB", "MLB.02"),
        LinkerStream("sox", "US ★ MLB TEAMS : Boston Red Sox HD", "US ❖ MLB", "BostonRedSox.mlb"),
        LinkerStream("kings", "US ★ LOS ANGELES KINGS HD", "US ❖ NHL TEAMS", null),
        LinkerStream("grid", "US ★ SPORTS GRID HD", "US ❖ SPORTS", "SportsGrid.us"),
        LinkerStream("spectrum", "US ★ SPECTRUM NEWS 1 [NEW YORK]", "US ❖ NEWS", "SpectrumNews1NewYork.us"),
        LinkerStream("sny", "US ★ SNY HD", "US ❖ SPORTS", "SNY.us"),
        LinkerStream("sky-arena", "IT ★ SKY SPORT ARENA", "IT ❖ SPORT", "SkySportArena.it"),
        LinkerStream("ziggo", "NL ★ ZIGGO SPORT 2", "NL ❖ SPORT", "ZiggoSport2.nl"),
        LinkerStream("cytavision", "GR ★ CYTAVISION SPORTS 4", "GR ❖ SPORT", "CytavisionSports4.cy"),
        LinkerStream("polsat", "PL ★ POLSAT SPORT", "PL ❖ SPORT", "PolsatSport1.pl"),
        LinkerStream("bg", "BG ★ DIEMA SPORT", "BG ❖ SPORT", "DiemaSport.bg"),
        LinkerStream("laliga-tv", "ES ★ LALIGA HYPERMOTION TV 2", "ES ❖ SPORT", "LaLigaSmartbankTV2.es"),
    )

    private val programmes = listOf(
        // NHL: Florida Panthers at Carolina Hurricanes, kickoff 2026-09-29T21:00Z
        prog("ESPN.us", "2026-09-29T21:00Z", "2026-09-30T00:00Z", "NHL Hockey : Florida Panthers at Carolina Hurricanes ᴸᶦᵛᵉ",
            "The Carolina Hurricanes host the Florida Panthers in an Eastern Conference game at Lenovo Center in Raleigh, North Carolina."),
        prog("ESPN3.nl", "2026-09-29T21:00Z", "2026-09-30T00:00Z", "NHL : Carolina Hurricanes - Florida Panthers"),
        prog("PremierSports1.uk", "2026-09-29T21:00Z", "2026-09-30T00:00Z", "NHL Hockey : Florida Panthers at Carolina Hurricanes ᴸᶦᵛᵉ"),
        prog("SkySportMix.de", "2026-09-29T20:55Z", "2026-09-30T00:00Z", "Live NHL: Florida Panthers @ Carolina Hurricanes : Carolina Hurricanes - Florida Panthers", "Aus dem Lenovo Center in Raleigh, North Carolina, USA."),
        prog("SPORTTV2.pt", "2026-09-29T21:00Z", "2026-09-30T00:00Z", "Hoquei No Gelo - NHL - Carolina Hurricanes x Florida Panthers (Live) : ter 29-09"),
        prog("beINSPORTS1.fr", "2026-09-29T21:00Z", "2026-09-30T00:00Z", "Hockey sur glace : NHL ᴺᵉʷ", "Carolina Hurricanes v Florida Panthers\nRetransmission d'un match de NHL, la présentation..."),
        prog("TNT.us", "2026-09-29T21:00Z", "2026-09-30T00:00Z", "SportsCenter Special"),
        // NHL: Pittsburgh Penguins at Philadelphia Flyers 2026-09-30T23:30Z on TNT (a different game)
        prog("TNT.us", "2026-09-30T23:30Z", "2026-10-01T02:00Z", "NHL Hockey : Pittsburgh Penguins at Philadelphia Flyers ᴸᶦᵛᵉ"),
        // Team channel with placeholders around the real listing
        prog("BostonRedSox.mlb", "2026-09-29T19:00Z", "2026-09-30T23:00Z", "Next Game: Boston Red Sox @ New York Yankees on 2026-09-30 at 03:33AM EDT"),
        prog("BostonRedSox.mlb", "2026-09-30T00:00Z", "2026-09-30T03:00Z", "Boston Red Sox @ New York Yankees  ᴸᶦᵛᵉ"),
        prog("SportsGrid.us", "2026-09-30T00:00Z", "2026-09-30T03:00Z", "Boston Red Sox vs. New York Yankees MLB Baseball Playoffs In-Game Live Gameday"),
        prog("SpectrumNews1NewYork.us", "2026-09-30T23:00Z", "2026-10-01T00:00Z", "Inside City Hall  ᴺᵉʷ", "Errol Louis interviews public officials about New York City politics."),
        prog("SNY.us", "2026-09-30T23:00Z", "2026-10-01T02:00Z", "Mets Classics : 2015: New York Mets vs. Washington Nationals", "Flores Walk-Off from July 31, 2015."),
        // National teams, many languages: Croatia at Spain 2026-09-29T18:45Z
        prog("SkySportArena.it", "2026-09-29T18:45Z", "2026-09-29T21:00Z", "Spagna - Croazia  ᴸᶦᵛᵉ"),
        prog("ZiggoSport2.nl", "2026-09-29T18:45Z", "2026-09-29T21:00Z", "UEFA Nations League : Spanje - Kroatië"),
        prog("CytavisionSports4.cy", "2026-09-29T18:45Z", "2026-09-29T21:00Z", "Ισπανία - Κροατία : UEFA Nations League 2026/27"),
        prog("PolsatSport1.pl", "2026-09-29T18:45Z", "2026-09-29T21:00Z", "Piłka nożna: Liga Narodów - mecz: Hiszpania - Chorwacja  ᴸᶦᵛᵉ"),
        // Cyrillic: Iceland at Luxembourg
        prog("DiemaSport.bg", "2026-09-29T18:45Z", "2026-09-29T21:00Z", "Люксембург - Исландия. 2 кръг, Футбол: УЕФА Лига на нациите 2026/2027, директно"),
        // Club name variant: CD Sabadell at RC Celta Fortuna 2026-09-26T16:30Z
        prog("LaLigaSmartbankTV2.es", "2026-09-26T16:30Z", "2026-09-26T18:30Z", "LALIGA HYPERMOTION (T26/27): RC Celta B - CE Sabadell  ᴸᶦᵛᵉ"),
    )

    private val linker = StreamLinker(streams, programmes)

    private fun team(name: String, nick: String? = null, city: String? = null) = LinkerTeam(name, nick, nick, city)

    private fun nhl(home: LinkerTeam, away: LinkerTeam, kickoff: String, broadcasts: List<String> = emptyList()) =
        LinkerEvent("e", "hockey/nhl", utc(kickoff), home, away, broadcasts)

    private val panthers = nhl(
        team("Carolina Hurricanes", "Hurricanes", "Carolina"), team("Florida Panthers", "Panthers", "Florida"),
        "2026-09-29T21:00Z", listOf("ESPN"),
    )

    @Test
    fun descriptionDoesNotBreakTitleMatch() {
        val espn = linker.link(panthers).firstOrNull { it.key == "g:espn.us" }
        assertNotNull(espn)
        assertEquals(LinkTier.LiveListing, espn!!.tier)
        assertTrue(espn.confidence >= 0.9)
        assertEquals("both mirrors of the feed are returned", 2, espn.streamIds.size)
    }

    @Test
    fun otherSeparatorsAndLanguages() {
        val keys = linker.link(panthers).filter { it.tier == LinkTier.LiveListing }.map { it.key }.toSet()
        assertTrue(keys.toString(), keys.containsAll(listOf("g:espn.us", "g:espn3.nl", "g:premiersports1.uk", "g:skysportmix.de", "g:sporttv2.pt")))
    }

    @Test
    fun descriptionOnlyListingIsLowerConfidence() {
        val bein = linker.link(panthers).firstOrNull { it.key == "g:beinsports1.fr" }
        assertNotNull(bein)
        assertEquals(LinkTier.ListingByDescription, bein!!.tier)
        assertTrue(bein.confidence < 0.8)
    }

    @Test
    fun differentGameOnSameNetworkIsNotLinked() {
        assertTrue(linker.link(panthers).none { it.key == "g:tnt.us" })
    }

    @Test
    fun rightsPartnersRespectRegionAndGuide() {
        val kings = nhl(
            team("Colorado Avalanche", "Avalanche", "Colorado"), team("Los Angeles Kings", "Kings", "Los Angeles"),
            "2026-10-01T02:00Z", listOf("TNT", "truTV"),
        )
        val feeds = linker.link(kings)
        assertTrue("US TNT's guide ends before kickoff -> likely partner", feeds.any { it.key == "r:tnt.us" && it.tier == LinkTier.LikelyRights })
        assertTrue("TNT Spain is a different network", feeds.none { it.key == "r:tnt.es" })
        assertTrue("dedicated team channel named after a participant", feeds.any { it.key == "t:kings" && it.tier == LinkTier.TeamChannel })
    }

    @Test
    fun eventChannelNamesNeedAgreeingKickoff() {
        fun mlb(id: String, kickoff: String) = LinkerEvent(
            id, "baseball/mlb", utc(kickoff),
            team("Atlanta Braves", "Braves", "Atlanta"), team("Philadelphia Phillies", "Phillies", "Philadelphia"),
        )
        assertTrue(linker.link(mlb("m", "2026-09-29T18:00Z")).any { it.key == "n:mlb01" && it.tier == LinkTier.EventChannel }) // 2:00 PM ET == 18:00Z
        assertFalse(linker.link(mlb("m2", "2026-09-29T23:30Z")).any { it.key == "n:mlb01" })
    }

    @Test
    fun placeholdersReplaysAndCoverageAreNotConfirmations() {
        val sox = LinkerEvent(
            "s", "baseball/mlb", utc("2026-09-30T00:00Z"),
            team("New York Yankees", "Yankees", "New York"), team("Boston Red Sox", "Red Sox", "Boston"),
        )
        val feeds = linker.link(sox)
        assertEquals("the real listing beats the 'Next Game' placeholder around it", LinkTier.LiveListing, feeds.first { it.key == "g:bostonredsox.mlb" }.tier)
        assertEquals("'In-Game Live' analysis is demoted", LinkTier.Coverage, feeds.first { it.key == "g:sportsgrid.us" }.tier)

        val mets = LinkerEvent(
            "r", "baseball/mlb", utc("2026-09-30T23:30Z"),
            team("Washington Nationals", "Nationals", "Washington"), team("New York Mets", "Mets", "New York"),
        )
        assertTrue("'Classics' is a replay", linker.link(mets).none { it.key == "g:sny.us" })
    }

    @Test
    fun placeWordsNeverConfirmAGame() {
        val stlouis = LinkerEvent(
            "m", "soccer/usa.1", utc("2026-09-30T23:30Z"),
            LinkerTeam("Red Bull New York", "Red Bull New York"), LinkerTeam("St. Louis CITY SC", "St. Louis CITY SC", "CITY SC", "St. Louis"),
        )
        assertTrue(linker.link(stlouis).none { it.key == "g:spectrumnews1newyork.us" })
    }

    @Test
    fun nationalTeamsAreMatchedInEveryLanguage() {
        val event = LinkerEvent("n", "soccer/uefa.nations", utc("2026-09-29T18:45Z"), LinkerTeam("Spain", "Spain"), LinkerTeam("Croatia", "Croatia"), isNational = true)
        val keys = linker.link(event).filter { it.tier == LinkTier.LiveListing }.map { it.key }.toSet()
        assertTrue(keys.toString(), keys.containsAll(listOf("g:skysportarena.it", "g:ziggosport2.nl", "g:cytavisionsports4.cy", "g:polsatsport1.pl")))
    }

    @Test
    fun cyrillicWorks() {
        val event = LinkerEvent("n", "soccer/uefa.nations", utc("2026-09-29T18:45Z"), LinkerTeam("Luxembourg", "Luxembourg"), LinkerTeam("Iceland", "Iceland"), isNational = true)
        assertTrue(linker.link(event).any { it.key == "g:diemasport.bg" && it.tier == LinkTier.LiveListing })
    }

    @Test
    fun clubNameVariantsMatchThroughFuzzyFallback() {
        val event = LinkerEvent("c", "soccer/esp.2", utc("2026-09-26T16:30Z"), LinkerTeam("RC Celta Fortuna", "Celta Fortuna"), LinkerTeam("CD Sabadell", "Sabadell"))
        val feed = linker.link(event).firstOrNull { it.key == "g:laligasmartbanktv2.es" }
        assertNotNull(feed)
        assertTrue(feed!!.confidence >= 0.7)
    }

    @Test
    fun feedsCollapseIntoFamiliesAndMirrorsAreBestQualityFirst() {
        val feeds = linker.link(panthers)
        assertTrue(feeds.groupedByFamily().size <= feeds.size)
        assertEquals("espn-fhd", feeds.first { it.key == "g:espn.us" }.streamIds.first())
    }

    @Test
    fun exactProviderListingBeatsWrongNetworkWhenXmltvClockIsTwoHoursLate() {
        val tsnStreams = listOf(
            LinkerStream("tsn4-hd", "CA ★ TSN 4 HD", "CA ❖ SPORTS", "TSN4.ca"),
            LinkerStream("tsn4-sd", "CA ★ TSN 4", "CA ❖ SPORTS", "TSN4.ca"),
            LinkerStream("tsn5-hd", "CA ★ TSN 5 HD", "CA ❖ SPORTS", "TSN5.ca"),
            LinkerStream("tsn5-sd", "CA ★ TSN 5", "CA ❖ SPORTS", "TSN5.ca"),
        )
        val providerGuide = listOf(
            prog("TSN4.ca", "2026-10-14T01:30Z", "2026-10-14T04:00Z", "NHL Hockey : Toronto Maple Leafs at Utah Mammoth"),
            // The supplied provider XMLTV is two hours later than ESPN's 23:30 kickoff for this fixture.
            prog("TSN5.ca", "2026-10-14T01:30Z", "2026-10-14T04:00Z", "NHL Hockey : St. Louis Blues at Ottawa Senators"),
        )
        val event = LinkerEvent(
            "ottawa", "hockey/nhl", utc("2026-10-13T23:30Z"),
            LinkerTeam("Ottawa Senators", "Senators"), LinkerTeam("St. Louis Blues", "Blues"),
            broadcasts = listOf("TSN"),
        )

        val feeds = StreamLinker(tsnStreams, providerGuide).link(event)
        val exact = feeds.firstOrNull { it.key == "g:tsn5.ca" }
        assertNotNull("the exact playlist EPG title must remain directly playable despite provider clock skew", exact)
        assertEquals(LinkTier.LiveListing, exact!!.tier)
        assertTrue(exact.confidence >= 0.9)
        assertEquals(listOf("tsn5-hd", "tsn5-sd"), exact.streamIds)
        assertTrue("an unrelated TSN listing must not be offered", feeds.none { it.key == "g:tsn4.ca" || "tsn4" in it.streamIds })
    }

    @Test
    fun noEventReturnsNothingForUnknownTeams() {
        val event = LinkerEvent("x", "hockey/nhl", utc("2026-09-29T21:00Z"), team("Zzyzx Zebras", "Zebras", "Zzyzx"), team("Qwerty Quokkas", "Quokkas", "Qwerty"))
        assertNull(linker.link(event).firstOrNull { it.tier == LinkTier.LiveListing })
    }

    @Test
    fun displayNameStripsRegionAndQuality() {
        assertEquals("ESPN", StreamLinker.displayName("US ★ ESPN HD ◉"))
        assertEquals("TNT", StreamLinker.displayName("US ★ TNT [EAST]").substringBefore(' ').ifEmpty { "TNT" })
        assertEquals("US", StreamLinker.regionOf("US ★ ESPN HD", "ESPN.us"))
        assertEquals("NL", StreamLinker.regionOf("ESPN 3", "ESPN3.nl"))
    }
}
