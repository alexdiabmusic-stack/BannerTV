package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import org.junit.Assert.*
import org.junit.Test

class PhoneStatsPresentationTest {
    @Test fun teamSportsExposeRosterAndInjuriesButSoccerHasNoInjurySection() {
        val hockey = League.find("hockey/nhl")!!; val soccer = League.find("soccer/eng.1")!!
        assertEquals(listOf("Overview", "Standings", "Leaders", "Teams", "Injuries"), PhoneStatsSection.available(hockey).map { it.label })
        assertEquals(listOf("Overview", "Standings", "Leaders", "Teams"), PhoneStatsSection.available(soccer).map { it.label })
        assertEquals("Table and form", PhoneStatsSection.Standings.detail(soccer))
    }
    @Test fun IndividualSportsHaveFieldsRatherThanFictionalTeamRosters() {
        for (path in listOf("racing/f1", "golf/pga")) assertEquals(listOf("Overview", "Field", "Leaders"), PhoneStatsSection.available(League.find(path)!!).map { it.label })
        assertEquals("Drivers and results", PhoneStatsSection.Leaders.detail(League.find("racing/f1")!!))
        assertEquals("Tournament field", PhoneStatsSection.Field.detail(League.find("golf/pga")!!))
    }
    @Test fun FollowChangesChooseAvailableLeagueAndEmptyFollowsExposeCatalog() {
        val nhl = League.find("hockey/nhl")!!; val nfl = League.find("football/nfl")!!
        assertEquals(nhl, PhoneStatsSection.selectedLeague(nhl.path, listOf(nfl, nhl)))
        assertEquals(nfl, PhoneStatsSection.selectedLeague(nhl.path, listOf(nfl)))
        assertEquals(nhl, PhoneStatsSection.selectedLeague(nhl.path, emptyList()))
        assertEquals(League.ALL.first(), PhoneStatsSection.selectedLeague("untrusted/path", emptyList()))
    }
    @Test fun explicitUnfollowedLeagueIsAvailableForTheVisitWithoutChangingFollows() {
        val followed = listOf(League.find("football/nfl")!!, League.find("hockey/nhl")!!)
        val mlb = League.find("baseball/mlb")!!
        val available = PhoneStatsSection.leaguesForVisit(followed, mlb.path)
        assertEquals(followed + mlb, available); assertEquals(mlb, PhoneStatsSection.selectedLeague(mlb.path, available))
        assertEquals(2, followed.size); assertEquals(followed, PhoneStatsSection.leaguesForVisit(followed, "untrusted/path"))
        assertEquals(League.ALL, PhoneStatsSection.leaguesForVisit(emptyList(), mlb.path))
    }
}
