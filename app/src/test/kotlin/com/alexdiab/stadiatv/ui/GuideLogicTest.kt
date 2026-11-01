package com.alexdiab.stadiatv.ui

import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.lineup.CatalogMatcher
import com.alexdiab.stadiatv.lineup.GuideDirectory
import com.alexdiab.stadiatv.lineup.GuideListing
import com.alexdiab.stadiatv.lineup.GuideRef
import com.alexdiab.stadiatv.lineup.Lineup
import com.alexdiab.stadiatv.lineup.LineupBuilder
import com.alexdiab.stadiatv.lineup.LineupFixture
import com.alexdiab.stadiatv.lineup.LineupPrefs
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.ui.livetv.GuideFilter
import com.alexdiab.stadiatv.ui.livetv.GuideLogic
import com.alexdiab.stadiatv.ui.livetv.GuideOptions
import com.alexdiab.stadiatv.ui.livetv.GuideTimelineLogic
import com.alexdiab.stadiatv.ui.livetv.GuideUi
import com.alexdiab.stadiatv.ui.livetv.SportsFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideLogicTest {
    private val builder = LineupBuilder(CatalogMatcher())
    private var next = 0
    private val now = 1_800_000_000_000L

    private fun ch(name: String, group: String = "", epg: String? = null) = LineupFixture.channel(next++, name, group, epg)

    private val channels = listOf(
        ch("CA ★ CTV OTTAWA HD", "CA ❖ GENERAL", "CTVOttawa-CJOH.ca"),
        ch("CA ★ CTV TORONTO HD", "CA ❖ GENERAL", "CTVToronto-CFTO.ca"),
        ch("CA ★ CBC OTTAWA HD", "CA ❖ GENERAL", "CBCOttawa-CBOT.ca"),
        ch("CA ★ TSN 1 HD", "CA ❖ SPORTS", "TSN1.ca"),
        ch("CA ★ SPORTSNET EAST HD", "CA ❖ SPORTS", "SportsnetEast.ca"),
        ch("CAF ★ RDS HD", "CA ❖ QUEBEC", "RDS.ca"),
        ch("CAF ★ ICI RDI HD", "CA ❖ QUEBEC", "ICI-RDI.ca"),
        ch("CA ★ CBC NEWS HD", "CA ❖ GENERAL", "CBCNewsNetwork.ca"),
        ch("US ★ ESPN HD", "US ❖ SPORTS", "ESPN.us"),
        ch("US ★ CNN HD", "US ❖ NEWS", "CNN.us"),
        ch("US ★ HBO HD [EAST]", "US ❖ MOVIES", "HBO.us"),
        ch("US ★ NICKELODEON HD", "US ❖ KIDS", "Nickelodeon.us"),
        ch("US ★ ABC 7 CHICAGO IL HD", "US ❖ ABC", "ABCWLS.us"),
        ch("US ★ ABC 7 NEW YORK HD", "US ❖ ABC", "ABCWABC.us"),
        ch("CA ★ COMEDY CENTRAL", "CA ❖ PLUTO TV"),
        ch("US ★ TELEMUNDO HD", "US ❖ TELEMUNDO", "Telemundo.us"),
    )

    private val guide = listOf(
        "CTVOttawa-CJOH.ca", "CTVToronto-CFTO.ca", "CBCOttawa-CBOT.ca", "TSN1.ca", "SportsnetEast.ca", "RDS.ca", "ICI-RDI.ca",
        "CBCNewsNetwork.ca", "ESPN.us", "CNN.us", "HBO.us", "Nickelodeon.us", "ABCWLS.us", "ABCWABC.us",
    ).map { GuideListing(it, listOf(it), 100) }

    private val lineup: Lineup = builder.build(builder.analyse(channels), GuideDirectory.build(builder.guideMatcher, guide))

    private fun programme(id: String, title: String, category: String? = null, description: String? = null) =
        Programme(id, now - 600_000, now + 600_000, title, null, description, category)

    private val schedule = mapOf(
        "tsn1.ca" to programme("tsn1.ca", "NHL Hockey", "Sports", "Bruins at Maple Leafs"),
        "sportsneteast.ca" to programme("sportsneteast.ca", "MLB Baseball", "Sports", "Blue Jays at Yankees"),
        "ctvottawa-cjoh.ca" to programme("ctvottawa-cjoh.ca", "CTV News at Six", "News"),
        "abcwls.us" to programme("abcwls.us", "NFL Football", "Sports", "Bears at Packers"),
    )

    private fun ui(
        filter: GuideFilter = GuideFilter.All,
        settings: UserSettings = UserSettings(homeCountry = "CA", homeMarket = "ottawa"),
        options: GuideOptions = GuideOptions(),
        query: String = "",
    ): GuideUi = GuideLogic.build(
        lineup, settings, LineupPrefs(homeCountry = "CA"), filter, options, query,
        nowAndNext = { ref: GuideRef, _ -> schedule[ref.id.lowercase()] to null }, now = now, timeZone = "America/Toronto",
    )

    private fun names(ui: GuideUi) = ui.rows.map { it.entry.name }

    @Test
    fun allShowsTheViewersAreaAndNationalChannelsButNotOtherCitiesOrFreeStreaming() {
        val all = names(ui())
        assertTrue("CTV Ottawa" in all && "CBC Ottawa" in all)
        assertFalse("CTV Toronto" in all)
        assertFalse("ABC Chicago" in all)
        assertTrue("TSN 1" in all && "ESPN" in all)
        assertFalse(all.any { it.contains("Comedy Central") })
    }

    @Test
    fun theHomeAreaIsTheFirstThingListed() {
        val all = names(ui())
        assertEquals(setOf("CTV Ottawa", "CBC Ottawa"), all.take(2).toSet())
        assertTrue(all.indexOf("CTV Ottawa") < all.indexOf("ESPN"))
    }

    @Test
    fun anotherAreaCanBeChosenOrEveryAreaShown() {
        assertTrue("ABC Chicago" in names(ui(settings = UserSettings(homeCountry = "US", homeMarket = "chicago"))))
        val every = names(ui(options = GuideOptions(allAreas = true)))
        assertTrue("CTV Toronto" in every && "ABC New York" in every)
    }

    @Test
    fun searchFindsAnyChannelWhateverTheArea() {
        val found = names(ui(query = "toronto"))
        assertEquals(listOf("CTV Toronto"), found)
        assertTrue(names(ui(query = "comedy")).any { it.contains("Comedy Central") })
    }

    @Test
    fun countryAndLanguageChips() {
        val canada = ui(GuideFilter.Canada)
        assertTrue(canada.rows.all { it.entry.country == "CA" })
        assertTrue(ui(GuideFilter.Usa).rows.all { it.entry.country == "US" })
        val french = names(ui(GuideFilter.French))
        assertTrue("RDS" in french && "ICI RDI" in french)
        assertFalse("TSN 1" in french)
    }

    @Test
    fun newsMoviesAndKidsChips() {
        assertTrue(names(ui(GuideFilter.News)).containsAll(listOf("CBC News Network", "CNN")))
        assertTrue(names(ui(GuideFilter.Movies)).any { it.startsWith("HBO") })
        assertEquals(listOf("Nickelodeon"), names(ui(GuideFilter.Kids)))
        // Kids channels can be kept out of All without losing the Kids chip.
        val hideKids = UserSettings(homeCountry = "CA", homeMarket = "ottawa", hideKidsChannels = true)
        assertFalse("Nickelodeon" in names(ui(settings = hideKids)))
        assertEquals(listOf("Nickelodeon"), names(ui(GuideFilter.Kids, hideKids)))
    }

    @Test
    fun sportsListsSportsChannelsAndOnlyAGameOnABroadcaster() {
        val sports = names(ui(GuideFilter.Sports, options = GuideOptions(allAreas = true)))
        assertTrue(sports.containsAll(listOf("TSN 1", "Sportsnet East", "ESPN")))
        // ABC Chicago is showing football; ABC New York is not showing anything sporty.
        assertTrue("ABC Chicago" in sports)
        assertFalse("ABC New York" in sports)
        assertFalse("CTV Ottawa" in sports)
    }

    @Test
    fun sportsCanBeNarrowedToALeagueOrTheViewersTeams() {
        val hockey = names(ui(GuideFilter.Sports, options = GuideOptions(sports = SportsFilter.Hockey)))
        assertEquals(listOf("TSN 1"), hockey)
        assertEquals(listOf("Sportsnet East"), names(ui(GuideFilter.Sports, options = GuideOptions(sports = SportsFilter.Baseball))))
        val following = UserSettings(
            homeCountry = "CA", homeMarket = "ottawa",
            favoriteTeams = listOf(com.alexdiab.stadiatv.data.FavoriteTeam("mlb", "10", "Blue Jays")),
        )
        assertEquals(listOf("Sportsnet East"), names(ui(GuideFilter.Sports, following, GuideOptions(sports = SportsFilter.MyTeams))))
    }

    private fun sportsNames(filter: SportsFilter, now: Programme?, next: Programme? = null) = GuideLogic.build(
        lineup, UserSettings(homeCountry = "CA", homeMarket = "ottawa"), LineupPrefs(homeCountry = "CA"), GuideFilter.Sports,
        GuideOptions(sports = filter, allAreas = true), "",
        nowAndNext = { ref: GuideRef, _ -> if (ref.id.equals("espn.us", true)) now to next else null to null }, now = now(), timeZone = "America/Toronto",
    ).rows.map { it.entry.name }

    private fun now() = this.now

    @Test
    fun footballDoesNotListABasketballGameBecauseFootballIsNext() {
        val basketball = programme("espn.us", "NBA Basketball", "Sports", "Lakers at Celtics")
        val football = Programme("espn.us", now + 600_000, now + 7_200_000, "NFL Football", null, "Bears at Packers", "Sports")
        // The channel is showing the NBA now and the NFL afterwards: the Football chip must not claim it.
        assertFalse("ESPN" in sportsNames(SportsFilter.Football, basketball, football))
        assertTrue("ESPN" in sportsNames(SportsFilter.Basketball, basketball, football))
        // With nothing on, the next listing is what the channel is about to show.
        assertTrue("ESPN" in sportsNames(SportsFilter.Football, null, football))
    }

    @Test
    fun aListingThatNamesItsLeagueIsNotMovedByWhatItsDescriptionMentions() {
        val game = programme("espn.us", "NBA Basketball", "Sports", "Lakers at Celtics. Stay tuned for NFL Sunday.")
        assertFalse("ESPN" in sportsNames(SportsFilter.Football, game))
        assertTrue("ESPN" in sportsNames(SportsFilter.Basketball, game))
        // A listing that names no league is still judged by its description.
        val untitled = programme("espn.us", "Sportsnet Live", "Sports", "Blue Jays at Yankees")
        assertTrue("ESPN" in sportsNames(SportsFilter.Baseball, untitled))
    }

    @Test
    fun favoritesHiddenAndOrderAreTheViewersOwn() {
        val tsn = lineup.entries.first { it.name == "TSN 1" }
        val espn = lineup.entries.first { it.name == "ESPN" }
        val settings = UserSettings(homeCountry = "CA", homeMarket = "ottawa", favoriteEntryKeys = setOf(espn.key), hiddenEntryKeys = setOf(tsn.key))
        assertEquals(listOf("ESPN"), names(ui(GuideFilter.Favorites, settings)))
        assertFalse("TSN 1" in names(ui(settings = settings)))
        val shown = ui(settings = settings, options = GuideOptions(showHidden = true))
        assertTrue(shown.rows.first { it.entry.name == "TSN 1" }.isHidden)
        // Favourites float to the top, and a hand-made order beats the default one.
        assertEquals("ESPN", ui(settings = settings).rows.first().entry.name)
        val ordered = settings.copy(entryOrder = listOf(lineup.entries.first { it.name == "CNN" }.key))
        assertEquals("CNN", ui(settings = ordered).rows.first().entry.name)
    }

    @Test
    fun theRowCarriesWhatIsOnAndItsKind() {
        val row = ui().rows.first { it.entry.name == "CTV Ottawa" }
        assertEquals("CTV News at Six", row.now?.title)
        assertTrue(row.label.startsWith("News"))
        assertTrue(row.label.contains("Ottawa"))
    }

    @Test
    fun spanishChannelsAreASeparateCatalogueUntilAskedFor() {
        assertFalse(names(ui(options = GuideOptions(allAreas = true))).any { it.startsWith("Telemundo") })
        val spanish = UserSettings(homeCountry = "CA", homeMarket = "ottawa", showSpanishChannels = true)
        assertTrue(names(ui(settings = spanish, options = GuideOptions(allAreas = true))).any { it.startsWith("Telemundo") })
        // Searching finds them either way.
        assertTrue(names(ui(query = "telemundo")).any { it.startsWith("Telemundo") })
    }

    @Test
    fun theTimeZoneGivesAFirstGuessAtTheArea() {
        assertEquals("toronto", GuideLogic.marketForTimeZone("America/Toronto"))
        assertEquals("los angeles", GuideLogic.marketForTimeZone("America/Los_Angeles"))
        assertEquals("", GuideLogic.marketForTimeZone("Europe/Paris"))
    }

    @Test
    fun timelineIsContinuousAndClipsOverlappingListingsToTheVisibleWindow() {
        val minute = 60_000L
        val programmes = listOf(
            Programme("guide", 10 * minute, 30 * minute, "First"),
            Programme("guide", 25 * minute, 50 * minute, "Second"),
        )
        val segments = GuideTimelineLogic.segments(programmes, 0, 60 * minute)

        assertEquals(listOf(0L, 10 * minute, 30 * minute, 50 * minute), segments.map { it.startMillis })
        assertEquals(listOf(10 * minute, 30 * minute, 50 * minute, 60 * minute), segments.map { it.endMillis })
        assertEquals(listOf(null, "First", "Second", null), segments.map { it.programme?.title })
    }
}
