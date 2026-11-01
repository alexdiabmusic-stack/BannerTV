package com.alexdiab.stadiatv.sports.f1

import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.*
import kotlin.math.abs

private fun JsonElement?.number(): Double? = stringOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun JsonElement?.integer(): Int? = number()?.toInt()
private fun JsonElement?.display(): String? = (this["Value"].stringOrNull() ?: stringOrNull())?.trim()?.takeIf(String::isNotEmpty)
private fun performance(value: JsonElement?): String = when { value["OverallFastest"].bool() -> "overallBest"; value["PersonalFastest"].bool() -> "personalBest"; else -> "neutral" }

object F1Mapper {
    fun stints(raw: JsonElement?): List<F1Stint> {
        val entries = F1Wire.indexed(raw)
        var start: Int? = if (entries.any { it.second["TyresNotChanged"].int() == 1 }) null else 1
        return entries.map { (key, value) ->
            val laps = value["TotalLaps"].integer()
            val length = laps?.let { (it - value["StartLaps"].int()).coerceAtLeast(0) }
            val compound = value["Compound"].string().uppercase().takeIf { it in setOf("SOFT", "MEDIUM", "HARD", "INTERMEDIATE", "WET") } ?: "UNKNOWN"
            F1Stint(key, compound, laps, value["New"]?.let { it.bool() }, length, start).also { start = if (length != null) start?.plus(length) else null }
        }
    }

    fun state(topics: Map<String, JsonElement>, identity: String, time: Long): F1State {
        val info = topics["SessionInfo"]; val session = topics["SessionData"]
        val timing = topics["CanonicalTiming"] ?: topics["TimingDataF1"] ?: topics["TimingData"]
        val name = info["Name"].stringOrNull() ?: info["Type"].string("Session")
        val type = F1Session.type(if (name.contains("sprint", true)) name else info["Type"].string(name))
        val phase = timing["SessionPart"].integer() ?: F1Wire.indexed(session["Series"]).lastOrNull()?.second?.get("QualifyingPart").integer()
        val phaseIndex = ((phase ?: 1) - 1).coerceAtLeast(0).toString()
        val path = info["Path"].stringOrNull()
        val clock = topics["ExtrapolatedClock"]
        val weather = topics["WeatherData"]
        val drivers = topics["DriverList"].obj.orEmpty().mapNotNull { (key, value) ->
            val number = value["RacingNumber"].stringOrNull() ?: key.takeIf { it.toIntOrNull() != null } ?: return@mapNotNull null
            val line = timing["Lines"][number]; val stats = topics["TimingStats"]["Lines"][number]; val app = topics["TimingAppData"]["Lines"][number]
            val times = F1Wire.indexed(line["BestLapTimes"])
            val phaseStats = F1Wire.indexed(line["Stats"]).firstOrNull { it.first == phaseIndex }?.second
            val best = if (type in setOf("Qualifying", "Sprint Qualifying")) { if (times.isEmpty()) line["BestLapTime"]["Value"].stringOrNull() else times.firstOrNull { it.first == phaseIndex }?.second?.get("Value").stringOrNull() } else line["BestLapTime"]["Value"].stringOrNull() ?: stats["PersonalBestLapTime"]["Value"].stringOrNull()
            F1Driver(number, value["FullName"].stringOrNull() ?: value["BroadcastName"].string("Unknown driver"), value["Tla"].string(number), value["TeamName"].string(), value["TeamColour"].stringOrNull(), value["HeadshotUrl"].stringOrNull(),
                line["Position"].integer(), if (type in setOf("Race", "Sprint")) line["GapToLeader"].display() else phaseStats["TimeDiffToFastest"].stringOrNull() ?: line["GapToLeader"].display(),
                if (type in setOf("Race", "Sprint")) line["IntervalToPositionAhead"].display() else null,
                line["LastLapTime"]["Value"].stringOrNull(), best, times.map { it.second["Value"].string("–") }, line["NumberOfLaps"].integer(),
                F1Wire.indexed(line["Sectors"]).map { (id, sector) -> F1Sector(id, sector["Value"].stringOrNull(), performance(sector), F1Wire.indexed(sector["Segments"]).map { when (it.second["Status"].integer()) { 2049 -> "personalBest"; 2051 -> "overallBest"; 0, 2048, 2064 -> "neutral"; else -> "unknown" } }) },
                F1Wire.merge(stats["BestSpeeds"], line["Speeds"] ?: JsonObject(emptyMap())).obj.orEmpty().mapNotNull { (id, speed) -> speed["Value"].stringOrNull()?.let { id to it } }.toMap(),
                stints(app["Stints"]), line["InPit"].bool(), line["PitOut"].bool(), line["NumberOfPitStops"].integer(), line["Retired"].bool(), line["Stopped"].bool(), line["KnockedOut"].bool(), line["LastLapTime"]["OverallFastest"].bool(), line["LastLapTime"]["PersonalFastest"].bool())
        }.sortedWith(compareBy<F1Driver> { it.position ?: 999 }.thenBy { it.number })
        return F1State(identity, path, info["Meeting"]["Name"].string("Formula 1"), info["Meeting"]["Circuit"]["ShortName"].stringOrNull(), name,
            topics["SessionStatus"]["Status"].stringOrNull() ?: F1Wire.indexed(session["StatusSeries"]).lastOrNull()?.second?.get("SessionStatus").stringOrNull() ?: info["SessionStatus"].string("Unknown"),
            phase, topics["LapCount"]["CurrentLap"].integer(), topics["LapCount"]["TotalLaps"].integer(), F1Wire.duration(clock["Remaining"].stringOrNull()), F1Wire.utc(clock["Utc"].stringOrNull()), clock["Extrapolating"].bool(), drivers,
            topics["TrackStatus"]["Status"].stringOrNull(), when (topics["TrackStatus"]["Status"].string()) { "1" -> "Green flag"; "2" -> "Yellow flag"; "4" -> "Safety Car"; "5" -> "Red flag"; "6" -> "Virtual Safety Car"; "7" -> "VSC ending"; else -> "Track status unavailable" },
            weather?.takeIf { !it.obj.isNullOrEmpty() }?.let { F1Weather(it["AirTemp"].number(), it["TrackTemp"].number(), it["Humidity"].number(), it["Pressure"].number(), it["WindDirection"].number(), it["WindSpeed"].number()?.times(3.6), when (it["Rainfall"].integer()) { 0 -> false; 1 -> true; else -> null }) },
            F1Wire.indexed(topics["RaceControlMessages"]["Messages"]).mapNotNull { (id, raw) -> raw["Message"].stringOrNull()?.let { F1Message("$identity:rc:$id", F1Wire.utc(raw["Utc"].stringOrNull()), raw["Category"].string("Other"), raw["Flag"].stringOrNull(), raw["RacingNumber"].stringOrNull(), it, raw["Lap"].integer()) } },
            F1Wire.indexed(topics["TeamRadio"]["Captures"]).map { (id, raw) -> val radioPath = raw["Path"].stringOrNull(); val url = radioPath?.let { if (it.startsWith("https://livetiming.formula1.com/")) it else path?.let { base -> runCatching { F1Wire.archive(base + radioPath) }.getOrNull() } }; F1Radio("$identity:radio:$id", raw["RacingNumber"].stringOrNull(), F1Wire.utc(raw["Utc"].stringOrNull()), url) },
            topics["PitStopSeries"]["PitTimes"].obj.orEmpty().flatMap { (number, stops) -> F1Wire.indexed(stops).map { (id, raw) -> F1PitStop("$number:$id", number, id.toIntOrNull()?.plus(1), raw["PitStop"]["Lap"].integer(), raw["PitStop"]["PitStopTime"].stringOrNull(), raw["PitStop"]["PitLaneTime"].stringOrNull()) } }, topics.keys, time)
    }
}

/** Single-writer state; stale topic deltas never overwrite a more recent snapshot. */
class F1StateStore(val identity: String) {
    private val topics = mutableMapOf<String, JsonElement>()
    private val times = mutableMapOf<String, Long>()
    private val telemetry = mutableMapOf<String, F1Telemetry>()
    private val positions = mutableMapOf<String, F1Position>()
    private val trails = mutableMapOf<String, F1Ring<F1Position>>()
    private val history = mutableMapOf<String, F1Ring<F1Telemetry>>()
    private var frozen: F1State? = null
    fun apply(update: F1Update): Boolean {
        val name = update.topic.removeSuffix(".z")
        if (update.time < (times[name] ?: Long.MIN_VALUE)) return false
        val payload = try { if (update.topic.endsWith(".z")) F1Wire.compressed(update.payload.string()) else (update.payload as? JsonPrimitive)?.takeIf { it.isString }?.let { runCatching { parseJson(it.content) }.getOrNull() } ?: update.payload } catch (_: Exception) { return false }
        times[name] = update.time
        when (name) {
            "CarData" -> ingestCars(payload, update.time)
            "Position" -> ingestPositions(payload, update.time)
            else -> {
                topics[name] = if (update.snapshot) payload else F1Wire.merge(topics[name], payload)
                if (name == "TimingData" || name == "TimingDataF1") topics["CanonicalTiming"] = F1Wire.merge(topics["CanonicalTiming"], payload)
            }
        }
        return true
    }
    fun state(): F1State {
        val result = F1Mapper.state(topics, identity, times.values.maxOrNull() ?: 0)
        frozen?.let { return result.copy(drivers = it.drivers, status = it.status) }
        if (result.finalised) frozen = result
        return result
    }
    fun fast() = F1Fast(telemetry.toMap(), positions.toMap(), trails.mapValues { it.value.values() })
    fun history(driver: String): List<F1Telemetry> = history[driver]?.values().orEmpty()
    fun raw(): Map<String, JsonElement> = topics.toMap()
    private fun ingestCars(raw: JsonElement, fallback: Long) {
        raw["Entries"].array.forEach { entry ->
            val time = F1Wire.utc(entry["Utc"].stringOrNull()) ?: fallback
            entry["Cars"].obj.orEmpty().entries.take(64).forEach { (number, car) ->
                if ((telemetry[number]?.time ?: Long.MIN_VALUE) > time) return@forEach
                val c = car["Channels"]
                fun bounded(key: String, lower: Double, upper: Double) = c[key].number()?.takeIf { it in lower..upper }
                val sample = F1Telemetry(time, bounded("2", 0.0, 1000.0), bounded("0", 0.0, 100000.0), bounded("3", -1.0, 20.0)?.toInt(), bounded("4", 0.0, 100.0), bounded("5", 0.0, 100.0), c["45"].number(), c.obj.orEmpty().filterKeys { it !in setOf("0", "2", "3", "4", "5", "45") }.mapNotNull { (key, value) -> value.number()?.let { key to it } }.toMap())
                if (telemetry.size < 64 || number in telemetry) { telemetry[number] = sample; history.getOrPut(number) { F1Ring(150) }.add(sample) }
            }
        }
    }
    private fun ingestPositions(raw: JsonElement, fallback: Long) {
        raw["Position"].array.forEach { frame ->
            val time = F1Wire.utc(frame["Timestamp"].stringOrNull()) ?: fallback
            frame["Entries"].obj.orEmpty().entries.take(64).forEach { (number, value) ->
                val x = value["X"].number()?.takeIf { abs(it) < 100000000 } ?: return@forEach
                val y = value["Y"].number()?.takeIf { abs(it) < 100000000 } ?: return@forEach
                if ((positions[number]?.time ?: Long.MIN_VALUE) > time) return@forEach
                if (positions.size < 64 || number in positions) { val point = F1Position(time, x, y, value["Z"].number()); positions[number] = point; trails.getOrPut(number) { F1Ring(1500) }.add(point) }
            }
        }
    }
}
