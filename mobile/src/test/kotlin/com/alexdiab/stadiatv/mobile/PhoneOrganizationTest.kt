package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FeedSections
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Channel
import org.junit.Assert.*
import org.junit.Test

class PhoneOrganizationTest {
    @Test fun canonicalGroupMembershipSurvivesStreamChoiceAndKeepsManualOrder() {
        val a = Channel("hd", "One HD", "https://example/1", null, "Sports", "p", "Provider")
        val b = a.copy(id = "two", name = "Two")
        val group = PhoneChannelGroup("g", "Game night", listOf("canonical-two", "canonical-one"))
        val channels = groupedChannels(listOf(a, b), group) { if (it.id == "two") "canonical-two" else "canonical-one" }
        assertEquals(listOf(b, a), channels)
        val changed = a.copy(id = "sd", streamUrl = "https://example/backup")
        assertEquals(listOf(b, changed), groupedChannels(listOf(changed, b), group) { if (it.id == "two") "canonical-two" else "canonical-one" })
    }
    @Test fun hiddenFeedDoesNotMutateSourceOrDiscardOtherGames() {
        val league = League.find("basketball/nba")!!; val side = TeamSide("Team", "Team", "T")
        val one = Match("1", league, 1, "Game one", "One", GameState.LIVE, "Live", side, side)
        val two = one.copy(id = "2", name = "Game two")
        val feed = FeedSections(all = listOf(one, two), liveNow = listOf(one, two), liveForYou = listOf(one, two), prime = one)
        val visible = feed.visibleGames(setOf(one.key))
        assertEquals(listOf(two), visible.all); assertEquals(two, visible.prime)
        assertEquals(listOf(one, two), feed.all); assertEquals(feed, feed.visibleGames(emptySet()))
    }
}
