package com.alexdiab.stadiatv.sports.f1

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.sports.SportsProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

data class F1View(val session: F1Session, val state: F1State? = null, val fast: F1Fast = F1Fast(), val connection: String = "disconnected", val attempts: Int = 0, val cached: Boolean = false, val error: String? = null, val lastMessage: Long = 0, val lastData: Long = 0) {
    fun isLive(now: Long) = !cached && connection == "connected" && state?.active == true && now - lastMessage < 30000 && now - lastData < 30000
}

/** Calendar, historical snapshots and continuous timing use the exact iOS provider identities. */
class F1Repository(private val http: SportsHttp, private val stream: F1Transport, private val directory: SportsFile? = null) : SportsProvider {
    override val league = requireNotNull(League.find("racing/f1"))
    override val capabilities = setOf(SportsCapability.Scores, SportsCapability.Schedule, SportsCapability.Teams, SportsCapability.Standings, SportsCapability.Field)
    private val headers = mapOf("User-Agent" to "BannerTV/1.0")
    private val snapshotMutex = Mutex()
    private var snapshotAt = 0L
    private var discovery = emptyList<F1Update>()

    suspend fun resource(path: String, ttl: Long = 1800000): JsonElement {
        require(!path.contains("..") && !path.contains(':'))
        return http.getJson("https://api.jolpi.ca/ergast/f1/$path?format=json&limit=100", headers, ttl, retries = 1)
    }
    suspend fun meetings(season: Int): List<F1Meeting> = resource("$season/races/", 21600000)["MRData"]["RaceTable"]["Races"].array.mapNotNull(::meeting)
    suspend fun session(id: String): F1Session? = id.substringBefore('.').toIntOrNull()?.takeIf { it in 1950..2100 }?.let { year -> meetings(year).flatMap { it.sessions }.firstOrNull { it.id == id } }
    suspend fun resolve(match: Match): F1Session? = session(match.id) ?: meetings(SportsTime.format("yyyy", match.startMillis).toInt()).flatMap { it.sessions }.filter { abs(it.start - match.startMillis) < 3600000 && (match.name.contains(it.name, true) || match.statusDetail.contains(it.name, true)) }.singleOrNull()

    override suspend fun schedule(start: Long, days: Int): List<Match> {
        val lower = SportsTime.startOfDay(start); val end = SportsTime.addDays(lower, days.coerceIn(1,370))
        return setOf(lower,end).map { SportsTime.format("yyyy", it).toInt() }.distinct().flatMap { meetings(it) }
            .flatMap { it.sessions }.filter { it.start in lower until end }.sortedBy { it.start }.map(::match)
    }
    override suspend fun scores(date: Long?): List<Match> {
        val games = schedule(date ?: nowMillis(), 1)
        val now = nowMillis()
        if (games.none { it.startMillis in (now - 12 * 3600000)..(now + 3600000) }) return games
        val updates = snapshotMutex.withLock {
            if (now - snapshotAt > 30000) {
                discovery = withTimeoutOrNull(8000) { stream.firstSnapshot() }.orEmpty(); snapshotAt = nowMillis()
            }
            discovery
        }
        val info = updates.firstOrNull { it.topic == "SessionInfo" }?.payload ?: return games
        return games.map { game -> val selected = session(game.id); if (selected == null || !matches(info, selected)) game else { val store = F1StateStore(selected.id); updates.forEach(store::apply); match(selected, store.state()) } }
    }
    override suspend fun teams(): List<Team> = resource("current/constructors/")["MRData"]["ConstructorTable"]["Constructors"].array.mapNotNull { item ->
        val id = item["constructorId"].stringOrNull() ?: return@mapNotNull null; val name = item["name"].stringOrNull() ?: return@mapNotNull null
        Team(id, name, name, name, null, league)
    }
    override suspend fun standings(): List<StandingGroup> = coroutineScope { listOf(true, false).map { drivers -> async {
        val root = resource(if (drivers) "current/driverstandings/" else "current/constructorstandings/")["MRData"]["StandingsTable"]["StandingsLists"][0]
        StandingGroup(if (drivers) "Driver championship" else "Constructor championship", root[if (drivers) "DriverStandings" else "ConstructorStandings"].array.mapNotNull { row ->
            val person = row[if (drivers) "Driver" else "Constructor"]; val id = person[if (drivers) "driverId" else "constructorId"].stringOrNull() ?: return@mapNotNull null
            val name = if (drivers) listOf(person["givenName"].string(), person["familyName"].string()).joinToString(" ").trim() else person["name"].string("Constructor")
            StandingRow(id, name, person["code"].string(name), null, row["points"].string("–") + " points", mapOf("rank" to row["position"].string(), "wins" to row["wins"].string(), "points" to row["points"].string()))
        })
    } }.awaitAll() }
    override suspend fun field(): List<Racer> = standings().firstOrNull()?.rows.orEmpty().map { Racer(Athlete(it.teamId, it.teamName), it.values["rank"].orEmpty(), it.record, "") }

    fun watch(selected: F1Session): Flow<F1View> = flow {
        var view = F1View(selected, load(selected.id), cached = true)
        emit(view)
        while (selected.start - nowMillis() > 3600000) delay((selected.start - nowMillis() - 3600000).coerceIn(1, 300000))
        if (selected.start < nowMillis() - 4 * 3600000) {
            var archived: F1State? = null
            try { archived = archive(selected) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            try { val official = result(selected); if (official != null) archived = mergeResult(archived, official) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
            if (archived != null) { save(archived); emit(view.copy(state = archived, cached = false)); return@flow }
        }
        if (selected.start < nowMillis() - 12 * 3600000) { emit(view.copy(error = "Archived timing is not available for this session yet.")); return@flow }
        var store = F1StateStore(selected.id); var accepted = false; var sessionPath: String? = null; var sessionKey: String? = null
        var lastSlow = 0L; var lastFast = 0L; var lastSave = 0L
        stream.run { event ->
            val now = nowMillis()
            when (event) {
                F1HubEvent.Handshake, F1HubEvent.Ping, is F1HubEvent.Closed -> Unit
                is F1Connection -> { if (event.state in setOf("negotiating", "reconnecting")) accepted = false; view = view.copy(connection = event.state, attempts = event.attempts, error = event.error ?: view.error); emit(view) }
                is F1Activity -> view = view.copy(lastMessage = now)
                is F1HubEvent.Snapshot -> {
                    val info = event.updates.firstOrNull { it.topic == "SessionInfo" }?.payload
                    if (info == null || !matches(info, selected)) { accepted = false; view = view.copy(error = "The live feed is currently serving another session."); emit(view) }
                    else { store = F1StateStore(selected.id); event.updates.forEach(store::apply); accepted = true; sessionPath = info["Path"].stringOrNull(); sessionKey = info["Key"].stringOrNull(); view = view.copy(state = store.state(), fast = store.fast(), cached = false, error = null, lastData = now); emit(view) }
                }
                is F1HubEvent.Update -> {
                    val update = event.update
                    if (update.topic == "SessionInfo") {
                        val path = update.payload["Path"].stringOrNull(); val key = update.payload["Key"].stringOrNull()
                        if ((path != null && sessionPath != null && path != sessionPath) || (key != null && sessionKey != null && key != sessionKey)) throw IOException("Timing session changed; reconnecting for a full snapshot.")
                        if (update.payload["StartDate"] != null && !matches(update.payload, selected)) { accepted = false; view = view.copy(error = "The live feed has moved to another session."); emit(view) }
                    }
                    if (accepted && store.apply(update)) {
                        view = view.copy(lastData = now)
                        val fast = update.topic in setOf("CarData.z", "Position.z")
                        if (fast && now - lastFast >= 200) { view = view.copy(fast = store.fast()); lastFast = now; emit(view) }
                        if (!fast && (now - lastSlow >= 500 || update.topic in setOf("RaceControlMessages", "SessionStatus"))) { view = view.copy(state = store.state(), cached = false, error = null); lastSlow = now; emit(view) }
                        if (now - lastSave >= 30000 || view.state?.finalised == true && lastSave < lastSlow) { view.state?.let { save(it) }; lastSave = now }
                    }
                }
            }
        }
    }

    suspend fun archive(selected: F1Session): F1State? = coroutineScope {
        val index = http.getJson(F1Wire.archive("${selected.season}/Index.json"), headers, 300000, 0)
        val path = index["Meetings"].array.flatMap { it["Sessions"].array }.firstOrNull { matches(it, selected) }?.get("Path").stringOrNull() ?: return@coroutineScope null
        val feeds = try { http.getJson(F1Wire.archive(path + "Index.json"), headers, 300000, 0)["Feeds"].obj?.keys.orEmpty() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptySet() }
        val store = F1StateStore(selected.id)
        for (batch in F1Wire.topics.filter { !it.endsWith(".z") && (feeds.isEmpty() || it in feeds) }.chunked(4)) {
            batch.map { topic -> async {
                try { F1Update(topic, http.getJson(F1Wire.archive(path + topic + ".json"), headers, 300000, 0), nowMillis(), true) }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            } }.awaitAll().filterNotNull().forEach(store::apply)
        }
        store.state().takeIf { "TimingData" in it.topics || "TimingDataF1" in it.topics }
    }
    suspend fun result(selected: F1Session): F1State? {
        if (!selected.race) return null
        val suffix = if (selected.type == "Sprint") "sprint/" else "results/"
        return official(resource("${selected.season}/${selected.round}/$suffix", 300000), selected)
    }
    private fun load(id: String): F1State? = try { directory?.let { dir -> SportsFile(dir, "${safeId(id)}.json").takeIf { it.length() in 1..4_194_304 }?.readText()?.let { AppJson.decodeFromString<F1State>(it) } }?.takeIf { it.identity == id } } catch (_: Exception) { null }
    private fun save(state: F1State) {
        val dir = directory ?: return
        try { dir.mkdirs(); val target = SportsFile(dir, "${safeId(state.identity)}.json"); val temp = SportsFile(dir, "${target.name}.tmp"); temp.writeSynced(AppJson.encodeToString(state).encodeToByteArray()); if (!temp.renameTo(target)) temp.delete(); dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() } } catch (_: Exception) { }
    }
    private fun safeId(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
    fun match(session: F1Session, state: F1State? = null): Match {
        val side = TeamSide(session.meeting, "F1", "F1")
        return Match(session.id, league, session.start, session.meeting + " · " + session.name, session.meeting, when { state?.completed == true -> GameState.FINAL; state?.active == true -> GameState.LIVE; else -> GameState.PRE }, state?.lap?.let { "${session.name} · Lap $it" } ?: session.name, side, side, venue = session.circuit)
    }
    companion object {
        fun matches(info: JsonElement, selected: F1Session): Boolean = F1Wire.start(info)?.let { abs(it - selected.start) < 3600000 } == true && F1Session.type(info["Name"].stringOrNull() ?: info["Type"].string()) == selected.type && (selected.type != "Practice" || info["Name"].string() == selected.name)
        fun meeting(raw: JsonElement): F1Meeting? {
            val season = raw["season"].string().toIntOrNull() ?: return null; val round = raw["round"].string().toIntOrNull() ?: return null; val name = raw["raceName"].stringOrNull() ?: return null
            val circuit = raw["Circuit"]["circuitName"].string("Circuit")
            val definitions = listOf("FirstPractice" to "Practice 1", "SecondPractice" to "Practice 2", "ThirdPractice" to "Practice 3", "SprintQualifying" to "Sprint Qualifying", "SprintShootout" to "Sprint Qualifying", "Qualifying" to "Qualifying", "Sprint" to "Sprint", "Race" to "Race")
            val sessions = definitions.mapNotNull { (key, label) ->
                if (key == "SprintShootout" && raw["SprintQualifying"]["date"] != null) return@mapNotNull null
                val value = if (key == "Race") raw else raw[key]
                val start = F1Wire.parse(value["date"].string() + "T" + value["time"].string()) ?: return@mapNotNull null
                F1Session("$season.$round.$key", name, circuit, label, start, season, round)
            }.sortedBy { it.start }
            return F1Meeting("$season.$round", name, raw["Circuit"]["Location"]["country"].string(), circuit, sessions)
        }
        fun official(raw: JsonElement, selected: F1Session): F1State? {
            val race = raw["MRData"]["RaceTable"]["Races"][0]
            if (race["season"].int() != selected.season || race["round"].int() != selected.round) return null
            val rows = (race["Results"] ?: race["SprintResults"]).array
            if (rows.isEmpty()) return null
            val drivers = rows.mapNotNull { row -> val p = row["Driver"]; val number = row["number"].stringOrNull() ?: p["permanentNumber"].stringOrNull() ?: p["driverId"].stringOrNull() ?: return@mapNotNull null
                F1Driver(number, listOf(p["givenName"].string(), p["familyName"].string()).joinToString(" ").trim(), p["code"].string(number), row["Constructor"]["name"].string(), position = row["position"].string().toIntOrNull(), gap = row["Time"]["time"].stringOrNull(), bestLap = row["FastestLap"]["Time"]["time"].stringOrNull(), laps = row["laps"].string().toIntOrNull(), classification = row["status"].stringOrNull())
            }.sortedBy { it.position ?: 999 }
            return F1State(selected.id, meeting = selected.meeting, circuit = selected.circuit, name = selected.name, status = "Finalised", drivers = drivers, lap = drivers.firstOrNull()?.laps, updated = nowMillis())
        }
        fun mergeResult(archive: F1State?, official: F1State): F1State {
            if (archive == null || archive.identity != official.identity) return official
            val previous = archive.drivers.associateBy { it.number }
            return archive.copy(drivers = official.drivers.map { row -> previous[row.number]?.copy(position = row.position, classification = row.classification) ?: row }, status = official.status)
        }
    }
}

sealed interface F1TransportEvent
data class F1Connection(val state: String, val attempts: Int, val error: String? = null) : F1TransportEvent
data class F1Activity(val time: Long) : F1TransportEvent
interface F1Transport {
    suspend fun firstSnapshot(): List<F1Update>
    suspend fun run(deliver: suspend (F1TransportEvent) -> Unit)
}
