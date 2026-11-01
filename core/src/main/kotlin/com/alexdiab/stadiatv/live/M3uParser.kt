package com.alexdiab.stadiatv.live

import java.io.BufferedReader
import java.io.StringReader
import java.util.Locale

/**
 * Streaming M3U/M3U8 parser. One pass, one line at a time, so a 30,000-channel playlist never needs a
 * second in-memory copy. Per-stream HTTP headers are collected exactly as the iOS parser does:
 * `#EXTVLCOPT:http-user-agent=` / `http-referrer=` / `http-origin=`, `#KODIPROP:...stream_headers=`,
 * `#EXTHTTP:{json}`, header attributes on `#EXTINF`, and the `url|User-Agent=...` pipe suffix. They apply
 * to the next URL only.
 */
object M3uParser {
    const val MAX_CHANNELS = 30_000

    class Result(val epgUrls: List<String>, val channels: List<Channel>)

    fun parse(text: String, playlist: Playlist): Result = parse(BufferedReader(StringReader(text)), playlist)

    fun parse(reader: BufferedReader, playlist: Playlist, isActive: () -> Boolean = { true }): Result {
        val state = State(playlist)
        while (true) {
            val line = reader.readLine() ?: break
            state.process(line)
            if (state.channels.size >= MAX_CHANNELS || !isActive()) break
        }
        return Result(state.epgUrls.toList(), state.channels)
    }

    private class State(val playlist: Playlist) {
        val channels = ArrayList<Channel>(4096)
        val epgUrls = LinkedHashSet<String>()
        private val groups = HashMap<String, String>()
        private val usedIds = HashSet<String>()

        private var name: String? = null
        private var logo: String? = null
        private var group: String? = null
        private var tvgId: String? = null
        private var tvgName: String? = null
        private var language: String? = null
        private var catchup: CatchupSpec? = null
        private var defaultCatchupMode: String? = null
        private var defaultCatchupDays: String? = null
        private var defaultCatchupSource: String? = null
        private val headers = LinkedHashMap<String, String>()

        fun process(raw: String) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U") -> {
                    defaultCatchupMode = attribute("catchup", line)
                    defaultCatchupDays = attribute("catchup-days", line)
                    defaultCatchupSource = attribute("catchup-source", line) ?: attribute("catchup-template", line)
                    val guide = attribute("x-tvg-url", line) ?: attribute("url-tvg", line)
                    guide?.split(',')?.map { it.trim() }?.filter { it.startsWith("http") }?.forEach(epgUrls::add)
                }
                line.startsWith("#EXTINF") -> extinf(line)
                line.startsWith("#EXTVLCOPT:") -> {
                    val option = line.removePrefix("#EXTVLCOPT:")
                    val equals = option.indexOf('=')
                    if (equals > 0) {
                        val key = option.substring(0, equals)
                        val value = option.substring(equals + 1).trim()
                        // Only header options; VLC transport options such as http-reconnect are ignored.
                        val canonical = StreamHeaders.canonicalName(key)
                        if (value.isNotEmpty() && canonical != null && canonical != key) headers[canonical] = value
                    }
                }
                line.startsWith("#KODIPROP:") -> {
                    val prop = line.removePrefix("#KODIPROP:")
                    val prefix = "inputstream.adaptive.stream_headers="
                    if (prop.lowercase(Locale.US).startsWith(prefix)) headers.putAll(StreamHeaders.parsePairs(prop.substring(prefix.length)))
                }
                line.startsWith("#EXTHTTP:") -> extHttp(line.removePrefix("#EXTHTTP:"))
                line.startsWith("#") || line.isEmpty() -> Unit
                else -> stream(line)
            }
        }

        private fun extinf(line: String) {
            logo = attribute("tvg-logo", line)
            group = attribute("group-title", line)
            tvgId = attribute("tvg-id", line)
            tvgName = attribute("tvg-name", line)
            language = attribute("tvg-language", line)
            catchup = CatchupSpec.m3u(attribute("catchup", line) ?: defaultCatchupMode, attribute("catchup-days", line) ?: defaultCatchupDays,
                attribute("catchup-source", line) ?: attribute("catchup-template", line) ?: defaultCatchupSource)
            val comma = line.lastIndexOf(',')
            name = if (comma >= 0) line.substring(comma + 1).trim().ifEmpty { tvgName } else tvgName
            for (key in HEADER_ATTRIBUTES) {
                val value = headerAttribute(key, line) ?: continue
                StreamHeaders.canonicalName(key)?.let { headers[it] = value }
            }
        }

        private fun extHttp(json: String) {
            runCatching {
                val obj = org.json.JSONObject(json.trim())
                obj.keys().forEach { key ->
                    val value = obj.optString(key).trim()
                    val canonical = StreamHeaders.canonicalName(key)
                    if (value.isNotEmpty() && canonical != null) headers[canonical] = value
                }
            }
        }

        private fun stream(line: String) {
            val (url, pipeHeaders) = StreamHeaders.splitPipeSuffix(line)
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            headers.putAll(pipeHeaders)
            val resolvedName = name?.takeIf { it.isNotEmpty() } ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "Channel ${channels.size + 1}" }
            val groupTitle = group?.takeIf { it.isNotEmpty() }?.let { groups.getOrPut(it) { it } } ?: categorize(resolvedName)
            channels += Channel(
                id = uniqueId(url),
                name = resolvedName,
                streamUrl = url,
                logoUrl = logo?.takeIf { it.isNotEmpty() },
                group = groupTitle,
                playlistId = playlist.id,
                playlistName = playlist.name,
                tvgId = tvgId?.takeIf { it.isNotEmpty() },
                tvgName = tvgName?.takeIf { it.isNotEmpty() },
                language = language?.takeIf { it.isNotEmpty() },
                headers = if (headers.isEmpty()) emptyMap() else LinkedHashMap(headers),
                catchup = catchup,
            )
            name = null; logo = null; group = null; tvgId = null; tvgName = null; language = null; catchup = null
            headers.clear()
        }

        private fun uniqueId(url: String): String {
            var hash = -3750763034362895579L // FNV-1a 64 offset basis
            for (ch in url) { hash = (hash xor ch.code.toLong()) * 1099511628211L }
            var id = java.lang.Long.toHexString(hash)
            var n = 1
            while (!usedIds.add(id)) id = java.lang.Long.toHexString(hash) + "-" + n++
            return id
        }
    }

    private val HEADER_ATTRIBUTES = listOf("user-agent", "http-user-agent", "referer", "referrer", "http-referrer", "http-referer", "origin", "http-origin")

    /** `key="value"` or `key='value'`. */
    internal fun attribute(key: String, line: String): String? {
        var from = 0
        while (true) {
            val index = line.indexOf(key, from, ignoreCase = true)
            if (index < 0) return null
            from = index + key.length
            if (index > 0 && !line[index - 1].isWhitespace()) continue
            var cursor = from
            while (cursor < line.length && line[cursor] == ' ') cursor++
            if (cursor >= line.length || line[cursor] != '=') continue
            cursor++
            while (cursor < line.length && line[cursor] == ' ') cursor++
            if (cursor >= line.length) return null
            val quote = line[cursor]
            if (quote != '"' && quote != '\'') continue
            val end = line.indexOf(quote, cursor + 1)
            if (end < 0) return null
            return line.substring(cursor + 1, end).ifEmpty { null }
        }
    }

    /** Like [attribute] but the key must start a new attribute (preceded by a space), so `user-agent` doesn't match inside `http-user-agent`. */
    private fun headerAttribute(key: String, line: String): String? {
        val marker = " $key=\""
        val start = line.indexOf(marker, ignoreCase = true)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = line.indexOf('"', valueStart)
        if (end < 0) return null
        return line.substring(valueStart, end).trim().ifEmpty { null }
    }

    private fun categorize(name: String): String {
        val value = name.lowercase(Locale.US)
        return when {
            listOf("sport", "espn", "nfl", "nba", "nhl", "mlb").any(value::contains) -> "Sports"
            value.contains("news") -> "News"
            value.contains("movie") || value.contains("cinema") -> "Movies"
            value.contains("kids") || value.contains("cartoon") -> "Kids"
            else -> "Other"
        }
    }
}
