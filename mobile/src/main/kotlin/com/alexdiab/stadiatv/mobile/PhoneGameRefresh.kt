package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.sports.NflWeek
import com.alexdiab.stadiatv.sports.NflSeasonType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Native provider summaries already own their shot projection. Only ESPN summaries need the separate enrichment. */
internal fun phoneRetainSummaryShots(fresh: GameCentre, previous: GameCentre?): GameCentre = if (fresh.providerStamp != null) fresh
    else fresh.copy(shots = previous?.shots.orEmpty(), shotSource = previous?.shotSource)

/** A missing optional soccer graph/ticker must not eventually throttle a healthy live score to five minutes. */
internal fun phoneNativeRefreshFailed(data: GameCentre): Boolean = data.football?.errors?.isNotEmpty() == true ||
    data.basketball?.errors?.isNotEmpty() == true || data.soccer?.let { soccer ->
        if (data.providerStamp?.provider == "LaLiga") soccer.recoverableCoreFailure else soccer.errors.isNotEmpty()
    } == true

internal fun phoneNflWeek(match: Match, data: GameCentre? = null): NflWeek? {
    if (match.league.path != "football/nfl") return null
    val detail = data?.takeIf { it.providerStamp?.provider == "NFL" }?.football
    val reference = match.nativeReference?.takeIf { it.provider == "NFL" }
    val season = detail?.season ?: reference?.season ?: return null
    val type = NflSeasonType.entries.firstOrNull { it.name == (detail?.seasonType ?: reference?.seasonType) } ?: return null
    return NflWeek(season, type, detail?.week ?: reference?.week ?: return null).takeIf { it.valid }
}

internal fun phoneNflWeekLabel(week: NflWeek) = "${week.season} · ${week.type.label} · Week ${week.number}"

internal fun phoneNflLiveDataDelayed(match: Match, data: GameCentre, now: Long): Boolean =
    match.league.path == "football/nfl" && match.state == GameState.LIVE && data.providerStamp?.provider == "NFL" &&
        data.football?.summaryUpdatedAt?.let { it > 0 && now - it > 30_000 } == true

/** NFLGameCenterViewModel intervals/backoff. Final games reconcile once, then stop automatic polling. */
internal fun phoneGamePollDelay(match: Match, now: Long, failures: Int = 0, data: GameCentre? = null): Long? {
    if (match.league.path in setOf("soccer/eng.1", "soccer/usa.1", "soccer/esp.1")) {
        val status = match.statusDetail.lowercase().replace("-", "").replace(" ", "")
        if (match.state == GameState.FINAL) {
            // A failed first reconnect must not strand a final game on its saved score forever.
            // Missing supplemental commentary/lineups alone is not a reason to keep polling a final.
            val coreFailure = data?.soccer?.let { soccer ->
                if (match.league.path == "soccer/esp.1") soccer.recoverableCoreFailure
                else soccer.errors.keys.any { it in setOf("overview", "score") }
            } ?: true
            return if (failures > 0 && coreFailure) ((1L shl failures.coerceIn(1, 5)) * 10_000).coerceIn(60_000, 300_000) else null
        }
        if (status in setOf("cancelled", "canceled", "postponed", "abandoned")) return null
        val base = when {
            status in setOf("halftime", "ht") -> 25_000L
            status in setOf("delayed", "suspended") -> 30_000L
            match.state == GameState.LIVE -> 10_000L
            match.startMillis - now <= 900_000 -> 25_000L
            match.startMillis - now <= 3_600_000 -> 60_000L
            else -> 300_000L
        }
        return maxOf(base, if (failures > 0) ((1L shl failures.coerceIn(1, 5)) * 10_000).coerceAtMost(300_000) else 0)
    }
    if (match.league.path in setOf("basketball/nba", "basketball/wnba")) {
        if (match.state == GameState.FINAL || match.statusDetail.lowercase() in setOf("cancelled", "canceled", "postponed")) return null
        val seconds = com.alexdiab.stadiatv.sports.NativeBasketballDuration.clockSeconds(match.live.clock)
        val detail = data?.basketball
        val boundary = data?.plays?.firstOrNull()?.type?.lowercase()?.let { it.startsWith("period") && "end" in it } == true || seconds == 0.0
        val base = when {
            match.statusDetail.equals("Halftime", true) -> 60_000L
            match.statusDetail.lowercase() in setOf("delayed", "suspended") -> 30_000L
            match.state == GameState.LIVE && boundary -> 20_000L
            match.state == GameState.LIVE && (detail?.period ?: match.live.period?.toIntOrNull() ?: 0) >= (detail?.regulationPeriods ?: 4) && seconds != null && seconds <= 120 -> 8000L
            match.state == GameState.LIVE -> 12_000L
            match.startMillis - now > 3_600_000 -> 300_000L
            else -> 60_000L
        }
        return maxOf(base, if (failures > 0) ((1L shl failures.coerceIn(1, 5)) * 10_000).coerceAtMost(300_000) else 0)
    }
    if (match.league.path != "football/nfl") return when (match.state) {
        GameState.LIVE -> 15_000L; GameState.FINAL -> 300_000L; else -> 60_000L
    }
    if (match.state == GameState.FINAL || match.statusDetail.lowercase() in setOf("cancelled", "canceled", "postponed")) return null
    val base = when {
        match.statusDetail.equals("Halftime", true) -> 25_000L
        match.statusDetail.lowercase() in setOf("delayed", "suspended") -> 45_000L
        match.state == GameState.LIVE -> 6000L
        match.startMillis - now > 1_800_000 -> 180_000L
        else -> 45_000L
    }
    val backoff = if (failures > 0) ((1L shl failures.coerceIn(1, 5)) * 10_000).coerceAtMost(300_000) else 0
    return maxOf(base, backoff)
}

/** Apply the limit on EVERY mutation, including late native/extras responses, not only ESPN load starts. */
internal class PhoneGameSnapshotStore(private val capacity: Int = 8, private val protected: () -> Set<String> = { emptySet() }) {
    private val state = MutableStateFlow<Map<String, PhoneGameSnapshot>>(emptyMap())
    private val accessOrder = mutableListOf<String>()
    val value get() = state.value
    fun asStateFlow() = state.asStateFlow()
    @Synchronized fun touch(key: String) { if (key in state.value) { accessOrder.remove(key); accessOrder.add(key) } }
    @Synchronized fun update(transform: (Map<String, PhoneGameSnapshot>) -> Map<String, PhoneGameSnapshot>) {
        val keep = protected().take(capacity).toSet()
        val next = LinkedHashMap(transform(state.value))
        // Map equality ignores iteration order; StateFlow must not be used as the access-order ledger.
        accessOrder.retainAll(next.keys)
        next.keys.filterNot { it in accessOrder }.forEach(accessOrder::add)
        while (next.size > capacity) {
            val victim = accessOrder.firstOrNull { it !in keep } ?: accessOrder.first()
            next.remove(victim); accessOrder.remove(victim)
        }
        state.value = next
    }
    init { require(capacity > 0) }
}
