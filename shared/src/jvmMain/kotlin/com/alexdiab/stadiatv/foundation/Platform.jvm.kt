package com.alexdiab.stadiatv.foundation

import kotlinx.coroutines.*
import okio.*

import com.alexdiab.stadiatv.util.Dates
import java.net.URI
import java.util.TimeZone

actual object SportsTime {
    actual fun addLocalDays(at: Long, days: Int, zone: String) = java.util.Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
        timeInMillis = at; add(java.util.Calendar.DATE, days)
    }.timeInMillis
    actual fun parseLocal(raw: String, pattern: String, zone: String): Long? {
        val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone(zone); isLenient = false }
        val pos = java.text.ParsePosition(0)
        return format.parse(raw, pos)?.time?.takeIf { pos.index == raw.length }
    }
    actual fun compact(at: Long) = Dates.compact(at)
    actual fun addDays(at: Long, days: Int) = Dates.addDays(at, days)
    actual fun startOfDay(at: Long) = Dates.startOfDay(at)
    actual fun kickoff(at: Long) = Dates.kickoff(at)
    actual fun parseIso(raw: String?) = Dates.parseIso(raw)
    actual fun format(pattern: String, at: Long, zone: String?): String = Dates.format(pattern, at, zone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault())
}
actual fun safeWebURL(raw: String, httpsOnly: Boolean): Boolean = runCatching {
    val uri = URI(raw)
    (uri.scheme == "https" || (!httpsOnly && uri.scheme == "http")) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0
}.getOrDefault(false)
actual typealias SportsIOException = java.io.IOException
internal actual fun decomposeText(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
internal actual fun basketballAsset(prefix: String, code: String) = "file:///android_asset/team-logos/$prefix/$code." + if (prefix == "nba") "svg" else "png"
internal actual class SportsLock {
    actual fun <T> locked(block: () -> T): T = synchronized(this, block)
}

internal actual val sportsIO get() = Dispatchers.IO
internal actual val sportsDisk get() = FileSystem.SYSTEM

internal actual fun decomposeCompatibilityText(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
