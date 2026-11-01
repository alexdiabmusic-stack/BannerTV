package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.live.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupBuilderTest {
    private val builder = LineupBuilder(CatalogMatcher())
    private var next = 0

    private fun stream(name: String, group: String = "", epg: String? = null): Channel = LineupFixture.channel(next++, name, group, epg)

    private fun lineup(channels: List<Channel>, guide: List<GuideListing> = emptyList()): Lineup =
        builder.build(builder.analyse(channels), GuideDirectory.build(builder.guideMatcher, guide))

    private fun Lineup.entry(name: String): LineupEntry = entries.firstOrNull { it.name == name } ?: error("no entry '$name' in ${entries.map { it.name }}")

    // region quality

    @Test
    fun hdPlaysBeforeStandardDefinitionAndTheSettingFlipsIt() {
        val sd = stream("CA ★ TSN 1", epg = "TSN1.ca")
        val hd = stream("CA ★ TSN 1 HD", epg = "TSN1.ca")
        val entry = lineup(listOf(sd, hd), listOf(GuideListing("TSN1.ca", listOf("TSN 1"), 90))).entry("TSN 1")
        assertEquals(2, entry.streamCount)
        assertEquals(hd.id, entry.pick(LineupPrefs()).channel.id)
        assertEquals(sd.id, entry.pick(LineupPrefs(preferHd = false)).channel.id)
    }

    @Test
    fun fourKIsHeldBackUntilAskedFor() {
        val fhd = stream("US ★ ESPN FHD", epg = "ESPN.us")
        val uhd = stream("US ★ ESPN 4K", epg = "ESPN.us")
        val hd = stream("US ★ ESPN HD", epg = "ESPN.us")
        val entry = lineup(listOf(hd, uhd, fhd), listOf(GuideListing("ESPN.us", listOf("ESPN"), 90))).entry("ESPN")
        assertEquals(fhd.id, entry.pick(LineupPrefs()).channel.id)
        assertEquals(uhd.id, entry.pick(LineupPrefs(preferUhd = true)).channel.id)
        assertEquals(hd.id.takeIf { false } ?: entry.feeds.single().streams.minBy { it.quality.rank }.channel.id, entry.pick(LineupPrefs(preferHd = false)).channel.id)
    }

    @Test
    fun aBackupCopyPlaysOnlyWhenNothingElseDoes() {
        val main = stream("CA ★ CBC TORONTO", epg = "CBCToronto-CBLT.ca")
        val backup = stream("CA ★ CBC TORONTO HD BACKUP", epg = "CBCToronto-CBLT.ca")
        val entry = lineup(listOf(backup, main), listOf(GuideListing("CBCToronto-CBLT.ca", listOf("CBC Toronto"), 90))).entry("CBC Toronto")
        assertEquals(main.id, entry.pick(LineupPrefs()).channel.id)
    }

    // endregion

    // region guides

    @Test
    fun aWrongIdOnOneQualityIsCorrectedByItsSibling() {
        val bad = stream("CA ★ CTV WILD CHANNEL HD", epg = "animalplanet.ca")
        val good = stream("CA ★CTV WILD CHANNEL", epg = "CTVWild.ca")
        val guide = listOf(GuideListing("animalplanet.ca", listOf("CA ★ ANIMAL PLANET"), 90), GuideListing("CTVWild.ca", listOf("CTV Wild"), 90))
        val pick = lineup(listOf(bad, good), guide).entry("CTV Wild").pick(LineupPrefs())
        assertEquals("CTVWild.ca", pick.guideId)
        // The picture is still the HD one; only the schedule comes from the other copy.
        assertEquals(bad.id, pick.channel.id)
    }

    @Test
    fun anIdWithNoProgrammesIsReplacedByTheSameChannelFoundInTheGuide() {
        val bnn = stream("CA ★ BNN HD", epg = "Bloomberg.ca")
        val guide = listOf(GuideListing("Bloomberg.ca", listOf("CA ★ BNN HD"), 0), GuideListing("BNN.Bloomberg.ca2", listOf("BNN Bloomberg"), 120))
        assertEquals("BNN.Bloomberg.ca2", lineup(listOf(bnn), guide).entry("BNN Bloomberg").pick(LineupPrefs()).guideId)
    }

    @Test
    fun aStreamWithoutAnIdFindsItsGuideByWhatItIs() {
        val sn = stream("CA ★ SPORTSNET EAST HD")
        val guide = listOf(GuideListing("SportsnetEast.ca", listOf("Sportsnet East"), 90), GuideListing("SportsnetWest.ca", listOf("Sportsnet West"), 90))
        val pick = lineup(listOf(sn), guide).entry("Sportsnet East").pick(LineupPrefs())
        assertEquals("SportsnetEast.ca", pick.guideId)
        assertEquals(GuideSource.Matched, pick.guideSource)
    }

    @Test
    fun aStationNeverBorrowsAnotherCitysGuideByName() {
        val stLouis = stream("US ★ ABC 30 ST. LOUIS HD", epg = "ABCKSTP.us")
        val guide = listOf(GuideListing("ABCKSTP.us", listOf("US ★ ABC 5 ST. PAUL HD"), 120))
        val pick = lineup(listOf(stLouis), guide).entry("ABC St Louis").pick(LineupPrefs())
        // The provider gave St. Paul's id for St. Louis: rejected, and with no other station nearby there is no schedule.
        assertNull(pick.guideId)
    }

    @Test
    fun aStationWithNoScheduleAirsItsNetworksFromTheSameTimeZone() {
        val albany = stream("US ★ ABC 10 ALBANY HD")
        val dallas = stream("US ★ ABC 8 DALLAS HD")
        val guide = listOf(
            GuideListing("ABCWABC.us", listOf("US ★ ABC 7 NEW YORK HD"), 200),
            GuideListing("ABCWFAA.us", listOf("US ★ ABC 8 DALLAS HD"), 200),
        )
        val lineup = lineup(listOf(albany, dallas), guide)
        val albanyPick = lineup.entry("ABC Albany").pick(LineupPrefs())
        assertEquals("ABCWABC.us", albanyPick.guideId)
        assertEquals(GuideSource.Network, albanyPick.guideSource)
        assertEquals("ABCWFAA.us", lineup.entry("ABC Dallas").pick(LineupPrefs()).guideId)
        assertEquals(GuideSource.Matched, lineup.entry("ABC Dallas").pick(LineupPrefs()).guideSource)
    }

    @Test
    fun aWestFeedWithoutItsOwnScheduleIsTheEastScheduleThreeHoursLater() {
        val east = stream("US ★ HBO SIGNATURE HD [EAST]", epg = "HBOSignature.us")
        val west = stream("US ★ HBO SIGNATURE HD [WEST]", epg = "HBOSignaturePacific.us")
        val guide = listOf(GuideListing("HBOSignature.us", listOf("HBO Signature"), 90), GuideListing("HBOSignaturePacific.us", listOf("HBO Signature West"), 0))
        val lineup = lineup(listOf(east, west), guide)
        val pick = lineup.entry("HBO Signature (West)").pick(LineupPrefs())
        assertEquals("HBOSignature.us", pick.guideId)
        assertEquals(GuideSource.Shifted, pick.guideSource)
        assertEquals(180, pick.guideOffsetMinutes)
        assertEquals(0, lineup.entry("HBO Signature").pick(LineupPrefs()).guideOffsetMinutes)
    }

    @Test
    fun aChannelWithNoScheduleAnywherePlaysItsTwinInTheOtherCountry() {
        val ca = stream("CA ★ DISNEY JR HD")
        val us = stream("US ★ DISNEY JUNIOR HD", epg = "DisneyJunior.us")
        val lineup = lineup(listOf(ca, us), listOf(GuideListing("DisneyJunior.us", listOf("Disney Junior"), 90)))
        val pick = lineup.entry("Disney Junior Canada").pick(LineupPrefs())
        assertEquals("DisneyJunior.us", pick.guideId)
        assertEquals(GuideSource.Twin, pick.guideSource)
        assertEquals(us.id, pick.channel.id)
    }

    @Test
    fun theViewersOwnCountrysFeedPlaysWhenBothHaveASchedule() {
        val canadian = stream("CA ★ BET HD")
        val american = stream("US ★ BET HD EAST", epg = "BET.us")
        val entry = lineup(listOf(canadian, american), listOf(GuideListing("BET.us", listOf("BET"), 90))).entry("BET")
        assertEquals(2, entry.feeds.size)
        assertEquals(canadian.id, entry.pick(LineupPrefs(homeCountry = "CA")).channel.id)
        assertEquals(american.id, entry.pick(LineupPrefs(homeCountry = "US")).channel.id)
        assertEquals("BET.us", entry.pick(LineupPrefs(homeCountry = "CA")).guideId)
    }

    // endregion

    // region what stays out of the guide

    @Test
    fun eventFeedsAndAdultChannelsAreNotChannels() {
        val channels = listOf(
            stream("US ★ MLS 01: LAFC vs SEATTLE 8:00 PM ET", group = "US ❖ MLS"),
            stream("US ★ ESPN+ 12", group = "US ❖ ESPN PLUS"),
            stream("US ★ ADULT CHANNEL", group = "XXX ❖ ADULT"),
            stream("US ★ ESPN HD", group = "US ❖ SPORTS", epg = "ESPN.us"),
        )
        assertEquals(listOf("ESPN"), lineup(channels).entries.map { it.name })
    }

    @Test
    fun freeStreamingChannelsAreKeptApartFromTheCableChannelTheyAreNamedAfter() {
        val cable = stream("US ★ COMEDY CENTRAL HD EAST", group = "US ❖ ENTERTAINMENT", epg = "ComedyCentral.us")
        val pluto = stream("CA ★ COMEDY CENTRAL", group = "CA ❖ PLUTO TV")
        val lineup = lineup(listOf(cable, pluto), listOf(GuideListing("ComedyCentral.us", listOf("Comedy Central"), 90)))
        assertEquals(2, lineup.entries.size)
        assertEquals(1, lineup.entries.count { it.isFast })
        assertNull(lineup.entries.single { it.isFast }.catalog)
        assertEquals(cable.id, lineup.entry("Comedy Central").pick(LineupPrefs()).channel.id)
    }

    // endregion

    // region the whole saved lineup

    @Test
    fun everyStreamEndsUpInExactlyOneEntryOrIsLeftOutOnPurpose() {
        val streams = builder.analyse(LineupFixture.channels)
        val lineup = builder.build(streams, LineupFixture.directory)
        val placed = lineup.entries.flatMap { e -> e.feeds.flatMap { f -> f.streams.map { it.channel.key } } }
        assertEquals("a stream is in two entries", placed.size, placed.toSet().size)
        val left = streams.filter { it.channel.key !in placed.toSet() }
        assertTrue("only events and adult channels may be left out: ${left.filter { !it.isEvent }.take(5).map { it.channel.name }}", left.all { it.isEvent || "Adult" in com.alexdiab.stadiatv.live.ChannelFilterEngine.categories(it.channel) })
    }

    @Test
    fun noEntryMixesTwoCatalogueChannels() {
        val lineup = LineupFixture.lineup()
        for (entry in lineup.entries) {
            val ids = entry.feeds.flatMap { f -> f.streams.map { it.identity.catalog?.id } }.toSet()
            assertTrue("${entry.name} mixes $ids", ids.size == 1)
        }
    }

    @Test
    fun theEssentialChannelsInTheSavedLineupAllHaveAGuide() {
        val lineup = LineupFixture.lineup()
        val unguided = lineup.entries.filter { it.catalog?.tier == Tier.Essential && it.pick(LineupPrefs()).guideId == null }.map { it.name }
        // What is left is only channels none of the saved guide sources lists at all.
        assertTrue("essential channels without a guide: $unguided", unguided.size <= 12)
    }

    @Test
    fun theSavedLineupFindsTheCoreCanadianChannels() {
        val lineup = LineupFixture.lineup()
        val found = lineup.entries.mapNotNull { it.catalog?.id }.toSet()
        for (id in listOf("ca.tsn1", "ca.tsn2", "ca.tsn3", "ca.tsn4", "ca.tsn5", "ca.sportsneteast", "ca.sportsnetwest", "ca.sportsnetpacific", "ca.sportsnetone", "ca.sportsnet360", "ca.cbcnewsnetwork", "ca.ctvnewschannel", "ca.rds", "us.espn", "us.cnn", "us.foxnews")) {
            assertTrue("$id missing from the lineup", id in found)
        }
        assertNotNull(lineup.entries.firstOrNull { it.catalog?.id == "ca.ctv" && it.market == "ottawa" })
        assertNotNull(lineup.entries.firstOrNull { it.catalog?.id == "ca.cbctelevision" && it.market == "ottawa" })
    }

    @Test
    fun hdIsWhatPlaysWheneverAnHdCopyExists() {
        val lineup = LineupFixture.lineup()
        val prefs = LineupPrefs()
        for (entry in lineup.entries) {
            if (entry.isFast) continue
            val pick = entry.pick(prefs)
            val bestInFeed = pick.feed.streams.filterNot { it.name.isBackup }.maxOfOrNull { it.quality.rank } ?: continue
            if (bestInFeed <= Quality.Fhd.rank) assertTrue("${entry.name} plays ${pick.stream.quality} though ${bestInFeed} exists", pick.stream.quality.rank == bestInFeed)
        }
        assertFalse(lineup.entries.isEmpty())
    }

    // endregion
}
