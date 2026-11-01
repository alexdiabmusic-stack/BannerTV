package com.alexdiab.stadiatv.sports.f1

import com.alexdiab.stadiatv.foundation.*
import okio.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okio.ByteString.Companion.decodeBase64

/** Translated from the iOS F1 protocol/state store, rather than a second scoreboard feed. */
object F1Wire {
    const val LIMIT = 16 * 1024 * 1024
    val topics = listOf("Heartbeat", "DriverList", "ExtrapolatedClock", "RaceControlMessages", "SessionInfo", "SessionStatus", "SessionData", "LapCount", "TimingData", "TimingStats", "TimingAppData", "TrackStatus", "WeatherData", "TeamRadio", "TopThree", "CarData.z", "Position.z", "TimingDataF1", "PitLaneTimeCollection", "PitStopSeries", "LapSeries", "CurrentTyres", "TyreStintSeries", "ChampionshipPrediction", "DriverRaceInfo", "OvertakeSeries")
    const val handshake = "{\"protocol\":\"json\",\"version\":1}\u001e"
    val subscription = buildJsonObject { put("type", 1); put("invocationId", "0"); put("target", "Subscribe"); put("arguments", JsonArray(listOf(JsonArray(topics.map(::JsonPrimitive))))) }.toString() + '\u001e'

    fun archive(path: String): String {
        require(!path.contains("..") && !path.contains(':') && !path.contains('\\')) { "Invalid timing archive path" }
        return "https://livetiming.formula1.com/static/" + path.trim('/')
    }

    fun compressed(text: String, limit: Int = LIMIT): JsonElement {
        require(text.length <= limit * 4L / 3 + 4)
        val data = requireNotNull(text.decodeBase64()).toByteArray()
        require(data.isNotEmpty() && data.size <= limit)
        return Buffer().write(data).inflate(Inflater(true)).buffer().use { stream ->
            require(!stream.request(limit.toLong() + 1)) { "Timing payload exceeds safe size" }
            parseJson(stream.readUtf8())
        }
    }

    fun utc(text: String?): Long? = parse(text) ?: text?.let { parse(it + "Z") }
    fun parse(text: String?): Long? {
        if (text == null) return null
        val normalized = text.replace(Regex("(\\.\\d{3})\\d+(?=Z|[+-]\\d\\d:\\d\\d|$)"), "$1")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            SportsTime.parseLocal(normalized, pattern, "UTC")?.let { return it }
        }
        return null
    }
    fun start(info: JsonElement?): Long? {
        val text = info["StartDate"].stringOrNull() ?: return null
        parse(text)?.let { return it }
        val local = utc(text) ?: return null
        val offset = info["GmtOffset"].string("00:00:00")
        return local - ((duration(offset.trimStart('+', '-')) ?: 0.0) * 1000 * if (offset.startsWith('-')) -1 else 1).toLong()
    }
    fun duration(text: String?): Double? {
        val pieces = text?.split(':') ?: return null
        if (pieces.isEmpty() || pieces.size > 3) return null
        val values = pieces.map { it.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n >= 0 } ?: return null }
        return values.fold(0.0) { n, v -> n * 60 + v }.takeIf { it.isFinite() && it <= 604800 }
    }
    fun indexed(raw: JsonElement?): List<Pair<String, JsonElement>> = when (raw) {
        is JsonArray -> raw.mapIndexed { i, v -> i.toString() to v }
        is JsonObject -> raw.entries.sortedWith(compareBy<Map.Entry<String, JsonElement>> { it.key.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.key }).map { it.key to it.value }
        else -> emptyList()
    }
    /** Arrays replace; numeric object patches index arrays; dictionaries remain dictionaries. */
    fun merge(old: JsonElement?, delta: JsonElement): JsonElement {
        if (delta["_deleted"].bool()) return JsonNull
        if (old is JsonArray && delta is JsonObject && delta.keys.all { it.toIntOrNull() != null }) {
            val result = old.toMutableList()
            delta.forEach { (key, value) -> val index = key.toInt(); if (index in 0 until 100000) { while (result.size <= index) result.add(JsonNull); result[index] = merge(result[index], value) } }
            return JsonArray(result)
        }
        if (delta is JsonObject) {
            val result = old.obj?.toMutableMap() ?: mutableMapOf()
            delta.forEach { (key, value) -> if (value["_deleted"].bool()) result.remove(key) else result[key] = merge(result[key], value) }
            return JsonObject(result)
        }
        return delta
    }
}

data class F1Update(val topic: String, val payload: JsonElement, val time: Long, val snapshot: Boolean = false)
sealed interface F1HubEvent : F1TransportEvent {
    data object Handshake : F1HubEvent
    data class Snapshot(val updates: List<F1Update>) : F1HubEvent
    data class Update(val update: F1Update) : F1HubEvent
    data object Ping : F1HubEvent
    data class Closed(val reason: String) : F1HubEvent
}

class F1Protocol {
    private var buffer = ByteArray(0)
    private var handshaken = false
    fun receive(bytes: ByteArray, now: Long = nowMillis()): List<F1HubEvent> {
        require(buffer.size.toLong() + bytes.size <= F1Wire.LIMIT) { "Timing frame exceeds safe size" }
        buffer += bytes
        val output = mutableListOf<F1HubEvent>()
        var start = 0
        for (end in buffer.indices) if (buffer[end] == 0x1e.toByte()) {
            val text = buffer.decodeToString(start, end); start = end + 1
            if (text.isEmpty()) continue
            val frame = parseJson(text)
            if (!handshaken) {
                require(frame is JsonObject && frame.isEmpty()) { frame["error"].string("Invalid timing handshake") }
                handshaken = true; output += F1HubEvent.Handshake; continue
            }
            when (frame["type"].int()) {
                1 -> if (frame["target"].string().equals("feed", true)) {
                    val args = frame["arguments"].array
                    val topic = args.getOrNull(0).stringOrNull()
                    if (topic != null && args.size >= 2) output += F1HubEvent.Update(F1Update(topic, args[1], F1Wire.utc(args.getOrNull(2).stringOrNull()) ?: now))
                }
                3 -> if (frame["invocationId"].string() == "0") {
                    require(frame["error"] == null) { frame["error"].string() }
                    output += F1HubEvent.Snapshot(frame["result"].obj.orEmpty().map { F1Update(it.key, it.value, now, true) })
                }
                6 -> output += F1HubEvent.Ping
                7 -> output += F1HubEvent.Closed(frame["error"].string("Timing connection closed"))
            }
        }
        if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
        return output
    }
}

@Serializable data class F1Session(val id: String, val meeting: String, val circuit: String, val name: String, val start: Long, val season: Int, val round: Int) {
    val type: String get() = type(name)
    val race: Boolean get() = type in setOf("Race", "Sprint")
    val qualifying: Boolean get() = type in setOf("Qualifying", "Sprint Qualifying")
    companion object { fun type(name: String): String = when (name.lowercase()) { "practice", "practice 1", "practice 2", "practice 3" -> "Practice"; "sprint qualifying", "sprint shootout" -> "Sprint Qualifying"; else -> name.replaceFirstChar { it.uppercase() } } }
}
@Serializable data class F1Meeting(val id: String, val name: String, val country: String, val circuit: String, val sessions: List<F1Session>)
@Serializable data class F1Sector(val id: String, val time: String?, val performance: String, val segments: List<String>)
@Serializable data class F1Stint(val id: String, val compound: String, val laps: Int?, val fresh: Boolean?, val completedLaps: Int?, val startLap: Int?)
@Serializable data class F1Driver(val number: String, val name: String, val tla: String, val team: String, val colour: String? = null, val headshot: String? = null,
    val position: Int? = null, val gap: String? = null, val interval: String? = null, val lastLap: String? = null, val bestLap: String? = null,
    val qualifyingTimes: List<String> = emptyList(), val laps: Int? = null, val sectors: List<F1Sector> = emptyList(), val speeds: Map<String, String> = emptyMap(),
    val stints: List<F1Stint> = emptyList(), val inPit: Boolean = false, val pitOut: Boolean = false, val stops: Int? = null,
    val retired: Boolean = false, val stopped: Boolean = false, val knockedOut: Boolean = false, val overallFastest: Boolean = false, val personalFastest: Boolean = false, val classification: String? = null)
@Serializable data class F1Message(val id: String, val time: Long?, val category: String, val flag: String?, val driver: String?, val text: String, val lap: Int?)
@Serializable data class F1Radio(val id: String, val driver: String?, val time: Long?, val url: String?)
@Serializable data class F1PitStop(val id: String, val driver: String, val number: Int?, val lap: Int?, val duration: String?, val laneTime: String?)
@Serializable data class F1Weather(val air: Double?, val track: Double?, val humidity: Double?, val pressure: Double?, val windDirection: Double?, val windKph: Double?, val raining: Boolean?)
@Serializable data class F1State(val identity: String, val path: String? = null, val meeting: String = "Formula 1", val circuit: String? = null, val name: String = "Session",
    val status: String = "Unknown", val phase: Int? = null, val lap: Int? = null, val totalLaps: Int? = null, val remaining: Double? = null, val clockUtc: Long? = null, val extrapolating: Boolean = false,
    val drivers: List<F1Driver> = emptyList(), val trackCode: String? = null, val trackStatus: String = "Track status unavailable", val weather: F1Weather? = null,
    val messages: List<F1Message> = emptyList(), val radio: List<F1Radio> = emptyList(), val pitStops: List<F1PitStop> = emptyList(), val topics: Set<String> = emptySet(), val updated: Long = 0) {
    val finalised: Boolean get() = status == "Finalised"
    val completed: Boolean get() = status in setOf("Finalised", "Ends")
    val active: Boolean get() = status in setOf("Started", "Resumed")
    fun seconds(now: Long): Int? = remaining?.let { (it - if (extrapolating) ((now - (clockUtc ?: now)).coerceAtLeast(0) / 1000.0) else 0.0).coerceAtLeast(0.0).toInt() }
}
data class F1Position(val time: Long, val x: Double, val y: Double, val z: Double?)
data class F1Telemetry(val time: Long, val speed: Double?, val rpm: Double?, val gear: Int?, val throttle: Double?, val brake: Double?, val aero: Double?, val unknown: Map<String, Double>)
data class F1Fast(val telemetry: Map<String, F1Telemetry> = emptyMap(), val positions: Map<String, F1Position> = emptyMap(), val trails: Map<String, List<F1Position>> = emptyMap())

/** Fixed-capacity history avoids retaining an entire race's high frequency updates. */
class F1Ring<T>(private val capacity: Int) {
    private val storage = ArrayList<T>(); private var cursor = 0
    fun add(value: T) { if (capacity <= 0) return; if (storage.size < capacity) storage += value else { storage[cursor] = value; cursor = (cursor + 1) % capacity } }
    fun values(): List<T> = if (storage.size < capacity || cursor == 0) storage.toList() else storage.subList(cursor, storage.size) + storage.subList(0, cursor)
}
