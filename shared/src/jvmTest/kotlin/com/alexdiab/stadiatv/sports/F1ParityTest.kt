package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.f1.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.Deflater
import okio.ByteString.Companion.toByteString

class F1ParityTest {
    private fun fixture(name: String) = parseJson(requireNotNull(javaClass.classLoader?.getResource("parity/f1/$name.json")).readText())
    private fun store(): F1StateStore = F1StateStore("2025.1.Race").also { store ->
        listOf("DriverList", "SessionInfo", "TimingData", "TimingStats", "TimingAppData", "SessionData", "TrackStatus", "WeatherData", "RaceControlMessages", "TeamRadio", "LapCount", "ExtrapolatedClock").forEach { assertTrue(store.apply(F1Update(it, fixture(it), 1000, true))) }
    }
    @Test fun nestedDeltasPreserveUnchangedSectorsTyresAndSpeeds() {
        val old = parseJson("""{"Lines":{"4":{"Position":"2","Gap":"+1.2","Sectors":[{"Value":"25.1","Segments":[{"Status":2048},{"Status":2049}]}],"Speeds":{"ST":{"Value":"310"}},"Stints":[{"Compound":"MEDIUM","TotalLaps":14}]}}}""")
        val patch = parseJson("""{"Lines":{"4":{"Gap":"+0.8","Sectors":{"0":{"Segments":{"1":{"Status":2051}}}},"Stints":{"0":{"TotalLaps":15}}}}}""")
        val line = F1Wire.merge(old, patch)["Lines"]["4"]
        assertEquals("2", line["Position"].string()); assertEquals("+0.8", line["Gap"].string())
        assertEquals("25.1", line["Sectors"][0]["Value"].string()); assertEquals(2051, line["Sectors"][0]["Segments"][1]["Status"].int())
        assertEquals("MEDIUM", line["Stints"][0]["Compound"].string()); assertEquals(15, line["Stints"][0]["TotalLaps"].int()); assertEquals("310", line["Speeds"]["ST"]["Value"].string())
    }
    @Test fun arrayReplacementDeletionSparsePatchAndDictionarySemantics() {
        assertEquals(4, F1Wire.merge(parseJson("[1]"), parseJson("""{"3":4}""")).array.size)
        assertEquals(1, F1Wire.merge(parseJson("[1,2]"), parseJson("[3]")).array.size)
        assertTrue(F1Wire.merge(parseJson("""{"4":{"Position":1}}"""), parseJson("""{"4":{"_deleted":true}}""")).obj!!.isEmpty())
        assertTrue(F1Wire.merge(null, parseJson("""{"4":{"Position":1}}""")) is JsonObject)
        assertTrue(F1Wire.merge(parseJson("[1]"), parseJson("""{"1000000000":1}""")).array.size == 1)
    }
    @Test fun signalRCoreKeepsPartialAndCombinedUtf8Records() {
        val parser = F1Protocol()
        assertTrue(parser.receive("{".toByteArray()).isEmpty())
        val events = parser.receive(("}\u001e" + """{"type":1,"target":"feed","arguments":["TrackStatus",{"Status":"4"},"2026-01-01T00:00:00Z"]}""" + "\u001e").toByteArray())
        assertEquals(2, events.size); assertTrue(events.first() is F1HubEvent.Handshake)
        assertEquals("TrackStatus", (events[1] as F1HubEvent.Update).update.topic)
        assertTrue(F1Wire.subscription.contains("CarData.z")); assertTrue(F1Wire.subscription.endsWith('\u001e'))
    }
    @Test fun handshakeFailureAndSubscriptionErrorAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { F1Protocol().receive("{\"error\":\"denied\"}\u001e".toByteArray()) }
        val parser = F1Protocol(); parser.receive("{}\u001e".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { parser.receive("{\"type\":3,\"invocationId\":\"0\",\"error\":\"denied\"}\u001e".toByteArray()) }
    }
    @Test fun recordedRawDeflateAndMovingTelemetry() {
        for (topic in listOf("CarData", "Position")) assertTrue(F1Wire.compressed(fixture("$topic.z").string())[if (topic == "CarData") "Entries" else "Position"].array.isNotEmpty())
        val store = F1StateStore("recorded")
        assertTrue(store.apply(F1Update("CarData.z", fixture("CarData.moving.z"), 1000)))
        assertTrue(store.apply(F1Update("Position.z", fixture("Position.moving.z"), 1000)))
        val fast = store.fast()
        assertTrue(fast.telemetry["1"]!!.speed!! > 100); assertTrue(fast.telemetry["1"]!!.rpm!! > 10000)
        assertNull(fast.telemetry["6"]?.throttle); assertNull(fast.telemetry["6"]?.brake)
        assertNotEquals(0.0, fast.positions["1"]!!.x); assertTrue(fast.trails["1"]!!.isNotEmpty()); assertTrue(store.history("1").isNotEmpty())
    }
    @Test fun corruptionAndDecompressionBombAreBounded() {
        assertThrows(Exception::class.java) { F1Wire.compressed("invalid") }
        val deflater = Deflater(9, true); deflater.setInput(ByteArray(100000) { 32 }); deflater.finish(); val compressed = ByteArray(4096); val n = deflater.deflate(compressed); deflater.end()
        assertThrows(IllegalArgumentException::class.java) { F1Wire.compressed(compressed.copyOf(n).toByteString().base64(), 1000) }
    }
    @Test fun archivedSessionKeepsNativeDriversStrategyFlagsWeatherAndRadio() {
        val state = store().state()
        assertEquals(20, state.drivers.size); assertEquals("Race", state.name)
        assertTrue(state.drivers.any { it.stints.isNotEmpty() }); assertTrue(state.messages.isNotEmpty()); assertTrue(state.messages.all { it.time != null })
        assertTrue(state.radio.any { it.url?.startsWith("https://livetiming.formula1.com/") == true }); assertNotNull(state.weather?.track)
    }
    @Test fun officialResultsPreserveRichArchiveAndRejectOtherSeason() {
        val session = F1Session("2025.1.Race", "Australian Grand Prix", "Albert Park", "Race", 0, 2025, 1)
        val official = requireNotNull(F1Repository.official(fixture("JolpicaResults"), session))
        assertTrue(official.finalised); assertEquals(20, official.drivers.size); assertEquals("NOR", official.drivers.first().tla)
        val merged = F1Repository.mergeResult(store().state(), official)
        assertEquals(1, merged.drivers.first().position); assertTrue(merged.drivers.first().stints.isNotEmpty())
        assertNull(F1Repository.official(fixture("JolpicaResults"), session.copy(season = 2026)))
    }
    @Test fun staleCorruptAndPostFinalUpdatesCannotRegressTiming() {
        val store = F1StateStore("test")
        store.apply(F1Update("TrackStatus", parseJson("{\"Status\":\"4\"}"), 1000))
        assertFalse(store.apply(F1Update("TrackStatus", parseJson("{\"Status\":\"1\"}"), 999)))
        assertFalse(store.apply(F1Update("CarData.z", JsonPrimitive("invalid"), 2000)))
        assertEquals("Safety Car", store.state().trackStatus)
        store.apply(F1Update("DriverList", parseJson("""{"4":{"RacingNumber":"4"}}"""), 1000))
        store.apply(F1Update("TimingData", parseJson("""{"Lines":{"4":{"Position":"1"}}}"""), 1000))
        store.apply(F1Update("SessionStatus", parseJson("{\"Status\":\"Finalised\"}"), 1000))
        assertEquals(1, store.state().drivers.first().position)
        store.apply(F1Update("TimingData", parseJson("""{"Lines":{"4":{"Position":"2"}}}"""), 2000))
        assertEquals(1, store.state().drivers.first().position)
    }
    @Test fun calendarDeduplicatesSprintShootoutAndNeverUsesMeetingIndexAsRound() {
        val raw = parseJson("""{"season":"2026","round":"7","raceName":"Example","Circuit":{"circuitName":"Circuit"},"date":"2026-05-01","time":"14:00:00Z","SprintQualifying":{"date":"2026-04-30","time":"14:00:00Z"},"SprintShootout":{"date":"2026-04-30","time":"14:00:00Z"}}""")
        val meeting = requireNotNull(F1Repository.meeting(raw)); assertEquals(2, meeting.sessions.size); assertEquals("2026.7.Race", meeting.sessions.last().id)
    }
    @Test fun localOffsetAndSessionIdentityAreChecked() {
        val session = F1Session("2026.1.Race", "Example", "Circuit", "Race", requireNotNull(F1Wire.utc("2026-03-01T05:00:00Z")), 2026, 1)
        val info = parseJson("""{"Name":"Race","StartDate":"2026-03-01T15:00:00","GmtOffset":"10:00:00"}""")
        assertTrue(F1Repository.matches(info, session)); assertFalse(F1Repository.matches(info, session.copy(name = "Qualifying")))
        assertEquals(F1Wire.utc("2026-03-01T05:00:00.123Z"), F1Wire.utc("2026-03-01T05:00:00.1234567"))
    }
    @Test fun boundedRingsClockAndArchivePath() {
        val ring = F1Ring<Int>(150); repeat(10000, ring::add); assertEquals((9850..9999).toList(), ring.values())
        assertEquals(108, F1State("test", remaining = 120.0, clockUtc = 1000, extrapolating = true).seconds(13000))
        assertEquals(120, F1State("test", remaining = 120.0, clockUtc = 1000).seconds(13000))
        assertThrows(IllegalArgumentException::class.java) { F1Wire.archive("../private") }
        assertThrows(IllegalArgumentException::class.java) { F1Wire.archive("https://example.com") }
        assertNull(F1Wire.duration("NaN")); assertNull(F1Wire.duration("1:-2:00"))
    }
}
