package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StreamEpgConfirmationTest {
    private val kickoff = Dates.parseIso("2026-10-26T02:00:00Z")!!
    private val game = Match("constructed", League.find("hockey/nhl")!!, kickoff, "Rangers at Senators", "NYR @ OTT", GameState.LIVE, "",
        TeamSide("Ottawa Senators", "Senators", "OTT"), TeamSide("New York Rangers", "Rangers", "NYR"))
    private val slot = Channel("slot", "US ★ NHL GAME 05: RANGERS AT SENATORS 10PM ET", "https://streams.example/slot.ts", null, "Sports", "test", "Constructed playlist")
    private val network = slot.copy(id = "network", name = "TSN 5")
    private val listing = Programme("guide", kickoff, kickoff + 3 * 3_600_000, "NHL Hockey", description = "New York Rangers at Ottawa Senators")
    private fun source(tier: LinkTier, confidence: Double = .99, title: String? = "NHL Hockey") = RankedSource(slot, tier, confidence, "", "", "", title)

    @Test fun confidenceAloneNeverTurnsNameRightsTeamOrCoverageIntoEpgConfirmation() {
        for (tier in listOf(LinkTier.EventChannel, LinkTier.TeamChannel, LinkTier.LikelyRights, LinkTier.Coverage)) {
            assertFalse(tier.name, source(tier).isConfirmed)
        }
        assertTrue(source(LinkTier.LiveListing, .7).isConfirmed)
        assertTrue(source(LinkTier.ListingByDescription, .72).isConfirmed)
    }

    @Test fun missingBlankOrInvalidProbabilityListingEvidenceCannotBeConfirmed() {
        for (title in listOf(null, "", "  ")) assertFalse(source(LinkTier.LiveListing, title = title).isConfirmed)
        for (confidence in listOf(.69, Double.NaN, Double.POSITIVE_INFINITY, 1.01)) assertFalse(source(LinkTier.LiveListing, confidence).isConfirmed)
    }

    @Test fun timedNameWithoutAnyGuideRemainsManuallySelectableButNotAutomaticWatch(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        store.ensure(game, listOf(slot), emptyList(), { null }, 1)
        val result = store.sourcesFor(game)
        assertTrue(result.confirmed.isEmpty())
        val suggestion = result.possible.single()
        assertEquals(LinkTier.EventChannel, suggestion.tier)
        assertEquals(.85, suggestion.confidence, .001)
        assertNull(suggestion.programmeTitle)
    }

    @Test fun realDescriptionListingLeadsAHigherConfidenceTimedName(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        store.ensure(game, listOf(slot, network), listOf(listing), { if (it.key == network.key) "guide" else null }, 1)
        val result = store.sourcesFor(game)
        assertEquals(network.key, result.sources.first().channel.key)
        assertEquals(network.key, result.confirmed.single().channel.key)
        assertEquals(LinkTier.ListingByDescription, result.confirmed.single().tier)
        assertEquals(slot.key, result.possible.single().channel.key)
    }

    @Test fun multipleEvidencePathsKeepOneChannelAndPreferItsActualListing(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        store.ensure(game, listOf(slot), listOf(listing), { "guide" }, 1)
        val result = store.sourcesFor(game)
        assertEquals(1, result.sources.size)
        assertEquals(LinkTier.ListingByDescription, result.confirmed.single().tier)
        assertEquals(listing.title, result.confirmed.single().programmeTitle)
    }

    @Test fun actualGuideArrivingAfterColdStartPromotesTheSameSourceWithoutRenumbering(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        store.ensure(game, listOf(slot), emptyList(), { "guide" }, 1)
        assertTrue(store.sourcesFor(game).confirmed.isEmpty())
        store.ensure(game, listOf(slot), listOf(listing), { "guide" }, 2)
        assertEquals(slot.key, store.sourcesFor(game).confirmed.single().channel.key)
        store.ensure(game, listOf(slot), emptyList(), { "guide" }, 3)
        assertTrue(store.sourcesFor(game).confirmed.isEmpty())
    }

    @Test fun contradictoryActualProgrammeDoesNotGetOverriddenByTheName(): Unit = runBlocking {
        val store = StreamAvailabilityStore(this)
        store.ensure(game, listOf(slot), listOf(listing.copy(title = "NHL Hockey: Florida Panthers at Chicago Blackhawks", description = null)), { "guide" }, 1)
        assertTrue(store.sourcesFor(game).confirmed.isEmpty())
        assertEquals(LinkTier.EventChannel, store.sourcesFor(game).possible.single().tier)
    }
}
