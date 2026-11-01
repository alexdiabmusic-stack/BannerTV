package com.alexdiab.stadiatv.mobile

import androidx.compose.runtime.*
import com.alexdiab.stadiatv.data.fantasy.*
import com.alexdiab.stadiatv.live.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class PhoneFantasyGuideEvidence(val channelKey: String, val guideId: String?, val programme: Programme, val index: NativeFantasyEventIndex)

/** Visible programme results only; bounded content-keyed cache, two off-main computations at most. */
internal class PhoneFantasyGuideCache(private val compute: (String?, Programme, NativeFantasyEventIndex) -> List<NativeFantasyContextPlayer> = NativeFantasyGuideMatcher::players) {
    private val gate = Semaphore(2)
    private val cache = LinkedHashMap<PhoneFantasyGuideEvidence, List<NativeFantasyContextPlayer>>(128, .75f, true)
    suspend fun players(evidence: PhoneFantasyGuideEvidence): List<NativeFantasyContextPlayer> = withContext(Dispatchers.Default) {
        synchronized(cache) { cache[evidence] } ?: gate.withPermit {
            synchronized(cache) { cache[evidence] } ?: compute(evidence.guideId, evidence.programme, evidence.index).also { result ->
                synchronized(cache) {
                    cache[evidence] = result
                    while (cache.size > 128) cache.remove(cache.keys.first())
                }
            }
        }
    }
}

@Composable
internal fun fantasyProgrammeCount(evidence: PhoneFantasyGuideEvidence, cache: PhoneFantasyGuideCache): Int {
    return fantasyProgrammePlayers(evidence, cache).size
}

@Composable
internal fun fantasyProgrammePlayers(evidence: PhoneFantasyGuideEvidence, cache: PhoneFantasyGuideCache): List<NativeFantasyContextPlayer> {
    if (evidence.index.owner == null || evidence.index.contexts.isEmpty()) return emptyList()
    val result by produceState<Pair<PhoneFantasyGuideEvidence, List<NativeFantasyContextPlayer>>?>(null, evidence) {
        value = evidence to cache.players(evidence)
    }
    // produceState retains its old value until the new coroutine starts: never flash an old badge.
    return result?.takeIf { it.first == evidence }?.second.orEmpty()
}
