package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import org.junit.Assert.*
import org.junit.Test

class PhoneLiveDeskPresentationTest {
    private val league = League.ALL.first { it.group == SportGroup.Hockey }
    private val now = 1_792_995_600_000L
    private fun match(id: String, state: GameState = GameState.LIVE, start: Long = now) = Match(
        id, league, start, "Away at Home", "AWY @ HOM", state, state.label,
        TeamSide("Home", "Home", "HOM"), TeamSide("Away", "Away", "AWY"),
    )
    private fun featured(games: List<Match>, previous: String? = null) = phoneDeskFeaturedMatch(
        games, now, previous, { it.id.startsWith("favorite") }, { it.id.startsWith("followed") || it.id.startsWith("favorite") },
    )

    @Test fun `followed live team wins over a promoted or earlier generic game`() {
        val favorite = match("favorite-senators")
        assertEquals(favorite, featured(listOf(match("world-cup-final", start = now - 1000), favorite)))
    }
    @Test fun `any live action takes precedence over a scheduled favorite`() {
        val live = match("other")
        assertEquals(live, featured(listOf(match("favorite", GameState.PRE, now + 1000), live)))
    }
    @Test fun `refresh and provider order changes retain the same fixture with fresh score data`() {
        val first = match("favorite-a")
        val second = match("favorite-b")
        val refreshed = second.copy(home = second.home.copy(score = "3"))
        assertEquals(refreshed, featured(listOf(first, refreshed), second.key))
    }
    @Test fun `new live favorite preempts a lower tier but not another same-tier game`() {
        val other = match("followed-league")
        val favorite = match("favorite-a")
        assertEquals(favorite, featured(listOf(other, favorite), other.key))
        assertEquals(favorite, featured(listOf(match("favorite-b", start = now - 1000), favorite), favorite.key))
    }
    @Test fun `completed removed or overdue fixtures do not stay featured`() {
        val done = match("favorite-a", GameState.FINAL)
        val next = match("favorite-next", GameState.PRE, now + 10_000)
        assertEquals(next, featured(listOf(done, next), done.key))
        assertNull(featured(listOf(done, match("late", GameState.PRE, now - 1))))
        assertNull(featured(emptyList(), next.key))
    }
    @Test fun `progressive schedule loading replaces tomorrow with a nearer upcoming favorite`() {
        val later = match("favorite-later", GameState.PRE, now + 86_400_000)
        val sooner = match("favorite-sooner", GameState.PRE, now + 1000)
        assertEquals(sooner, featured(listOf(later, sooner), later.key))
    }
    @Test fun `live list excludes the hero and orders favorites before other games`() {
        val hero = match("favorite-a")
        val favorite = match("favorite-b")
        val other = match("other", start = now - 1000)
        assertEquals(listOf(favorite, other), phoneDeskLive(listOf(other, hero, favorite), hero.key) { it.id.startsWith("favorite") })
    }
    @Test fun `sport filter includes unfollowed sports and deduplicates stable game keys`() {
        val hockey = match("hockey")
        val soccer = match("soccer").copy(league = League.ALL.first { it.group == SportGroup.Soccer })
        assertEquals(listOf(hockey), phoneDeskScope(listOf(hockey, soccer, hockey), "Hockey") { false })
        assertEquals(emptyList<Match>(), phoneDeskScope(listOf(hockey), "For you") { false })
        assertEquals(listOf(hockey, soccer), phoneDeskScope(listOf(hockey, soccer), "All sports") { false })
    }
    @Test fun `postponed cancelled delayed and unknown start states never claim a countdown`() {
        listOf("Postponed", "Cancelled", "Delayed", "Suspended", "Time TBD", "Time to be announced").forEach { phase ->
            val label = phoneDeskUpcomingStatus(match("phase", GameState.PRE, now + 1000).copy(statusDetail = phase), now)
            assertTrue(label.contains(phase))
            assertFalse(label.contains("Starts"))
            assertFalse(label.contains("Starting"))
        }
    }
    @Test fun `schedule keeps finals live and future games in start time order and excludes next day`() {
        val day = Dates.startOfDay(now)
        val final = match("final", GameState.FINAL, day + 1000)
        val live = match("live", GameState.LIVE, day + 2000)
        val future = match("future", GameState.PRE, day + 3000)
        val tomorrow = match("tomorrow", GameState.PRE, Dates.addDays(day, 1))
        assertEquals(listOf(final, live, future), phoneDeskSchedule(listOf(tomorrow, future, final, live), "Today", now))
        assertEquals(listOf(tomorrow), phoneDeskSchedule(listOf(final, tomorrow), "Tomorrow", now))
    }
}
