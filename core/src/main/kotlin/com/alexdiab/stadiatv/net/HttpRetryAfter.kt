package com.alexdiab.stadiatv.net

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Numeric delta-seconds or the HTTP date accepted by the iOS native clients. Caller owns its retry/cooldown cap. */
internal object HttpRetryAfter {
    fun seconds(raw: String?, now: Long): Long? {
        val value = raw?.trim()?.takeIf { it.length in 1..128 } ?: return null
        if (value.matches(Regex("[0-9]+"))) return value.toLongOrNull()
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
        val pos = ParsePosition(0)
        val date = format.parse(value, pos)?.time?.takeIf { pos.index == value.length } ?: return null
        if (date <= now) return 0
        // Division before rounding avoids overflow even for an unexpectedly distant server date.
        val remaining = date - now
        if (remaining < 0) return null
        return remaining / 1000 + if (remaining % 1000 > 0) 1 else 0
    }
}
