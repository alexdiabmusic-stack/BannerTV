package com.alexdiab.stadiatv.lineup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMatcherTest {
    private val matcher = CatalogMatcher()

    private fun identify(name: String, group: String = "", epg: String? = null): Identity =
        matcher.identify(StreamName.parse(name, group), epg)

    private fun guide(id: String, vararg names: String): Identity = matcher.identifyGuideChannel(id, names.toList())

    @Test
    fun numberedFamiliesKeepTheirNumber() {
        for (n in 1..5) assertEquals("ca.tsn$n", identify("CA ★ TSN $n HD").catalog?.id)
        assertEquals("ca.tsn1", identify("CA★ TSN1").catalog?.id)
        assertEquals("ca.crave1", identify("CA ★ TMN 1").catalog?.id)
        assertEquals("ca.crave2", identify("CA ★ TMN2E  - CRAVE 2 TV  HD").catalog?.id)
    }

    @Test
    fun aCoastWordInTheNameIsNotAlwaysAVariant() {
        assertEquals("ca.sportsneteast", identify("CA ★ SPORTSNET EAST HD").catalog?.id)
        assertEquals("ca.sportsnetpacific", identify("CA★ SPORTSNET PACIFIC").catalog?.id)
        val central = identify("US ★ COMEDY CENTRAL HD EAST")
        assertEquals("us.comedycentral", central.catalog?.id)
        assertEquals("", central.variant)
        assertEquals("west", identify("US ★ COMEDY CENTRAL HD WEST").variant)
        // East is the main feed: the same entry with or without the word.
        assertEquals(identify("US ★ BET HD EAST").entryKey, identify("CA ★ BET").entryKey)
        assertNotEquals(identify("US ★ BET HD WEST").entryKey, identify("US ★ BET HD EAST").entryKey)
    }

    @Test
    fun localStationsKeepTheirCity() {
        val ottawa = identify("CA ★ CTV OTTAWA HD")
        assertEquals("ca.ctv", ottawa.catalog?.id)
        assertEquals("ottawa", ottawa.market)
        assertNotEquals(ottawa.entryKey, identify("CA ★ CTV TORONTO HD").entryKey)
        assertEquals(ottawa.entryKey, identify("CA ★ CTV OTTAWA").entryKey)

        val chicago = identify("US ★ ABC 7 CHICAGO IL HD")
        assertEquals("us.abc", chicago.catalog?.id)
        assertEquals("chicago", chicago.market)
        assertEquals("il", chicago.region)
    }

    @Test
    fun citiesThatShareANameAreToldApartByState() {
        val ms = identify("US ★ CBS 4 COLUMBUS MS HD")
        val oh = identify("US ★ CBS 10 COLUMBUS. OH HD")
        assertEquals("columbus", ms.market)
        assertNotEquals(ms.entryKey, oh.entryKey)
        // A state on one spelling and not the other is still one place.
        assertEquals(identify("US ★ CBS 7 DAYTON OH HD").entryKey, identify("US ★ CBS 45 HD DAYTON").entryKey)
    }

    @Test
    fun aCallsignAloneIsAStation() {
        val flint = identify("US ★ ABC 12 WJRT HD")
        assertEquals("us.abc", flint.catalog?.id)
        assertEquals("WJRT", flint.callsign)
        assertEquals("", flint.market)
        assertEquals("CFJP", identify("CAF ★ NOOVO CFJP ◉").callsign)
        // Four-letter places are not callsigns.
        assertEquals("waco", identify("US ★ CBS 10 WACO HD").market)
        assertEquals("kansas city", identify("US ★ ABC 9 KANSAS CITY HD").market)
    }

    @Test
    fun theNetworkAndTheStationCanBeTwoHalvesOfTheName() {
        val columbus = identify("US ★ MYTV 7  - WSYX COLUMBUS OH HD")
        assertEquals("us.mynetworktv", columbus.catalog?.id)
        assertEquals("WSYX", columbus.callsign)
        assertEquals("columbus", columbus.market)
    }

    @Test
    fun aBracketedCityOrCallsignIsAHint() {
        val greenville = identify("US ★ CBS 9 [GREENVILLE NC] (WNCT) HD")
        assertEquals("us.cbs", greenville.catalog?.id)
        assertEquals("WNCT", greenville.callsign)
        assertEquals("greenville", greenville.market)
        assertEquals("nc", greenville.region)
    }

    @Test
    fun usLocalNewsStreamsAreTheirOwnChannel() {
        val news = identify("US ★ CBS News Boston")
        val station = identify("US ★ CBS 4 BOSTON HD")
        assertEquals("us.cbs", news.catalog?.id)
        assertEquals("news", news.variant)
        assertNotEquals(station.entryKey, news.entryKey)
        // Canadian stations air their news on the channel itself.
        assertEquals(identify("CA ★ GLOBAL CALGARY").entryKey, identify("CA ★ GLOBAL NEWS CALGARY").entryKey)
    }

    @Test
    fun frenchCanadianFeedsMatchTheirCatalogueChannel() {
        val tva = identify("CAF ★ TVA SHERBROOKE HD")
        assertEquals("ca.tva", tva.catalog?.id)
        assertEquals("sherbrooke", tva.market)
        assertEquals("ca.noovo", identify("CAF ★ NOOVO CFJP ◉").catalog?.id)
        assertEquals("CA", tva.feedCountry)
    }

    @Test
    fun sameNamedChannelsInTwoCountriesAreTwoChannels() {
        assertEquals("us.bravo", identify("US ★ BRAVO HD EAST").catalog?.id)
        assertEquals("ca.bravocanada", identify("CA ★ BRAVO").catalog?.id)
        assertEquals("us.usanetwork", identify("US ★ USA NETWORK HD").catalog?.id)
    }

    @Test
    fun foreignFeedsDoNotBecomeNorthAmericanChannels() {
        assertNull(identify("NL ★ FOX HD").catalog)
        assertNull(identify("FR ★ COMEDY CENTRAL FHD").catalog)
        assertNull(guide("FOX.nl", "NL ★ FOX HD").catalog)
        assertNull(guide("ComedyCentral.be", "BE ★ COMEDY CENTRAL HD").catalog)
    }

    @Test
    fun aGuideIdIsReadTheSameWayAsAStreamName() {
        val ottawa = guide("CTVOttawa-CJOH.ca")
        assertEquals("ca.ctv", ottawa.catalog?.id)
        assertEquals("ottawa", ottawa.market)
        assertEquals("CJOH", ottawa.callsign)

        val glued = guide("ABCWJXX.us", "US ★ ABC 25 (WJXX) JACKSONVILLE HD")
        assertEquals("us.abc", glued.catalog?.id)
        assertEquals("WJXX", glued.callsign)
        // The provider's display name supplies the city the id doesn't carry.
        assertEquals("jacksonville", glued.market)

        val dotted = guide("CBC.News.Network.HD.ca2")
        assertEquals("ca.cbcnewsnetwork", dotted.catalog?.id)
        assertEquals("", dotted.market)
        assertEquals("ca.tsn1", guide("TSN1.ca").catalog?.id)
        assertEquals("pacific", guide("Bravo.(Pacific).us2").variant)
    }

    @Test
    fun sameStationNeedsEvidenceButAProvidersIdGetsTheBenefitOfTheDoubt() {
        val stream = identify("US ★ ABC 30 ST. LOUIS HD")
        val stPaul = guide("ABCKSTP.us", "US ★ ABC 5 ST. PAUL HD")
        val unknownPlace = guide("ABCKDNL.us")
        // A guide channel for another city is never this station's schedule, however similar the names.
        assertFalse(stream.compatibleWith(stPaul))
        assertFalse(stream.sameStationAs(stPaul))
        // One whose place is unknown can be accepted when the provider gave its id, but is never *searched* for.
        assertTrue(stream.compatibleWith(unknownPlace))
        assertFalse(stream.sameStationAs(unknownPlace))
        // Two different callsigns are two stations even if the city matches.
        assertFalse(identify("US ★ ABC 7 (WABC) NEW YORK").compatibleWith(guide("ABCWPVI.us", "US ★ ABC 6 (WPVI) NEW YORK")))
    }

    @Test
    fun anUncataloguedChannelIsGivenTheBenefitOfTheDoubt() {
        val obscure = identify("US ★ SOME LOCAL CHANNEL HD")
        assertNull(obscure.catalog)
        assertTrue(obscure.compatibleWith(guide("SomeLocal.us")))
    }
}
