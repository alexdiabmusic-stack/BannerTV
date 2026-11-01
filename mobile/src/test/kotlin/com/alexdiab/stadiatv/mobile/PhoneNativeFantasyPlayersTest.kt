package com.alexdiab.stadiatv.mobile

import org.junit.Assert.*
import org.junit.Test
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyPlayer

class PhoneNativeFantasyPlayersTest {
    private fun player(id: String, name: String, team: String, position: String) = NativeFantasyPlayer(id, name, team, position, setOf(position, "UTIL"))
    @Test fun draftFiltersCombineSearchPositionTeamAndRosteredIdentity() {
        val rows = listOf(player("nhl:1", "Anaheim Wing", "ANA", "L"), player("nhl:2", "Other Wing", "ANA", "L"), player("nhl:3", "Anaheim Centre", "ANA", "C"), player("nhl:4", "Philadelphia Wing", "PHI", "L"))
        assertEquals(listOf(rows[0]), phoneFantasyAvailablePlayers(rows, setOf("nhl:2"), "  wing ", "L", "ANA"))
        assertEquals(listOf(rows[3]), phoneFantasyAvailablePlayers(rows, emptySet(), "phi", null, null))
        assertEquals(rows, phoneFantasyAvailablePlayers(rows, emptySet(), "   ", null, null))
    }
    @Test fun equalNamesAcrossProviderNamespacesDoNotHideOrTransplantTheWrongPlayer() {
        val native = player("nhl:1", "Same name", "ANA", "L")
        val legacy = native.copy(id = "espn-nhl-1")
        assertEquals(listOf(native), phoneFantasyAvailablePlayers(listOf(native, legacy), setOf(legacy.id), "", null, null))
        assertTrue(phoneFantasyAvailablePlayers(listOf(native), emptySet(), "", "C", null).isEmpty())
    }
    @Test fun emptyPoolHasNoPageOrArtificialPlayerRow() {
        val page = phoneFantasyPlayerPage(emptyList<String>(), 8)
        assertTrue(page.rows.isEmpty()); assertEquals(0, page.pages); assertEquals(0, page.first); assertEquals(0, page.last)
    }
    @Test fun onlyThirtyRowsAreComposedAndLastPageHasItsActualRange() {
        val players = (1..64).toList()
        assertEquals((1..30).toList(), phoneFantasyPlayerPage(players, 0).rows)
        assertEquals((31..60).toList(), phoneFantasyPlayerPage(players, 1).rows)
        val last = phoneFantasyPlayerPage(players, 2)
        assertEquals(listOf(61, 62, 63, 64), last.rows); assertEquals(61, last.first); assertEquals(64, last.last); assertEquals(3, last.pages)
    }
    @Test fun filteringOrDraftingShrinksPageWithoutBlankRowsOrOutOfBounds() {
        val page = phoneFantasyPlayerPage((1..29).toList(), 20)
        assertEquals(0, page.index); assertEquals(29, page.rows.size); assertEquals(1, page.pages)
    }
    @Test fun restoredInvalidPageCannotOverflowItsRange() {
        assertEquals(0, phoneFantasyPlayerPage((1..60).toList(), Int.MIN_VALUE).index)
        assertEquals(1, phoneFantasyPlayerPage((1..60).toList(), Int.MAX_VALUE).index)
        assertEquals(2, phoneFantasyPlayerPage((1..60).toList(), 1).pages)
    }
}
