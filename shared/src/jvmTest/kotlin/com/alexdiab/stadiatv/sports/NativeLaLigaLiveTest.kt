package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real independent official/FotMob live field slices; modifications below are labelled constructions. */
class NativeLaLigaLiveTest {
    private fun fixture(name: String) = parseJson(javaClass.getResource("/parity/laliga-$name.source.json")!!.readText())
    private fun game() = NativeLaLigaMapping.match(fixture("official-live-20261024"), 2026, "laliga-easports-2026")!!
    private fun day() = buildJsonObject { put("leagues", buildJsonArray { add(buildJsonObject { put("id", 87); put("matches", fixture("fotmob-current-rows-20261024")) }) }) }
    private fun mapping() = NativeLaLigaReconciliation.resolve(game(), listOf(day()))!!
    private fun replace(raw: JsonElement?, key: String, value: JsonElement) = JsonObject(requireNotNull(raw).jsonObject + (key to value))
    @Test fun actualLiveClockUsesReportedDirectionalMarkedShortStringNotInferredMinuteOrHalf() {
        val game = game(); val raw = fixture("fotmob-live-header-20261024")
        val sample = NativeLaLigaAuthority.fotmob(raw, game, mapping(), game.startMillis + 2 * 3_600_000)
        assertEquals(GameState.LIVE, sample.match.state); assertEquals("52′", sample.match.live.clock)
        assertEquals("52′", sample.match.statusDetail); assertNull(sample.match.live.period)
        assertEquals("0–0", sample.match.displayScore); assertEquals("laliga:49", sample.match.home.teamId)
        assertFalse(sample.match.live.clock!!.contains('\u200e'))
    }
    @Test fun constructedClockVariantsKeepOnlyValidReportedFieldsAndNeverRunFromHalfTimestamps() {
        val raw = fixture("fotmob-live-header-20261024"); val map = mapping()
        fun mapped(time: JsonElement) = NativeLaLigaAuthority.fotmob(replace(raw, "header", replace(raw["header"], "status", replace(raw["header"]["status"], "liveTime", time))), game(), map, game().startMillis + 7_200_000).match
        assertEquals("51:32", mapped(buildJsonObject { put("long", "51:32") }).live.clock)
        assertEquals("90+4′", mapped(buildJsonObject { put("short", "90+4\u200e’\u200e") }).live.clock)
        for (token in listOf("999′", "wrong", "52;drop", "-1′")) assertNull(mapped(buildJsonObject { put("short", token); put("long", "51:99") }).live.clock)
        assertNull(mapped(buildJsonObject {}).live.clock) // Actual half timestamps still exist in the root, but are not extrapolated.
        val half = mapped(buildJsonObject { put("short", "HT"); put("shortKey", "halftime_short"); put("long", "45:00") })
        assertEquals("Half-time", half.statusDetail); assertNull(half.live.clock)
    }
    @Test fun currentRealRacingEspanyolPairResolvesBothFormalNamesWithoutScoreMatching() {
        val selected = mapping()
        assertEquals("5868106", selected.fotmobId); assertEquals("8696", selected.fotmobHome); assertEquals("8558", selected.fotmobAway)
        assertEquals("laliga:49", selected.officialHome); assertEquals("laliga:8", selected.officialAway)
        assertNull(NativeLaLigaReconciliation.resolve(game().copy(home = game().away, away = game().home), listOf(day())))
        assertEquals(selected, NativeLaLigaReconciliation.resolve(game().copy(home = game().home.copy(score = "99")), listOf(day())))
    }
    @Test fun otherObservedCurrentClubNamePairsNormalizeExactlyNotByFuzzySubstring() {
        for ((formal, native) in listOf("Valencia Club de Fútbol SAD" to "Valencia", "Real Club Deportivo de A Coruña SAD" to "Deportivo A Coruña", "Málaga Club de Fútbol SAD" to "Málaga"))
            assertEquals(NativeLaLigaReconciliation.normalize(formal), NativeLaLigaReconciliation.normalize(native))
        assertNotEquals(NativeLaLigaReconciliation.normalize("Real Racing Club SAD"), NativeLaLigaReconciliation.normalize("Racing Ferrol"))
        assertNotEquals(NativeLaLigaReconciliation.normalize("Valencia Club de Fútbol SAD"), NativeLaLigaReconciliation.normalize("Valencia B"))
    }
    @Test fun realOfficialFixtureContainsSixAssignedOfficialsWithoutAnyFotmobResource() {
        val result = NativeLaLigaMapping.officials(fixture("official-live-20261024"), game())!!
        assertEquals(6, result.size)
        assertEquals("Jesús Gil Manzano", result.single { it.role == "Referee" }.name)
        assertEquals("Raúl Martín González Francés", result.single { it.role == "VAR" }.name)
        assertEquals("Guillermo Cuadra Fernández", result.single { it.role == "Assistant VAR" }.name)
    }
    @Test fun actualLivePressureFutureNullSlotsAreSkippedWithoutInventingZeroValuesOrRejectingThePublishedHistory() {
        val raw = fixture("fotmob-live-header-20261024")
        // Constructed wrapper around independently recorded unmodified header/pressure field slices.
        val joined = replace(raw, "content", buildJsonObject { put("momentum", buildJsonObject { put("main", buildJsonObject { put("data", fixture("fotmob-live-pressure-20261024")) }) }) })
        val samples = NativeFotMobDetails.pressure(joined, game(), mapping())
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), samples.map { it.minute })
        assertEquals(listOf(0.0, -22.0, -11.0, -5.0), samples.map { it.value })
        assertFalse(samples.any { it.minute >= 88 })
    }
    @Test fun recordedFinalTimelineUsesReportedAddedTimeAndPlacesFullTimeAfterTheLastStoppageIncident() {
        val raw = fixture("fotmob-live-header-20261024")
        val rows = fixture("fotmob-final-event-order-20261024").array
        // Constructed wrapper around independent actual header/event field slices, not one recorded response.
        fun plays(entries: List<JsonElement>): List<Play> = NativeFotMobDetails.events(replace(raw, "content", buildJsonObject {
            put("matchFacts", buildJsonObject { put("events", buildJsonObject { put("events", JsonArray(entries)) }) })
        }), game(), mapping())
        val expected = listOf("8′", "45′", "62′", "69′", "84′", "90+5′", "90+7′", "90′")
        val accepted = plays(rows)
        assertEquals(expected, accepted.asReversed().map { it.clock })
        assertEquals(expected, plays(rows.reversed()).asReversed().map { it.clock })
        assertEquals(listOf("8′", "62′", "69′", "84′", "90+7′"), NativeSoccerPresentation.keyEvents(accepted).map { it.clock })
        assertEquals(SoccerEventKind.PeriodEnd, accepted.first().soccerEvent!!.kind)
        assertTrue(accepted.all { it.periodNumber == 0 }) // Minute order is not permission to invent a half.
        assertEquals(accepted.map { it.id }.toSet(), plays(rows.reversed()).map { it.id }.toSet())
    }
    @Test fun officialsRejectForeignFixtureAndMalformedDuplicatesButMissingIsNotSuccessfulEmpty() {
        val raw = fixture("official-live-20261024")
        assertTrue(runCatching { NativeLaLigaMapping.officials(raw, game().copy(id = "laliga:999")) }.isFailure)
        assertNull(NativeLaLigaMapping.officials(JsonObject(raw.jsonObject - "persons_role"), game()))
        assertEquals(emptyList<SoccerOfficial>(), NativeLaLigaMapping.officials(replace(raw, "persons_role", JsonArray(emptyList())), game()))
        val assignment = raw["persons_role"].array.first()
        assertTrue(runCatching { NativeLaLigaMapping.officials(replace(raw, "persons_role", JsonArray(listOf(assignment, assignment))), game()) }.isFailure)
        val unknown = replace(assignment, "role", buildJsonObject { put("slug", "new-provider-role"); put("name", "New published role") })
        assertEquals("New published role", NativeLaLigaMapping.officials(replace(raw, "persons_role", JsonArray(listOf(unknown))), game())!!.single().role)
    }
}
