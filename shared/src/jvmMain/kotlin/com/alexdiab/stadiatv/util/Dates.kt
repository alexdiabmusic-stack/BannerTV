package com.alexdiab.stadiatv.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * Date helpers that work on every Fire OS level the app supports (API 23+), without java.time.
 * `SimpleDateFormat`'s ISO `X` zone pattern is API 24+, so ISO parsing is done by hand.
 */
object Dates {
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$""")

    /** Parses ISO-8601 timestamps such as `2026-10-14T23:30Z`, with seconds, fractions or `+01:00` offsets. */
    fun parseIso(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = ISO.matchEntire(text) ?: return null
        val (year, month, day, hour, minute, second, fraction, zone) = match.destructured
        val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
        calendar.clear()
        calendar.set(year.toInt(), month.toInt() - 1, day.toInt(),
            hour.ifEmpty { "0" }.toInt(), minute.ifEmpty { "0" }.toInt(), second.ifEmpty { "0" }.toInt())
        var millis = calendar.timeInMillis
        if (fraction.isNotEmpty()) millis += (fraction.padEnd(3, '0').take(3)).toLong()
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val offset = digits.take(2).toInt() * 60 + digits.drop(2).take(2).toInt()
            millis -= sign * offset * 60_000L
        }
        return millis
    }

    // SimpleDateFormat isn't thread-safe, so each thread keeps its own set. (ThreadLocal.withInitial is API 26.)
    private val RFC_822 = object : ThreadLocal<List<SimpleDateFormat>>() {
        override fun initialValue() = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
        ).map { SimpleDateFormat(it, Locale.US).apply { isLenient = false } }
    }

    /** Parses an RSS `pubDate` (RFC 822) or an Atom/ISO timestamp, whichever the feed uses. */
    fun parseFeedDate(raw: String?): Long? {
        // Java reads "BST" as Bangladesh time; in a feed it is always British Summer Time.
        val text = raw?.trim().orEmpty().replace(Regex("""\sBST$"""), " +0100")
        if (text.isEmpty()) return null
        parseIso(text)?.let { return it }
        return RFC_822.get()!!.firstNotNullOfOrNull { format -> runCatching { format.parse(text)?.time }.getOrNull() }
    }

    fun startOfDay(millis: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    fun addDays(millis: Long, days: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return calendar.timeInMillis
    }

    fun isSameDay(left: Long, right: Long): Boolean = startOfDay(left) == startOfDay(right)

    fun isToday(millis: Long) = isSameDay(millis, System.currentTimeMillis())

    /** `yyyyMMdd` in the device's time zone, the format ESPN's scoreboard expects. */
    fun compact(millis: Long): String = format("yyyyMMdd", millis)

    fun format(pattern: String, millis: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val formatter = SimpleDateFormat(pattern, Locale.getDefault())
        formatter.timeZone = zone
        return formatter.format(Date(millis))
    }

    fun time(millis: Long): String = format("h:mm a", millis)

    /** "Today · 7:30 PM", "Tomorrow · 1:00 PM", "Sun, Oct 18 · 4:25 PM". */
    fun kickoff(millis: Long, now: Long = System.currentTimeMillis()): String {
        val time = time(millis)
        return when {
            isSameDay(millis, now) -> "Today · $time"
            isSameDay(millis, addDays(now, 1)) -> "Tomorrow · $time"
            else -> format("EEE, MMM d", millis) + " · " + time
        }
    }

    /** "Mon, Oct 14 · 7:30 PM" style. */
    /** "Thursday, October 15" */
    fun longDay(millis: Long = System.currentTimeMillis()): String = format("EEEE, MMMM d", millis)

    fun dateTime(millis: Long): String = format("EEE, MMM d · h:mm a", millis)

    fun dayHeading(millis: Long, now: Long = System.currentTimeMillis()): String = when {
        isSameDay(millis, now) -> "Today"
        isSameDay(millis, addDays(now, 1)) -> "Tomorrow"
        isSameDay(millis, addDays(now, -1)) -> "Yesterday"
        else -> format("EEEE, MMM d", millis)
    }

    /** "12m ago", "3h ago", "2d ago", else a short date. */
    fun relative(millis: Long?, now: Long = System.currentTimeMillis()): String {
        if (millis == null) return ""
        val seconds = (now - millis) / 1000
        return when {
            seconds < 0 -> format("MMM d", millis)
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 7 * 86_400 -> "${seconds / 86_400}d ago"
            else -> format("MMM d", millis)
        }
    }

    fun greeting(now: Long = System.currentTimeMillis()): String {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    /** `h:mm` countdown for programme progress and "starts in". */
    fun duration(millis: Long): String {
        val totalMinutes = (millis / 60_000).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
