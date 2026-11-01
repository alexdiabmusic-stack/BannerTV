package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import com.alexdiab.stadiatv.domain.GameVideo
import com.alexdiab.stadiatv.domain.BaseballProbability
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

/** Independent MLB content/stat surfaces, matching the iOS reducer's native IDs and validation. */
class NativeBaseballExtras(private val http: SportsHttp, private val base: String = "https://statsapi.mlb.com",
    private val snapshots: NativeBaseballExtrasCache? = null, private val now: () -> Long = ::nowMillis) {
    data class Surface<T>(val value: T, val checkedAt: Long, val saved: Boolean)
    private val cooldown = SportsMemo<String, Long>()
    suspend fun highlights(id: String): List<GameVideo> = parseHighlights(get(id, "content", 300_000))
    suspend fun probability(id: String): List<Float> = parseProbability(get(id, "winProbability?fields=about,atBatIndex,homeTeamWinProbability", 20_000))
    suspend fun probabilityPoints(id: String): List<BaseballProbability> = parseProbabilityPoints(get(id, "winProbability?fields=about,atBatIndex,homeTeamWinProbability", 20_000))
    suspend fun highlightsSurface(id: String): Surface<List<GameVideo>> = surface(id, "highlights", "content", ::parseHighlights)
    suspend fun probabilitySurface(id: String): Surface<List<BaseballProbability>> = surface(id, "probability", "winProbability?fields=about,atBatIndex,homeTeamWinProbability", ::parseProbabilityPoints)
    private suspend fun <T> surface(id: String, kind: String, resource: String, parse: (JsonElement) -> T): Surface<T> {
        require(id.matches(Regex("[1-9][0-9]{0,9}")))
        try {
            val at = now(); val raw = get(id, resource, 0)
            if (!NativeBaseballExtrasCache.validPayload(kind, raw)) throw IOException("MLB section response is incomplete")
            val value = parse(raw)
            snapshots?.save(id, kind, NativeBaseballExtrasCache.Saved(raw, at))
            return Surface(value, at, false)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val saved = snapshots?.load(id, kind) ?: throw error
            return Surface(parse(saved.payload), saved.at, true)
        }
    }
    private suspend fun get(id: String, resource: String, ttl: Long): JsonElement {
        require(id.matches(Regex("[0-9]+")))
        val key = resource.substringBefore('?')
        check((cooldown[key] ?: 0) <= now())
        try { return withTimeout(8000) { http.getJson("$base/api/v1/game/$id/$resource", ttlMillis = ttl, retries = 0) } }
        catch (error: TimeoutCancellationException) { cooldown[key] = now() + 60_000; throw IOException("MLB surface timed out", error) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: HttpStatusException) { cooldown[key] = now() + NativeGameInsights.cooldownMillis(error.code, error.retryAfterSeconds); throw error }
        catch (error: Exception) { cooldown[key] = now() + 60_000; throw error }
    }
    companion object {
        fun parseHighlights(raw: JsonElement): List<GameVideo> = raw["highlights"]["highlights"]["items"].array.mapNotNull { item ->
            val id = item["id"].stringOrNull() ?: item["guid"].stringOrNull() ?: return@mapNotNull null
            val url = item["playbacks"].array.firstNotNullOfOrNull { playback -> playback["url"].stringOrNull()?.takeIf { safeWebURL(it) && it.substringBefore('?').substringBefore('#').endsWith(".mp4", true) } } ?: return@mapNotNull null
            val image = item["image"]["cuts"].array.firstNotNullOfOrNull { it["src"].stringOrNull()?.takeIf { value -> safeWebURL(value) } }
            GameVideo("mlb:$id", item["headline"].stringOrNull() ?: item["title"].string("Watch highlight"), url.toString(), image?.toString())
        }.distinctBy { it.id }.take(40)
        fun parseProbability(raw: JsonElement): List<Float> = parseProbabilityPoints(raw).map { it.homeProbability }
        fun parseProbabilityPoints(raw: JsonElement): List<BaseballProbability> = raw.array.mapNotNull { row ->
            val index = row["about"]["atBatIndex"].stringOrNull()?.toIntOrNull()?.takeIf { it in 0..2000 } ?: return@mapNotNull null
            val percent = row["homeTeamWinProbability"].stringOrNull()?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: return@mapNotNull null
            index to percent / 100f
        }.associate { it }.entries.sortedBy { it.key }.map { (index, value) -> BaseballProbability(index, value) }.take(2001)
    }
}
