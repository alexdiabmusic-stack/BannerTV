package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.domain.*
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

@Serializable data class MatchAlertOptions(val enabled: Boolean = false, val leadMinutes: Int = 10, val live: Boolean = true, val closeGames: Boolean = true, val digest: Boolean = false, val digestHour: Int = 8)
data class PlannedMatchAlert(val key: String, val title: String, val body: String, val firesAt: Long, val game: Match?)

/** Pure iOS-style notification planning: seven-day horizon, forty starts and a duplicate-delivery ledger. */
object MatchAlertPlanner {
    const val RETENTION = 3 * 86400000L
    fun plan(matches: List<Match>, settings: UserSettings, options: MatchAlertOptions, ledger: Map<String, Long>, manualGames: Set<String>, now: Long): List<PlannedMatchAlert> {
        if (!options.enabled) return emptyList()
        val candidates = matches.filter { settings.involvesFavorite(it) }.distinctBy { it.key }
        val starts = mutableListOf<PlannedMatchAlert>(); val immediate = mutableListOf<PlannedMatchAlert>()
        for (game in candidates) {
            val sides = game.displaySides
            val label = if (game.league.group in setOf(SportGroup.Racing, SportGroup.Golf)) game.name else sides.joinToString(" vs ") { it.shortName }
            when (game.state) {
                GameState.PRE -> if (game.startMillis > now && game.startMillis <= now + 7 * 86400000L && game.key !in manualGames) {
                    val key = "auto:start:${game.key}"; val fire = game.startMillis - options.leadMinutes.coerceIn(0, 120) * 60000L
                    val alert = PlannedMatchAlert(key, label, "Starts at ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(game.startMillis))} · ${game.league.shortName}", fire.coerceAtLeast(now + 1000), game)
                    if (fire > now) starts += alert else if (key !in ledger) immediate += alert
                }
                GameState.LIVE -> {
                    val liveKey = "auto:live:${game.key}"
                    if (options.live && now - game.startMillis in 0..1800000 && liveKey !in ledger) immediate += PlannedMatchAlert(liveKey, "$label is live", "The game is now live.", now + 1000, game)
                    val closeKey = "auto:close:${game.key}"
                    val margin = when (game.league.group) { SportGroup.Football -> 8; SportGroup.Basketball -> 5; SportGroup.Baseball, SportGroup.Hockey, SportGroup.Soccer -> 1; else -> null }
                    val home = game.home.score?.toIntOrNull(); val away = game.away.score?.toIntOrNull()
                    if (!settings.spoilerFreeMode && options.closeGames && margin != null && home != null && away != null && abs(home - away) <= margin && closeKey !in ledger) immediate += PlannedMatchAlert(closeKey, "Close game: $label", "${game.displayScore} · ${game.statusDetail}", now + 1000, game)
                }
                GameState.FINAL -> Unit
            }
        }
        val digest = if (options.digest) (0..6).mapNotNull { offset ->
            val day = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.DAY_OF_YEAR, offset) }
            val end = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
            val games = candidates.filter { it.state != GameState.FINAL && it.startMillis in day.timeInMillis until end }.sortedBy { it.startMillis }
            val fire = (day.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, options.digestHour.coerceIn(0, 23)) }.timeInMillis
            val key = "auto:digest:" + SimpleDateFormat("yyyyMMdd", Locale.US).format(day.time)
            if (fire <= now || games.isEmpty() || key in ledger) null else PlannedMatchAlert(key, "Today in sports", "${games.size} games · ${games.take(3).joinToString(" · ") { it.shortName }}", fire, null)
        } else emptyList()
        return starts.sortedWith(compareBy<PlannedMatchAlert> { it.firesAt }.thenBy { it.key }).take(40) + immediate.sortedBy { it.key } + digest
    }
}
