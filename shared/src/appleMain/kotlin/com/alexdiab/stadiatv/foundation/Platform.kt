package com.alexdiab.stadiatv.foundation

import kotlinx.coroutines.*
import okio.*

import platform.Foundation.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.time.Clock

@OptIn(ExperimentalForeignApi::class)
actual object SportsTime {
    actual fun addLocalDays(at: Long, days: Int, zone: String): Long {
        val c = NSCalendar(NSCalendarIdentifierGregorian).apply { timeZone = checkNotNull(NSTimeZone.timeZoneWithName(zone)) }
        return millis(checkNotNull(c.dateByAddingUnit(NSCalendarUnitDay, days.toLong(), date(at), 0u)))
    }
    actual fun parseLocal(raw: String, pattern: String, zone: String): Long? {
        val timeZoneForDate = NSTimeZone.timeZoneWithName(zone) ?: return null
        val formatter = NSDateFormatter().apply {
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
            timeZone = timeZoneForDate
            dateFormat = pattern
            lenient = false
        }
        return formatter.dateFromString(raw)?.takeIf { formatter.stringFromDate(it) == raw }?.let(::millis)
    }
    private fun date(at: Long) = NSDate.dateWithTimeIntervalSince1970(at / 1000.0)
    private fun millis(date: NSDate) = kotlin.math.round(date.timeIntervalSince1970 * 1000.0).toLong()
    private val calendar get() = NSCalendar(NSCalendarIdentifierGregorian).apply { timeZone = NSTimeZone.localTimeZone }
    actual fun format(pattern: String, at: Long, zone: String?): String = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = zone?.let { NSTimeZone.timeZoneWithName(it) } ?: NSTimeZone.localTimeZone
        dateFormat = pattern
    }.stringFromDate(date(at))
    actual fun compact(at: Long) = format("yyyyMMdd", at)
    actual fun addDays(at: Long, days: Int): Long = millis(checkNotNull(calendar.dateByAddingUnit(NSCalendarUnitDay, days.toLong(), date(at), 0u)))
    actual fun startOfDay(at: Long) = millis(calendar.startOfDayForDate(date(at)))
    actual fun kickoff(at: Long): String {
        val today = startOfDay(Clock.System.now().toEpochMilliseconds())
        val label = when (startOfDay(at)) { today -> "Today"; addDays(today, 1) -> "Tomorrow"; else -> format("EEE, MMM d", at) }
        return label + " · " + format("h:mm a", at)
    }
    actual fun parseIso(raw: String?): Long? {
        val text = raw?.trim()?.replace(' ', 'T') ?: return null
        val normalized = ISO.matchEntire(text)?.destructured ?: return null
        val (day, hour, minute, second, fraction, offset) = normalized
        val zone = offset.ifEmpty { "Z" }.let { if (it.length == 5) it.take(3) + ":" + it.takeLast(2) else it }
        return runCatching {
            kotlin.time.Instant.parse(day + "T" + hour.ifEmpty { "00" } + ":" + minute.ifEmpty { "00" } + ":" + second.ifEmpty { "00" } + fraction + zone).toEpochMilliseconds()
        }.getOrNull()
    }
    private val ISO = Regex("""(\d{4}-\d{2}-\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2})(\.\d{1,9})?)?)?(Z|[+-]\d{2}:?\d{2})?""")
}
actual fun safeWebURL(raw: String, httpsOnly: Boolean): Boolean {
    if (raw.any { it.isWhitespace() || it.code < 32 || it == '\\' }) return false
    val url = NSURLComponents.componentsWithString(raw) ?: return false
    return (url.scheme == "https" || (!httpsOnly && url.scheme == "http")) &&
        !url.host.isNullOrBlank() && url.user == null && url.password == null &&
        (url.port?.intValue?.let { it in 1..65535 } ?: true)
}
actual open class SportsIOException : Exception {
    actual constructor(message: String) : super(message)
    actual constructor(message: String, cause: Throwable?) : super(message, cause)
}
internal actual class SportsLock {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }
}
internal actual fun basketballAsset(prefix: String, code: String) = "banner-asset:/" + prefix.uppercase() + "Logo_" + code
internal actual fun decomposeText(value: String): String = (value as NSString).decomposedStringWithCanonicalMapping

internal actual val sportsIO get() = Dispatchers.IO
internal actual val sportsDisk get() = FileSystem.SYSTEM

internal actual fun decomposeCompatibilityText(value: String): String = (value as NSString).decomposedStringWithCompatibilityMapping
