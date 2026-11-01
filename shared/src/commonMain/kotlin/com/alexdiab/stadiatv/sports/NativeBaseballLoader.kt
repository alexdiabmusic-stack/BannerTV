package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** MLBGameCenterService port: recover each native surface when the combined feed is unavailable.
 * Each accepted section passes the mapper/reducer independently; stale plays cannot veto a newer score.
 * Only successful network responses advance a section's checked time (no re-aged HTTP memory TTL). */
internal class NativeBaseballLoader(private val http: SportsHttp, private val base: String, private val now: () -> Long,
    private val disk: NativeBaseballSnapshotCache?) {
    private val mutex = Mutex()
    private val memory = LinkedHashMap<String, NativeBaseballSnapshotCache.Snapshot>()
    private val cooldown = SportsMemo<String, Long>()

    suspend fun cachedMatch(id: String): Match? = mutex.withLock {
        if (!id.matches(Regex("[1-9][0-9]{0,9}"))) return@withLock null
        (memory[id] ?: disk?.load(id))?.game()
    }

    suspend fun load(id: String, match: Match, previous: GameCentre?): GameCentre? = mutex.withLock {
        val saved = memory[id] ?: disk?.load(id)
        val recovered = saved?.details(match)
        var result = previous?.takeIf { it.providerStamp?.provider == "MLB" && it.providerStamp.gameId == id }
            ?.takeIf { it.providerStamp!!.requestedAt >= (recovered?.providerStamp?.requestedAt ?: 0) } ?: recovered
        var root = saved?.root ?: seed(id, match)
        var times = result?.baseball?.surfaceUpdatedAt.orEmpty()
        val requested = now()
        val errors = linkedMapOf<String, String>()
        var accepted = false

        fun apply(key: String, candidateRoot: JsonElement) {
            val candidate = NativeBaseball.parse(candidateRoot, match, requested)
            if (candidate == null) { errors[key] = "MLB returned inconsistent $key information."; return }
            val reduced = NativeBaseball.reduce(result, candidate)
            if (reduced.providerStamp != candidate.providerStamp || reduced.baseball != candidate.baseball || reduced.liveMatch != candidate.liveMatch) {
                errors[key] = "An older MLB $key update was ignored; the last accepted data is retained."; return
            }
            root = candidateRoot; result = reduced; times = times + (key to requested); accepted = true
        }

        val feedResult = fetch("feed", "/api/v1.1/game/$id/feed/live")
        val feed = feedResult.getOrNull()?.takeIf { raw -> raw["gamePk"].string() == id && NativeBaseballProvider.gameFromFeed(raw) != null && NativeBaseball.parse(raw, match, requested) != null }
        if (feed != null) {
            apply("overview", patch(root, feed["gameData"], feed["metaData"]))
            for ((key, field) in listOf("linescore" to "linescore", "plays" to "plays", "boxscore" to "boxscore")) {
                val value = feed["liveData"][field]
                if (validSurface(key, value, root)) apply(key, patch(root, surface = field to value!!))
                else errors[key] = "MLB $key information is unavailable; any saved section is retained."
            }
        } else {
            // A legacy ESPN seed has no native team IDs. Do not persist guessed MLB identities.
            if (saved == null && match.nativeReference?.provider != "MLB") return@withLock result
            errors["feed"] = "The combined MLB feed couldn't refresh. Independent sections are checked separately."
            val resources = coroutineScope {
                listOf(
                    "overview" to ("/api/v1.1/game/$id/feed/live" to true),
                    "linescore" to ("/api/v1/game/$id/linescore" to false),
                    "plays" to ("/api/v1/game/$id/playByPlay" to false),
                    "boxscore" to ("/api/v1/game/$id/boxscore" to false),
                ).map { (key, request) -> async { key to fetch(key, request.first, request.second) } }.awaitAll().toMap()
            }
            for (key in listOf("overview", "linescore", "plays", "boxscore")) {
                val value = resources[key]?.getOrNull()
                if (key == "overview") {
                    if (value?.get("gamePk").string() == id && value["gameData"]["status"] is JsonObject) {
                        val data = JsonObject(root["gameData"].obj.orEmpty() + ("status" to value["gameData"]["status"]!!))
                        apply(key, patch(root, data, value["metaData"]))
                    } else errors[key] = "MLB status couldn't refresh; any saved status is retained."
                } else if (validSurface(key, value, root)) apply(key, patch(root, surface = key to value!!))
                else errors[key] = "MLB $key couldn't refresh; any saved section is retained."
            }
        }
        val data = result ?: return@withLock null
        val final = data.copy(baseball = data.baseball?.copy(surfaceUpdatedAt = times, errors = errors,
            savedSnapshot = recovered != null && (!accepted || times.values.any { it < requested })))
        if (accepted) {
            val snapshot = NativeBaseballSnapshotCache.Snapshot(root, requested, times)
            // Full play feeds are sizeable JSON trees; never retain an unbounded history on low-RAM TVs.
            memory.remove(id)
            if (root.toString().length <= 2 * 1024 * 1024) memory[id] = snapshot
            while (memory.size > 2) memory.remove(memory.keys.first())
            disk?.save(id, snapshot)
        }
        final
    }

    private suspend fun fetch(key: String, path: String, statusOnly: Boolean = false): Result<JsonElement> {
        if ((cooldown[key] ?: 0) > now()) return Result.failure(IOException("MLB section cooldown"))
        val url = requestURL(base.trimEnd('/') + path, if (statusOnly) mapOf("fields" to "gamePk,metaData,timeStamp,gameData,status,abstractGameState,codedGameState,detailedState,statusCode,abstractGameCode") else emptyMap())
        return try { Result.success(withTimeout(5_000) { http.getJson(url, retries = 0) }) }
        catch (_: TimeoutCancellationException) { cooldown[key] = now() + 60_000; Result.failure(IOException("MLB section timed out")) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            cooldown[key] = now() + if (error is HttpStatusException) NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds) else 60_000
            Result.failure(error)
        }
    }

    private fun validSurface(key: String, value: JsonElement?, root: JsonElement): Boolean {
        if (value !is JsonObject) return false
        return when (key) {
            "linescore" -> value["innings"] is JsonArray || value["teams"] is JsonObject
            "plays" -> value["allPlays"] is JsonArray
            "boxscore" -> value["teams"] is JsonObject && listOf("away", "home").all { side ->
                val actual = value["teams"][side]["team"]["id"].stringOrNull()
                actual != null && actual == root["gameData"]["teams"][side]["id"].string()
            }
            else -> false
        }
    }

    private fun patch(root: JsonElement, data: JsonElement? = null, metadata: JsonElement? = null, surface: Pair<String, JsonElement>? = null) = JsonObject(root.obj.orEmpty().toMutableMap().apply {
        if (data != null) put("gameData", data)
        if (metadata != null) put("metaData", metadata)
        if (surface != null) put("liveData", JsonObject(root["liveData"].obj.orEmpty() + surface))
    })

    private fun seed(id: String, match: Match): JsonElement = buildJsonObject {
        put("gamePk", id)
        put("gameData", buildJsonObject {
            put("datetime", buildJsonObject { put("dateTime", SportsTime.format("yyyy-MM-dd'T'HH:mm:ss'Z'", match.startMillis, "UTC")) })
            put("status", buildJsonObject { put("detailedState", match.statusDetail); put("abstractGameState", when (match.state) { GameState.FINAL -> "Final"; GameState.LIVE -> "Live"; else -> "Preview" }) })
            put("teams", buildJsonObject { for ((key, side) in listOf("away" to match.away, "home" to match.home)) put(key, buildJsonObject {
                side.teamId?.removePrefix("mlb:")?.let { put("id", it) }; put("name", side.displayName); put("abbreviation", side.abbreviation)
            }) })
            match.venue?.let { put("venue", buildJsonObject { put("name", it) }) }
        })
    }
}
