package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class NativeFotMobDetailsTest {
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun game() = NativeLaLigaMapping.match(fixture("official-matches-finished")["matches"].array.first(), 2026, "verified-2026")!!
    private fun mapping() = NativeLaLigaReconciliation.resolve(game(), listOf(fixture("fotmob-day-matches")))!!
    private fun raw() = fixture("fotmob-matchdetails-finished")
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    private fun events(rows: List<JsonElement>) = replace(raw(), "content", replace(raw()["content"], "matchFacts", replace(raw()["content"]["matchFacts"], "events", buildJsonObject { put("events", JsonArray(rows)) })))

    @Test fun recordedTimelinePreservesAddedTimeGoalsAssistsAndSeparateOnOffPeople() {
        val plays = NativeFotMobDetails.events(raw(), game(), mapping())
        assertEquals(3, plays.count { it.isScoring }); assertTrue(plays.any { it.clock == "90+4′" && it.isScoring })
        val assisted = plays.single { it.soccerEvent!!.kind == SoccerEventKind.Goal && it.soccerEvent!!.primary?.id == "fotmob:860314" }
        assertEquals("fotmob:518525", assisted.soccerEvent!!.secondary!!.id); assertEquals("laliga:21", assisted.teamId)
        val swap = plays.first { it.soccerEvent!!.kind == SoccerEventKind.Substitution }
        assertNotEquals(swap.soccerEvent!!.primary!!.id, swap.soccerEvent!!.secondary!!.id)
        assertTrue(plays.all { it.awayScore == null && it.homeScore == null && it.periodNumber == 0 })
        assertEquals(1, plays.count { it.soccerEvent!!.kind == SoccerEventKind.RedCard })
    }
    @Test fun missingOwnershipUnknownCardsAndVarShapesNeverBecomeGuessedHomeYellowOrVar() {
        val original = raw()["content"]["matchFacts"]["events"]["events"].array.first()
        val unknown = replace(JsonObject(original.jsonObject - "isHome"), "card", JsonPrimitive("NewColour"))
        val entry = NativeFotMobDetails.events(events(listOf(replace(unknown, "VAR", JsonPrimitive("unverified")))), game(), mapping()).single()
        assertNull(entry.teamId); assertEquals(SoccerEventKind.Unknown, entry.soccerEvent!!.kind)
        assertTrue(entry.type.contains("NewColour"))
    }
    @Test fun synthesizedTimelineIdsSurviveReorderButAmbiguousIdenticalEventsAreRejected() {
        val originals = raw()["content"]["matchFacts"]["events"]["events"].array.filter { it["type"].string() == "Substitution" }.take(2)
        val rows = originals.map { JsonObject(it.jsonObject - "eventId" - "reactKey") }
        val a = NativeFotMobDetails.events(events(rows), game(), mapping()).map { it.id }.toSet()
        val b = NativeFotMobDetails.events(events(rows.reversed()), game(), mapping()).map { it.id }.toSet()
        assertEquals(a, b); assertEquals(2, a.size)
        assertTrue(runCatching { NativeFotMobDetails.events(events(listOf(rows.first(), rows.first())), game(), mapping()) }.exceptionOrNull() is IOException)
    }
    @Test fun unknownEventTimesRetainSourceOrderInsteadOfBeingPlacedAtAnInventedMinute() {
        val originals = raw()["content"]["matchFacts"]["events"]["events"].array.filter { it["type"].string() == "Card" }.take(2)
        val rows = listOf(JsonObject(originals[0].jsonObject - "time"), originals[1])
        val result = NativeFotMobDetails.events(events(rows), game(), mapping()).asReversed()
        assertEquals("", result.first().clock)
        assertNull(result.first().soccerEvent!!.minute)
        assertTrue(result.last().clock.isNotBlank())
        assertTrue(result.all { it.periodNumber == 0 })
    }
    @Test fun recordedLineupPreservesReportedGeometryAndExplicitRatingWithoutInventingFormationRows() {
        val teams = NativeFotMobDetails.lineups(raw(), game(), mapping()); assertEquals(2, teams.size)
        val home = teams.first { it.home }; assertEquals("3-5-2", home.formation); assertEquals("Quique Sánchez Flores", home.manager)
        assertEquals(11, home.players.count { it.starter }); assertFalse(home.hasPitch)
        val keeper = home.players.single { it.player.id == "fotmob:530468" }
        assertEquals(SoccerPitchPosition(.5f, .1f), keeper.pitchPosition); assertEquals(7.4, keeper.rating!!, .001)
        assertEquals("FotMob Rating", keeper.ratingSource); assertEquals("1", keeper.player.number)
        assertTrue(home.players.none { it.row != null || it.column != null || it.captain })
    }
    @Test fun completedCrossesAndPassesRetainCountsRatherThanBeingMislabelledAttemptsOrRatios() {
        val stats = NativeFotMobDetails.statistics(raw(), game(), mapping())
        assertEquals("52%", stats.single { it.label == "Possession" }.home)
        assertEquals("1.93", stats.single { it.label == "Expected goals" }.home)
        assertEquals("287", stats.single { it.label == "Completed passes" }.home)
        assertTrue(stats.any { it.label == "Completed crosses" }); assertFalse(stats.any { it.label == "Attempted crosses" })
        assertEquals("0.24", stats.single { it.label == "Expected goals" }.away)
    }
    @Test fun shotCoordinatesRemainRawMissingPositionsAreAbsentAndForeignClubsAreRejected() {
        val shots = NativeFotMobDetails.shots(raw(), game(), mapping()); assertEquals(24, shots.size)
        assertTrue(shots.any { it.x!! > 100 }); assertTrue(shots.all { it.teamId.startsWith("laliga:") })
        val shot = raw()["content"]["shotmap"]["shots"].array.first()
        fun altered(value: JsonElement) = replace(raw(), "content", replace(raw()["content"], "shotmap", buildJsonObject { put("shots", JsonArray(listOf(value))) }))
        val absent = NativeFotMobDetails.shots(altered(JsonObject(shot.jsonObject - "x" - "y")), game(), mapping()).single()
        assertNull(absent.x); assertNull(absent.y)
        assertTrue(runCatching { NativeFotMobDetails.shots(altered(replace(shot, "teamId", JsonPrimitive(123))), game(), mapping()) }.exceptionOrNull() is IOException)
    }
    @Test fun pressureKeepsFractionalAddedTimeSamplesAndSignedSourceValues() {
        val samples = NativeFotMobDetails.pressure(raw(), game(), mapping())
        assertTrue(samples.any { it.minute == 45.5 }); assertTrue(samples.any { it.minute == 90.75 })
        assertTrue(samples.any { it.value < 0 }); assertTrue(samples.any { it.value > 0 })
    }
    @Test fun actualTickerUsesReportedClubsAddedTimeAndDoesNotCallMatchEndMinuteZeroKickoff() {
        val bytes = Base64.getDecoder().decode(javaClass.getResource("/parity/laliga-fotmob-ticker-finished.source.base64")!!.readText().trim())
        val raw = NativeFotMobClient.decodeTicker(bytes); val entries = NativeFotMobDetails.commentary(raw, mapping())
        assertEquals("", entries.first().clock); assertEquals("90+9′", entries[1].clock)
        assertTrue(entries.first().text.startsWith("Match ends")); assertTrue(entries.all { it.timestamp == null })
        assertTrue(runCatching { NativeFotMobDetails.commentary(replace(raw, "HometeamId", JsonPrimitive(123)), mapping()) }.exceptionOrNull() is IOException)
    }
    @Test fun everyDetailSurfaceRejectsForeignMatchesAndMissingIsNotSuccessfulEmpty() {
        val wrong = mapping().copy(fotmobId = "999")
        for (operation in listOf<() -> Any>({ NativeFotMobDetails.lineups(raw(), game(), wrong) }, { NativeFotMobDetails.events(raw(), game(), wrong) },
            { NativeFotMobDetails.statistics(raw(), game(), wrong) }, { NativeFotMobDetails.shots(raw(), game(), wrong) }, { NativeFotMobDetails.pressure(raw(), game(), wrong) })) {
            assertTrue(runCatching(operation).exceptionOrNull() is IOException)
        }
        val emptyContent = replace(raw(), "content", buildJsonObject {})
        assertTrue(runCatching { NativeFotMobDetails.events(emptyContent, game(), mapping()) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { NativeFotMobDetails.events(events(emptyList()), game(), mapping()) }.isSuccess)
    }
}
