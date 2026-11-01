package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import com.alexdiab.stadiatv.foundation.SportsTime as Dates
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.math.abs

/** NFLGameCenterService/ViewModel recovery: rich drives and lightweight scores have separate freshness. */
internal class NativeFootballLoader(private val now: () -> Long, private val disk: NativeFootballSnapshotCache?,
    private val rich: suspend (NflWeek) -> JsonElement, private val summaries: suspend (NflWeek) -> JsonElement,
    private val names: suspend (Int) -> Map<String, String>) {
    private val lock = Mutex()
    private val memory = LinkedHashMap<String, NativeFootballSnapshotCache.Snapshot>()

    suspend fun cachedMatch(id: String): Match? = lock.withLock { (memory[id] ?: disk?.load(id))?.match() }
    suspend fun cachedDetails(match: Match): GameCentre? = lock.withLock {
        val id = match.nativeReference?.takeIf { it.provider == "NFL" }?.gameId ?: return@withLock null
        (memory[id] ?: disk?.load(id))?.details(match)
    }

    suspend fun load(match: Match, week: NflWeek, nativeId: String?, previous: GameCentre?, force: Boolean = false): GameCentre? = lock.withLock {
        var id = nativeId ?: previous?.takeIf { it.liveMatch?.key == match.key && it.providerStamp?.provider == "NFL" }?.providerStamp?.gameId
        var snapshot = id?.let { memory[it] ?: disk?.load(it) }
        var data = previous?.takeIf { it.providerStamp?.provider == "NFL" && it.providerStamp.gameId == id && it.liveMatch?.key == match.key }
            ?: snapshot?.details(match)
        val requested = now()
        var richTried = false
        val errors = linkedMapOf<String, String>()

        suspend fun save(row: JsonElement, players: Map<String, String>, detailsAt: Long) {
            val key = id ?: return
            val value = NativeFootballSnapshotCache.Snapshot(row, requested, detailsAt, players)
            memory.remove(key)
            if (row.toString().length <= 2 * 1024 * 1024) memory[key] = value
            while (memory.size > 2) memory.remove(memory.keys.first())
            disk?.save(key, value); snapshot = value
        }

        suspend fun full(): GameCentre? {
            richTried = true
            val response = try { withTimeoutOrNull(6_500) { coroutineScope {
                val players = async {
                    try { names(week.season) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { snapshot?.players.orEmpty() }
                }
                rich(week) to players.await()
            } } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            if (response == null) { errors["plays"] = "NFL play-by-play couldn't refresh; any accepted details are retained."; return null }
            val raw = runCatching { NflCalendar.rows(response.first) }.getOrNull()?.filter { row ->
                (id == null || row["id"].string() == id) && NflCalendar.week(row) == week &&
                    NativeFootball.teamMatches(row["awayTeam"], match.away) && NativeFootball.teamMatches(row["homeTeam"], match.home) &&
                    Dates.parseIso(row["time"].string())?.let { abs(it - match.startMillis) < 3 * 3_600_000L } == true
            }?.singleOrNull()
            if (raw == null) { errors["plays"] = "NFL returned inconsistent game details; any accepted data is retained."; return null }
            val rawId = raw["id"].stringOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) } ?: return null
            id = rawId
            val suppliedPlays = raw["driveChart"]["plays"] is JsonArray
            val retained = !suppliedPlays && snapshot?.row?.get("driveChart")?.get("plays") is JsonArray
            val row = if (retained) JsonObject(raw.jsonObject + ("driveChart" to snapshot!!.row["driveChart"]!!)) else raw
            val players = snapshot?.players.orEmpty() + response.second
            val candidate = NativeFootball.parse(row, match, requested, players) ?: return null
            val accepted = NativeFootball.reduce(data, candidate)
            if (accepted.providerStamp != candidate.providerStamp || accepted.liveMatch != candidate.liveMatch) {
                errors["plays"] = "An older NFL update was ignored; accepted game data is unchanged."; return null
            }
            val detailsAt = if (retained) data?.football?.detailsUpdatedAt?.takeIf { it > 0 } ?: snapshot!!.detailsAt else requested
            if (retained) errors["plays"] = "NFL play-by-play is unavailable; saved details are retained."
            val result = accepted.copy(football = accepted.football?.copy(summaryUpdatedAt = requested, detailsUpdatedAt = detailsAt,
                savedSnapshot = false, errors = errors.toMap()))
            save(row, players, detailsAt); data = result
            return result
        }

        val detailsAt = data?.football?.detailsUpdatedAt?.takeIf { it > 0 } ?: snapshot?.detailsAt ?: 0
        if (force || data == null || now() - detailsAt !in 0 until 12_000 || data.liveMatch?.state == GameState.FINAL) full()?.let { return@withLock it }

        val base = snapshot
        if (data != null && base != null && id != null) {
            val response = try { withTimeoutOrNull(5_000) { summaries(week) } }
                catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            val summary = response?.get("data").array.singleOrNull { it["gameId"].string() == id }
            if (summary != null) {
                val row = JsonObject(base.row.jsonObject + ("summary" to summary))
                val candidate = NativeFootball.parse(row, match, requested, base.players)
                val accepted = candidate?.let { NativeFootball.reduce(data, it) }
                if (candidate != null && accepted != null && accepted.providerStamp == candidate.providerStamp && accepted.liveMatch == candidate.liveMatch) {
                    // A score update must not age, erase or replace the prior drive package.
                    val old = data!!
                    var result = accepted.copy(plays = old.plays, playsSource = old.playsSource, drives = old.drives, boxScore = old.boxScore, teamStats = old.teamStats,
                        football = accepted.football?.copy(playsLoaded = old.football?.playsLoaded == true,
                            currentDriveId = NativeFootballPlays.currentDrive(old.drives, accepted.liveMatch?.state == GameState.LIVE, accepted.liveMatch?.statusDetail),
                            summaryUpdatedAt = requested, detailsUpdatedAt = detailsAt, savedSnapshot = false, errors = errors.toMap()))
                    if (result.providerStamp?.final == true && !richTried) {
                        data = result
                        full()?.let { return@withLock it }
                        result = result.copy(football = result.football?.copy(errors = errors.toMap()))
                    }
                    save(row, base.players, detailsAt); return@withLock result
                }
            }
            errors["overview"] = "NFL scores couldn't refresh; any saved score and details are unchanged."
        }
        if (!richTried) full()?.let { return@withLock it }
        data?.let { old -> old.copy(football = old.football?.copy(savedSnapshot = snapshot != null, errors = errors +
            ("overview" to "NFL game data couldn't refresh; the last accepted information is retained."))) }
    }
}
