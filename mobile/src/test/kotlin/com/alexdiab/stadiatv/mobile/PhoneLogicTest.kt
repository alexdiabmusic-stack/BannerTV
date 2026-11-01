package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.live.Programme
import org.junit.Assert.*
import org.junit.Test

class PhoneLogicTest {
    @Test fun `guide clips programmes at both edges and preserves their relative durations`() {
        val minute = 60_000L
        val cells = guideCells(listOf(
            Programme("tsn5", 0, 40 * minute, "Pre-game"),
            Programme("tsn5", 40 * minute, 100 * minute, "Hockey"),
            Programme("tsn5", 100 * minute, 100 * minute, "Invalid"),
            Programme("tsn5", 120 * minute, 150 * minute, "Outside"),
        ), 30 * minute, 90 * minute)
        assertEquals(listOf("Pre-game", "Hockey"), cells.map { it.programme.title })
        assertEquals(0f, cells[0].offsetMinutes, 0.001f)
        assertEquals(10f, cells[0].durationMinutes, 0.001f)
        assertEquals(10f, cells[1].offsetMinutes, 0.001f)
        assertEquals(50f, cells[1].durationMinutes, 0.001f)
    }

    @Test fun `playlist validation accepts http and https and rejects missing hosts and local schemes`() {
        assertTrue(validProviderUrl("https://example.com/playlist.m3u?username=test"))
        assertTrue(validProviderUrl(" http://example.com:8080 "))
        assertFalse(validProviderUrl("file:///data/private"))
        assertFalse(validProviderUrl("https://"))
        assertFalse(validProviderUrl("example.com"))
        assertFalse(validProviderUrl("https://a space.example"))
    }

    @Test fun `reminder is ten minutes before kickoff or immediate for an imminent game`() {
        val now = 1_000_000L
        assertEquals(now + 600_000L, reminderTime(now + 1_200_000L, now))
        assertEquals(now + 1_000L, reminderTime(now + 60_000L, now))
        assertNull(reminderTime(now, now))
        assertNull(reminderTime(now - 1, now))
    }
}
