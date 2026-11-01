package com.alexdiab.stadiatv.live

import java.net.URLDecoder
import java.util.Locale

/**
 * The per-stream HTTP headers IPTV providers expect. Names are normalised to canonical casing so
 * `user-agent`, `User-Agent` and `http-user-agent` all land under one key (mirrors iOS `StreamHTTPHeaders`).
 */
object StreamHeaders {
    const val USER_AGENT = "User-Agent"

    fun canonicalName(raw: String): String? {
        val name = raw.trim().lowercase(Locale.US)
        return when (name) {
            "" -> null
            "user-agent", "http-user-agent", "useragent" -> USER_AGENT
            "referer", "referrer", "http-referer", "http-referrer" -> "Referer"
            "origin", "http-origin" -> "Origin"
            "cookie", "http-cookie" -> "Cookie"
            else -> raw.trim()
        }
    }

    fun userAgent(headers: Map<String, String>): String? =
        headers.entries.firstOrNull { it.key.equals(USER_AGENT, ignoreCase = true) }?.value

    /** Stream headers win over the playlist-level default User-Agent. */
    fun merged(headers: Map<String, String>, defaultUserAgent: String?): Map<String, String> {
        if (userAgent(headers) != null) return headers
        val fallback = defaultUserAgent?.trim().orEmpty()
        return if (fallback.isEmpty()) headers else headers + (USER_AGENT to fallback)
    }

    /** `key=value&key2=value2` (URL-encoded), as in `#KODIPROP` stream_headers and the `url|User-Agent=...` suffix. */
    fun parsePairs(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in text.split('&')) {
            val equals = pair.indexOf('=')
            if (equals <= 0) continue
            val name = canonicalName(decode(pair.substring(0, equals))) ?: continue
            val value = decode(pair.substring(equals + 1)).trim()
            if (value.isNotEmpty()) result[name] = value
        }
        return result
    }

    /** Splits `url|User-Agent=...&Referer=...` into the bare URL and its headers. */
    fun splitPipeSuffix(line: String): Pair<String, Map<String, String>> {
        val pipe = line.indexOf('|')
        if (pipe < 0) return line to emptyMap()
        return line.substring(0, pipe).trim() to parsePairs(line.substring(pipe + 1))
    }

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
