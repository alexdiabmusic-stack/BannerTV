package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhoneMatchDataStatusTest {
    private val game = Match("test", League.find("soccer/esp.1")!!, 0, "Home vs Away", "HOM–AWY", GameState.LIVE,
        "Second half", TeamSide("Home", "Home", "HOM"), TeamSide("Away", "Away", "AWY"), nativeReference = NativeGameReference("LaLiga", "test"))
    private fun status(data: GameCentre = GameCentre.EMPTY, selection: String = "Overview", offline: Boolean = false,
        delayed: Boolean = false, snapshot: PhoneGameSnapshot = PhoneGameSnapshot(data)) =
        phoneMatchDataStatus(game, snapshot, selection, offline, delayed)

    @Test fun `empty initial state does not invent checked or healthy data`() {
        val value = status()
        assertTrue(value.provenance.isEmpty())
        assertTrue(value.notices.isEmpty())
        assertNull(value.freshness)
    }

    @Test fun `score and mapping uncertainty remain urgent when viewing other resources`() {
        val value = status(GameCentre(soccer = SoccerDetail(errors = linkedMapOf(
            "lineups" to "Lineups unavailable", "score" to "Score could not refresh", "mapping" to "Fixture identity unavailable"))), "Lineups")
        assertEquals("Score could not refresh", value.urgentNotice?.message)
        assertEquals(2, value.notices.count { it.urgent })
        assertTrue(value.notices.any { it.message == "Lineups unavailable" && !it.urgent })
    }

    @Test fun `optional resource failure is disclosed only with the relevant view`() {
        val data = GameCentre(soccer = SoccerDetail(errors = mapOf("pressure" to "Pressure unavailable", "commentary" to "Commentary unavailable")))
        assertEquals(listOf("Pressure unavailable"), status(data).notices.map { it.message })
        assertNull(status(data).urgentNotice)
        assertEquals(listOf("Commentary unavailable"), status(data, "Commentary").notices.map { it.message })
        assertTrue(status(data, "Lineups").notices.isEmpty())
    }

    @Test fun `offline score retains its original successful timestamp`() {
        val value = status(GameCentre(providerStamp = ProviderStamp("NBA", "test", 9000),
            basketball = BasketballDetail(null, null, null, null, emptyList(), surfaceUpdatedAt = mapOf("score" to 1000, "box" to 8000))), offline = true)
        assertEquals(PhoneMatchDataStamp("Offline NBA data", 1000), value.freshness)
        assertEquals(9000L, value.provenance.single().time)
    }

    @Test fun `saved baseball notice uses oldest retained resource time`() {
        val value = status(GameCentre(providerStamp = ProviderStamp("MLB", "test", 9000),
            baseball = BaseballDetail(null, "", null, null, null, emptyList(), null, null, emptyList(),
                savedSnapshot = true, surfaceUpdatedAt = mapOf("overview" to 5000, "plays" to 1000))))
        assertEquals(PhoneMatchDataStamp("Saved MLB data", 1000), value.freshness)
    }

    @Test fun `baseball supplemental retries retain their separate targets`() {
        val snapshot = PhoneGameSnapshot(baseballExtraErrors = mapOf("probability" to "Probability unavailable", "highlights" to "Highlights unavailable"))
        assertEquals("baseball:highlights", status(snapshot = snapshot).notices.single().retry)
        assertEquals("baseball:probability", status(selection = "Team stats", snapshot = snapshot).notices.single().retry)
        assertTrue(status(selection = "Box score", snapshot = snapshot).notices.isEmpty())
    }

    @Test fun `known unavailable basketball network does not advertise retry`() {
        val blocked = BasketballDetail(null, null, null, null, emptyList(), errors = mapOf("score" to "NBA isn't available on this network"))
        assertNull(status(GameCentre(basketball = blocked)).urgentNotice!!.retry)
        val mixed = blocked.copy(errors = blocked.errors + ("plays" to "Temporary plays timeout"))
        assertEquals("game", status(GameCentre(basketball = mixed)).urgentNotice!!.retry)
    }

    @Test fun `unpublished match details explain scheduled kickoff without a retry loop`() {
        val notice = status(GameCentre(soccer = SoccerDetail(overviewNotPublished = true))).notices.single()
        assertFalse(notice.urgent)
        assertNull(notice.retry)
        assertTrue(notice.message.contains("La Liga schedule"))
    }

    @Test fun `hockey landing failure stays visible outside overview`() {
        val value = status(GameCentre(hockey = HockeyDetail(null, null, null, null, null, errors = mapOf("landing" to "Overview could not refresh"))), "Box score")
        assertEquals("Overview could not refresh", value.urgentNotice?.message)
    }

    @Test fun `nfl delay exposes summary time and generic failures remain actionable`() {
        val data = GameCentre(football = FootballDetail(null, null, null, null, summaryUpdatedAt = 1234))
        val value = status(data, delayed = true, snapshot = PhoneGameSnapshot(data, error = "Match update failed"))
        assertEquals(PhoneMatchDataStamp("Live NFL data delayed", 1234), value.freshness)
        assertEquals("Match update failed", value.urgentNotice?.message)
        assertEquals("game", value.urgentNotice?.retry)
    }
}
