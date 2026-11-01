package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.AppJson
import com.alexdiab.stadiatv.util.Dates
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PhoneFantasyContextsTest {
    private val now = Dates.parseIso("2026-10-24T16:00:00Z")!!
    private val person = NativeFantasyPlayer("nhl:1", "Constructed player", "LA", "L", setOf("LW", "F", "UTIL"))
    private val league = NativeFantasyEngine.draft(NativeFantasyEngine.create("Constructed", "Team", "nhl", true, roster = mapOf("BN" to 1)), person, listOf(person))
    private fun game(id: String = "one", state: GameState = GameState.PRE, at: Long = now + 3_600_000) = Match(id, League.find("hockey/nhl")!!, at, "Edmonton at Los Angeles", "EDM @ LAK", state, "", TeamSide("Los Angeles Kings", "Kings", "LAK"), TeamSide("Edmonton Oilers", "Oilers", "EDM"))
    @Test fun oneBoundedEightDayQueryIsOnlyForANonemptySupportedSelectedRoster() {
        assertNull(phoneNativeFantasyScheduleQuery(null, now))
        assertNull(phoneNativeFantasyScheduleQuery(NativeFantasyEngine.create("Empty", "Team", "nhl", true), now))
        val query = phoneNativeFantasyScheduleQuery(league, now)!!
        assertEquals("hockey/nhl", query.leaguePath); assertEquals(8, query.days); assertEquals(Dates.startOfDay(now), query.first)
    }
    @Test fun previousLeagueOrSameSizedChangedRosterCannotReuseASharedIndex() {
        val index = NativeFantasyContexts.build(league, listOf(game()), now)
        val library = NativeFantasyLibrary(selected = league.id, leagues = listOf(league))
        assertEquals(index, phoneNativeFantasyIndex(index, library, now))
        val edited = league.copy(teams = listOf(league.userTeam.copy(roster = league.userTeam.roster.map { it.copy(player = person.copy(name = "Changed")) })))
        assertNull(phoneNativeFantasyIndex(index, library.copy(leagues = listOf(edited)), now).owner)
        assertNull(phoneNativeFantasyIndex(index, NativeFantasyLibrary(), now).owner)
    }
    @Test fun homeCountsDistinctRosteredPeopleAcrossDistinctDoubleheaderFixtures() {
        val index = NativeFantasyContexts.build(league, listOf(game("one"), game("two", at = now + 4 * 3_600_000)), now)
        val summary = phoneFantasyHomeSummary(index, emptySet(), now)!!
        assertEquals(2, summary.contexts.size); assertEquals(1, summary.players); assertFalse(summary.live)
        assertEquals("1 rostered player · 2 team fixtures", phoneFantasyHomeCountLabel(summary))
    }
    @Test fun liveTakesPriorityWhileHiddenFinalAndTomorrowGamesDoNotInventATodaySummary() {
        val live = game("live", GameState.LIVE)
        val index = NativeFantasyContexts.build(league, listOf(live, game("future"), game("final", GameState.FINAL)), now)
        assertEquals(listOf("live"), phoneFantasyHomeSummary(index, emptySet(), now)!!.contexts.map { it.match.id })
        assertNull(phoneFantasyHomeSummary(NativeFantasyContexts.build(league, listOf(game("tomorrow", at = Dates.addDays(now, 1))), now), emptySet(), now))
        assertNull(phoneFantasyHomeSummary(NativeFantasyContexts.build(league, listOf(live), now), setOf(live.key), now))
    }
    @Test fun guideDisplayHonorsBothItsSettingAndSpoilerProtection() {
        assertTrue(phoneShowFantasyGuide(PhoneFantasyDisplay(), false))
        assertFalse(phoneShowFantasyGuide(PhoneFantasyDisplay(showGuide = false), false))
        assertFalse(phoneShowFantasyGuide(PhoneFantasyDisplay(), true))
    }
    @Test fun crossScreenScheduleStopsWhenNoVisibleContextNeedsIt() {
        assertFalse(phoneNeedsFantasySchedule(PhoneFantasyDisplay(false, false, false), false))
        assertFalse(phoneNeedsFantasySchedule(PhoneFantasyDisplay(false, false, true), true))
        assertTrue(phoneNeedsFantasySchedule(PhoneFantasyDisplay(false, false, true), false))
        assertTrue(phoneNeedsFantasySchedule(PhoneFantasyDisplay(true, false, false), true))
        assertTrue(phoneNeedsFantasySchedule(PhoneFantasyDisplay(false, true, false), true))
    }
    @Test fun displayPreferencesRoundtripThroughPhoneStorageAndWhitelistedBackup() {
        val display = PhoneFantasyDisplay(showHome = false, showLive = false, showGuide = false)
        val preferences = PhonePreferences(fantasyDisplay = display)
        assertEquals(display, AppJson.decodeFromString<PhonePreferences>(AppJson.encodeToString(preferences)).fantasyDisplay)
        val backup = PhoneCloudBackup.create(UserSettings(), preferences, PodcastLibrary(), NativeFantasyLibrary())
        assertEquals(display, PhoneCloudBackup.decode(AppJson.encodeToString(backup)).fantasyDisplay)
        assertEquals(PhoneFantasyDisplay(), AppJson.decodeFromString<PhonePreferences>("{}").fantasyDisplay)
    }
}
