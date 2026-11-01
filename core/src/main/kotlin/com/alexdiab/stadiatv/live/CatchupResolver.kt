package com.alexdiab.stadiatv.live

import androidx.compose.runtime.Immutable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Immutable
data class CatchupSpec(val strategy: String, val days: Int = 0, val template: String? = null) {
    companion object {
        fun m3u(mode: String?, days: String?, source: String?): CatchupSpec? {
            val kind = mode?.trim()?.lowercase(Locale.ROOT)
            if (kind in setOf("none", "disabled", "false", "0")) return null
            if (kind.isNullOrBlank() && source.isNullOrBlank()) return null
            return CatchupSpec(if (kind in setOf("1", "true")) "default" else kind.orEmpty().ifBlank { "default" }, days?.toIntOrNull()?.coerceIn(0, 365) ?: 0, source?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/** iOS catch-up semantics, isolated from guide presentation. No URL or credentials belong in UI errors. */
object CatchupResolver {
    data class Availability(val url: String? = null, val reason: String) { val available: Boolean get() = url != null }

    fun resolve(channel: Channel, programme: Programme, now: Long = System.currentTimeMillis(), epgOffsetMinutes: Int = 0): Availability {
        val archive = channel.catchup ?: return Availability(reason = "This provider has not declared catch-up for this channel.")
        val offset = epgOffsetMinutes.toLong() * 60_000L
        val start = programme.startMillis - offset
        val end = programme.endMillis - offset
        if (start < 0 || end <= start) return Availability(reason = "This programme has invalid guide times.")
        if (end > now) return Availability(reason = "Replay becomes available after the programme ends, if your provider archives it.")
        if (archive.days > 0 && now - start > archive.days.coerceAtMost(365) * 86_400_000L) return Availability(reason = "This programme is outside the provider's ${archive.days}-day archive window.")
        val url = when {
            archive.template != null -> template(archive.template, channel.streamUrl, start, end, archive.strategy)
            archive.strategy in setOf("xtream", "xc", "shift", "default") -> xtream(channel.streamUrl, start, end)
            else -> null
        }
        return if (url != null) Availability(url, "Provider-declared replay · availability depends on your subscription.")
        else Availability(reason = "This provider's archive URL format is not supported. Live playback is unchanged.")
    }

    private fun xtream(live: String, start: Long, end: Long): String? {
        val url = live.toHttpUrlOrNull() ?: return null
        // Split encoded segments: an escaped slash in a credential must stay a single segment.
        val parts = url.encodedPathSegments
        val index = parts.indexOfLast { it == "live" }
        if (index < 0 || parts.size != index + 4) return null
        val user = parts[index + 1]; val pass = parts[index + 2]
        val id = parts[index + 3].substringBefore('.')
        if (user.isEmpty() || pass.isEmpty() || !id.matches(Regex("[0-9]+"))) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(start))
        val minutes = ((end - start + 59_999) / 60_000).coerceAtLeast(1)
        val path = (parts.take(index) + listOf("timeshift", user, pass, minutes.toString(), stamp, "$id.m3u8")).joinToString("/", prefix = "/")
        return url.newBuilder().encodedPath(path).fragment(null).build().toString()
    }

    private fun template(source: String, live: String, start: Long, end: Long, strategy: String): String? {
        val seconds = ((end - start) / 1000).coerceAtLeast(1)
        val minutes = (seconds / 60).coerceAtLeast(1)
        val tokens = mapOf("start" to start / 1000, "end" to end / 1000, "utc" to start / 1000, "lutc" to start / 1000, "timestamp" to start / 1000, "duration" to seconds, "durationMinutes" to minutes, "offset" to -minutes)
        var result = source
        tokens.forEach { (key, value) -> result = result.replace("{$key}", value.toString()) }
        if ('{' in result || '}' in result || result.length > 16384) return null
        val base = live.toHttpUrlOrNull() ?: return null
        if (strategy == "append" && (result.startsWith('?') || result.startsWith('&'))) {
            val suffix = result.drop(1)
            result = base.newBuilder().fragment(null).build().toString() + (if (base.encodedQuery == null) "?" else "&") + suffix
        }
        return (result.toHttpUrlOrNull() ?: base.resolve(result))?.takeIf { it.scheme in setOf("http", "https") }?.toString()
    }
}
