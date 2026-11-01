package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException
import okio.ByteString.Companion.encodeUtf8
import kotlin.concurrent.Volatile
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.net.*
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

/** Score authority is independent of incident/statistic freshness. A failed feed never creates a fresh sample. */
object NativeLaLigaAuthority {
    enum class Owner { Official, FotMob }
    data class Sample(val match: Match, val checkedAt: Long)
    data class State(val official: Sample?, val fotmob: Sample?, val accepted: Sample, val owner: Owner)
    private const val WINDOW = 6 * 3_600_000L
    private val interruptions = setOf("Postponed", "Cancelled", "Abandoned", "Delayed", "Suspended")
    private fun scope(a: Match, b: Match): Boolean {
        val ar = a.nativeReference ?: return false; val br = b.nativeReference ?: return false
        return a.league == NativeLaLigaMapping.league && b.league == a.league && a.key == b.key && ar.provider == "LaLiga" && br.provider == ar.provider &&
            ar.gameId == br.gameId && ar.season == br.season && ar.seasonType == br.seasonType && a.home.teamId == b.home.teamId && a.away.teamId == b.away.teamId
    }
    fun apply(expected: Match, previous: State?, official: Sample? = null, fotmob: Sample? = null, now: Long): State? {
        val ref = expected.nativeReference
        val home = expected.home.teamId.orEmpty(); val away = expected.away.teamId.orEmpty()
        if (expected.league != NativeLaLigaMapping.league || ref?.provider != "LaLiga" || expected.id != "laliga:${ref.gameId}" || NativeLaLigaMapping.rawId(ref.gameId) != ref.gameId ||
            ref.season !in 1900..2200 || ref.seasonType?.matches(Regex("[a-z0-9-]{1,120}")) != true || home == away ||
            !home.startsWith("laliga:") || !away.startsWith("laliga:") || NativeLaLigaMapping.rawId(home) == null || NativeLaLigaMapping.rawId(away) == null) throw IOException("La Liga score identity missing")
        if (previous != null && !scope(expected, previous.accepted.match)) throw IOException("La Liga retained score identity mismatch")
        for (sample in listOfNotNull(official, fotmob)) if (!scope(expected, sample.match) || sample.checkedAt <= 0 || sample.checkedAt > now + 300_000) throw IOException("La Liga score sample identity/time mismatch")
        fun latest(old: Sample?, incoming: Sample?) = if (incoming != null && (old == null || incoming.checkedAt >= old.checkedAt)) incoming else old
        val o = latest(previous?.official, official)
        val baseline = o?.match ?: expected
        // A legitimately rescheduled official fixture invalidates an old live mapping's kickoff window.
        val f = latest(previous?.fotmob, fotmob)?.takeIf { abs(it.match.startMillis - baseline.startMillis) <= WINDOW }
        val prior = previous?.accepted
        val priorOwner = previous?.owner
        val canRetain = prior != null && (prior.match.state == GameState.FINAL || abs(prior.match.startMillis - baseline.startMillis) <= WINDOW)
        val selected: Pair<Sample, Owner> = when {
            o?.match?.state == GameState.FINAL -> o to Owner.Official // Actual official final totals/corrections own the final result.
            prior?.match?.state == GameState.FINAL && priorOwner == Owner.Official -> prior to Owner.Official
            prior?.match?.state == GameState.FINAL -> if (f?.match?.state == GameState.FINAL) f to Owner.FotMob else prior to requireNotNull(priorOwner)
            o?.match?.statusDetail in interruptions -> o!! to Owner.Official
            o?.match?.state == GameState.PRE && now < o.match.startMillis && (!canRetain || prior?.match?.state != GameState.LIVE) -> o to Owner.Official
            f != null && now >= baseline.startMillis && f.match.state in setOf(GameState.LIVE, GameState.FINAL) -> f to Owner.FotMob
            canRetain && priorOwner == Owner.FotMob && prior?.match?.state == GameState.LIVE -> prior to Owner.FotMob
            o != null -> o to Owner.Official
            prior != null -> prior to requireNotNull(priorOwner)
            else -> return null
        }
        return State(o, f, selected.first, selected.second)
    }
    /** Only reported fields: no incident-derived totals, wall-clock minute or default first-half inference. */
    fun fotmob(raw: JsonElement, baseline: Match, mapping: NativeLaLigaReconciliation.Mapping, at: Long): Sample {
        NativeLaLigaReconciliation.validate(raw, mapping, baseline)
        val status = raw["header"]["status"]
        val key = listOf(status["reason"]["shortKey"], status["reason"]["longKey"], status["liveTime"]["shortKey"], status["liveTime"]["longKey"])
            .mapNotNull { it.stringOrNull()?.takeIf(String::isNotBlank) }.firstOrNull().orEmpty().take(100)
        val phase = when (key.lowercase()) {
            "halftime_short", "halftime" -> "Half-time"; "1st_half", "firsthalf" -> "First half"; "2nd_half", "secondhalf" -> "Second half"
            "postponed", "postponed_short" -> "Postponed"; "suspended", "suspended_short" -> "Suspended"; "abandoned", "abandoned_short" -> "Abandoned"
            "delayed", "delayed_short" -> "Delayed"; "cancelled", "cancelled_short" -> "Cancelled"; else -> key
        }
        val cancelled = NativeMlsMapping.boolean(status["cancelled"]) == true
        val finished = NativeMlsMapping.boolean(status["finished"]) == true || NativeMlsMapping.boolean(status["awarded"]) == true
        val started = NativeMlsMapping.boolean(status["started"]) == true
        val state = when { cancelled -> GameState.PRE; finished -> GameState.FINAL; phase in setOf("Postponed", "Cancelled", "Abandoned", "Delayed") -> GameState.PRE; started || phase == "Suspended" -> GameState.LIVE; else -> GameState.PRE }
        fun integer(value: JsonElement?, max: Int) = value.stringOrNull()?.toIntOrNull()?.takeIf { it in 0..max }
        val minute = integer(status["liveTime"]["minute"], 200)
        val added = integer(status["liveTime"]["added"], 99) ?: integer(status["liveTime"]["overtime"], 99)
        val running = state == GameState.LIVE && phase !in interruptions && phase != "Half-time"
        val clock = if (running) reportedClock(status["liveTime"]["short"].stringOrNull(), status["liveTime"]["long"].stringOrNull())
            ?: minute?.let { "$it${added?.takeIf { n -> n > 0 }?.let { n -> "+$n" }.orEmpty()}′" } else null
        val home = integer(raw["header"]["teams"][0]["score"], 100)?.takeIf { state != GameState.PRE }
        val away = integer(raw["header"]["teams"][1]["score"], 100)?.takeIf { state != GameState.PRE }
        val label = when { cancelled -> "Cancelled"; finished -> "Full time"; phase in interruptions -> phase; state == GameState.PRE -> if (key.isNotBlank() && phase == key) key else "Upcoming"; else -> clock ?: phase.ifBlank { "Live" } }
        return Sample(baseline.copy(state = state, statusDetail = label,
            home = baseline.home.copy(score = home?.toString(), isWinner = state == GameState.FINAL && home != null && away != null && home > away),
            away = baseline.away.copy(score = away?.toString(), isWinner = state == GameState.FINAL && home != null && away != null && away > home),
            live = if (state == GameState.LIVE) LiveContext(clock, phase.takeIf(String::isNotBlank)) else LiveContext.EMPTY), at)
    }
    /** Recorded live52′/51:32 fields, including directional marks; never extrapolate from kickoff/halfs. */
    private fun reportedClock(short: String?, long: String?): String? {
        fun clean(value: String?) = value?.takeIf { it.length <= 64 }?.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "")?.trim()
        val reported = clean(short)?.let { Regex("^([0-9]{1,3})(?:\\s*\\+\\s*([0-9]{1,2}))?\\s*['′’]$").matchEntire(it) }
        if (reported != null) {
            val minute = reported.groupValues[1].toIntOrNull()?.takeIf { it in 0..200 }
            val added = reported.groupValues[2].toIntOrNull()?.takeIf { it in 1..99 }
            if (minute != null) return "$minute${added?.let { "+$it" }.orEmpty()}′"
        }
        val seconds = clean(long)?.let { Regex("^([0-9]{1,3}):([0-5][0-9])$").matchEntire(it) } ?: return null
        val minute = seconds.groupValues[1].toIntOrNull()?.takeIf { it in 0..200 } ?: return null
        return "$minute:${seconds.groupValues[2]}"
    }
}
