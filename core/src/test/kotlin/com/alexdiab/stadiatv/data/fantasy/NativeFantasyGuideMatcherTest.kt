package com.alexdiab.stadiatv.data.fantasy

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test

/** Constructed playlist listings only, never inserted into a running app's guide. */
class NativeFantasyGuideMatcherTest {
    private val now = Dates.parseIso("2026-10-24T16:00:00Z")!!
    private val game = Match("constructed", League.find("hockey/nhl")!!, now + 3_600_000,
        "Toronto Maple Leafs at Ottawa Senators", "TOR @ OTT", GameState.PRE, "Upcoming",
        TeamSide("Ottawa Senators", "Senators", "OTT"), TeamSide("Toronto Maple Leafs", "Maple Leafs", "TOR"), broadcasts = listOf("TSN"))
    private val player = NativeFantasyPlayer("nhl:123", "Constructed roster player", "OTT", "L", setOf("LW", "F", "UTIL"))
    private val league = NativeFantasyEngine.draft(NativeFantasyEngine.create("Guide test", "Team", "nhl", true, roster = mapOf("BN" to 1)), player, listOf(player))
    private val index = NativeFantasyContexts.build(league, listOf(game), now)
    private val listing = Programme("own.tsn1", game.startMillis - 15 * 60_000, game.startMillis + 3 * 3_600_000, "Toronto Maple Leafs at Ottawa Senators")
    @Test fun exactPlaylistFixtureTitleProducesARosterBadge() {
        assertEquals(player.id, NativeFantasyGuideMatcher.players("own.tsn1", listing, index).single().entry.player.id)
    }
    @Test fun genericTitleNeedsBothTeamsInItsOwnDescription() {
        assertEquals(1, NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "NHL Hockey", description = "Toronto Maple Leafs at Ottawa Senators."), index).size)
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "NHL Hockey", description = "Ottawa Senators news and interviews."), index).isEmpty())
    }
    @Test fun sameNetworkAndOverlappingTimeAreNotEnoughForAnotherProgrammeOrGame() {
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "SportsCentre"), index).isEmpty())
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "Montreal Canadiens at Ottawa Senators"), index).isEmpty())
    }
    @Test fun foreignMissingAndReboundGuideIdsCannotBorrowAnotherChannelsBadge() {
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn5", listing, index).isEmpty())
        assertTrue(NativeFantasyGuideMatcher.players(null, listing, index).isEmpty())
        assertEquals(1, NativeFantasyGuideMatcher.players(" OWN.TSN1 ", listing, index).size)
    }
    @Test fun replaysStudioCoverageAndDifferentDaysDoNotBecomeConfirmedFixtureBadges() {
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "Replay: Toronto Maple Leafs at Ottawa Senators"), index).isEmpty())
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "Toronto Maple Leafs at Ottawa Senators Postgame"), index).isEmpty())
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(startMillis = listing.startMillis + 24 * 3_600_000, endMillis = listing.endMillis + 24 * 3_600_000), index).isEmpty())
    }
    @Test fun changedProgrammeContentSameCountAndTimeDoesNotRetainTheOldBadge() {
        assertEquals(1, NativeFantasyGuideMatcher.players("own.tsn1", listing, index).size)
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(title = "News"), index).isEmpty())
        val rescheduled = NativeFantasyContexts.build(league, listOf(game.copy(startMillis = game.startMillis + 24 * 3_600_000)), now)
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing, rescheduled).isEmpty())
    }
    @Test fun invalidProgrammeIntervalsAndAbsentRosterOwnershipHaveNoBadge() {
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(endMillis = listing.startMillis), index).isEmpty())
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing.copy(endMillis = listing.startMillis + 7 * 3_600_000), index).isEmpty())
        assertTrue(NativeFantasyGuideMatcher.players("own.tsn1", listing, NativeFantasyEventIndex()).isEmpty())
    }
}
