package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.SoccerComment
import com.alexdiab.stadiatv.domain.SoccerCommentaryWindow
import org.junit.Assert.*
import org.junit.Test

/** Constructed feed identities; no fabricated live commentary is used by runtime. */
class SoccerCommentaryWindowTest {
    private fun comment(id: String, text: String = id) = SoccerComment(id, "", text, "raw-code", null)
    @Test fun initialAndLatestViewsShowAllIncomingInProviderOrder() {
        val initial = SoccerCommentaryWindow().update(listOf(comment("b"), comment("a")), false)
        assertEquals(listOf("b", "a"), initial.displayed.map { it.id })
        val newest = initial.update(listOf(comment("c"), comment("b"), comment("a")), true)
        assertEquals(listOf("c", "b", "a"), newest.displayed.map { it.id })
        assertTrue(newest.deferredIds.isEmpty())
    }
    @Test fun leadingUpdatesWaitWhileReadingButCorrectionsAndOlderPagesRemainAvailable() {
        val initial = SoccerCommentaryWindow().update(listOf(comment("b"), comment("a")), true)
        val next = initial.update(listOf(comment("d"), comment("c"), comment("b", "corrected"), comment("a"), comment("older")), false)
        assertEquals(setOf("d", "c"), next.deferredIds)
        assertEquals(listOf("b", "a", "older"), next.displayed.map { it.id })
        assertEquals("corrected", next.displayed.first().text)
        val more = next.update(listOf(comment("e"), comment("d"), comment("c"), comment("b", "corrected again"), comment("a"), comment("older")), false)
        assertEquals(setOf("e", "d", "c"), more.deferredIds)
        assertEquals("corrected again", more.displayed.first().text)
        assertEquals(6, more.update(listOf(comment("e"), comment("d"), comment("c"), comment("b"), comment("a"), comment("older")), true).displayed.size)
    }
    @Test fun withdrawnMessagesAndPendingIdsAreRemovedRatherThanResurrected() {
        val initial = SoccerCommentaryWindow().update(listOf(comment("b"), comment("a")), true)
        val pending = initial.update(listOf(comment("new"), comment("b"), comment("a")), false)
        val corrected = pending.update(listOf(comment("a")), false)
        assertEquals(listOf("a"), corrected.displayed.map { it.id })
        assertTrue(corrected.deferredIds.isEmpty())
        assertTrue(corrected.update(emptyList(), false).displayed.isEmpty())
    }
    @Test fun completeReplacementWaitsForExplicitReturnAndCannotLeakAnotherGamesWindow() {
        val initial = SoccerCommentaryWindow().update(listOf(comment("old")), true)
        val pending = initial.update(listOf(comment("new")), false)
        assertTrue(pending.displayed.isEmpty()); assertEquals(setOf("new"), pending.deferredIds)
        assertEquals(listOf("new"), pending.update(listOf(comment("new")), true).displayed.map { it.id })
        assertEquals(listOf("other"), SoccerCommentaryWindow().update(listOf(comment("other")), false).displayed.map { it.id })
    }
}
