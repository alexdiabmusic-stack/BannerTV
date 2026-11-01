package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.util.Dates
import java.util.Calendar
import java.text.Normalizer
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Completed schedule cards still need a full local date/year, not just a Final label. */
fun nflScheduleDate(millis: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(millis))

/** Calendar boundaries, not 24-hour arithmetic: iOS's actual Sat/Sun weekend semantics, including DST. */
fun scheduleRange(selection: String, now: Long): LongRange {
    val day = Dates.startOfDay(now)
    val weekday = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK)
    val offset = when (selection) { "Tomorrow" -> 1; "Weekend" -> if (weekday == Calendar.SUNDAY || weekday == Calendar.SATURDAY) 0 else Calendar.SATURDAY - weekday; else -> 0 }
    val start = Dates.addDays(day, offset)
    val days = if (selection == "Weekend" && weekday != Calendar.SUNDAY) 2 else 1
    return start until Dates.addDays(start, days)
}

fun countdownLabel(starts: Long, now: Long): String {
    val minutes = ((starts - now).coerceAtLeast(0) / 60_000)
    return when { starts <= now -> "Starting now"; minutes < 1 -> "Starts in less than a minute"; minutes < 60 -> "Starts in ${minutes}m"; minutes < 1_440 -> "Starts in ${minutes / 60}h ${minutes % 60}m"; else -> "${minutes / 1_440} days to go" }
}

/** Basketball overview surfaces ties and lead changes, not every made free throw. */
fun scoringTimeline(plays: List<Play>, basketball: Boolean): List<Play> {
    if (!basketball) return plays.filter { it.isScoring }.take(8)
    return com.alexdiab.stadiatv.sports.NativeBasketballPresentation.leadChanges(plays)
}

/** The native soccer family shares event filters/chronology; La Liga must not fall into generic scoring-only UI. */
internal fun phoneUsesNativeSoccerEvents(data: GameCentre): Boolean = data.soccer != null &&
    data.providerStamp?.provider in setOf("Premier League", "MLS", "LaLiga")

fun gameTabs(group: SportGroup, data: GameCentre, nativeShots: Boolean, nativeSoccer: Boolean = false, nativeLaLiga: Boolean = false): List<String> = buildList {
    if (group == SportGroup.Soccer && (nativeSoccer || nativeLaLiga || data.providerStamp?.provider in setOf("Premier League", "MLS", "LaLiga"))) {
        val laLiga = nativeLaLiga || data.providerStamp?.provider == "LaLiga"
        addAll(listOf("Overview", "Timeline", "Lineups", "Team stats"))
        if (laLiga) add("Shots")
        add("Commentary"); add(if (laLiga) "League table" else "Live table")
        return@buildList // Source-specific resources only: EPL/MLS do not advertise a nonexistent shot feed.
    }
    add("Overview"); add(if (group == SportGroup.Soccer) "Events" else if (group == SportGroup.Baseball && data.baseball != null) "At bats" else "Plays")
    add("Box score"); add("Team stats")
    if (nativeShots) add(if (group == SportGroup.Hockey) "Shot map" else "Shot chart")
    if (group == SportGroup.Football && data.drives.isNotEmpty()) add("Drives")
    if (group == SportGroup.Soccer) { add("Lineups"); add("Commentary"); add("Live table") }
}

/** Fractions and percentages are independent efficiency bars; counts use comparative shares. */
fun statBarValues(away: String, home: String): Pair<Float, Float>? {
    fun efficiency(text: String): Float? {
        if (text.endsWith('%')) return text.dropLast(1).toFloatOrNull()?.div(100)?.coerceIn(0f, 1f)
        val parts = text.split('-', '/')
        if (parts.size != 2) return null
        val made = parts[0].toFloatOrNull() ?: return null; val attempts = parts[1].toFloatOrNull() ?: return null
        return if (attempts > 0 && made >= 0 && made <= attempts) made / attempts else null
    }
    val a = efficiency(away); val h = efficiency(home)
    if (a != null && h != null) return a to h
    val av = away.replace(",", "").toFloatOrNull() ?: return null
    val hv = home.replace(",", "").toFloatOrNull() ?: return null
    if (!av.isFinite() || !hv.isFinite() || av < 0 || hv < 0) return null
    return if (av + hv == 0f) 0.5f to 0.5f else av / (av + hv) to hv / (av + hv)
}

fun searchRank(query: String, title: String, fields: String = ""): Int {
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val q = normalize(query); if (q.length < 2) return 0
    val name = normalize(title); val all = "$name ${normalize(fields)}"
    if (q.split(' ').any { !all.contains(it) }) return 0
    return when { name == q -> 200; name.startsWith(q) -> 140; name.contains(q) -> 100; else -> 30 }
}
