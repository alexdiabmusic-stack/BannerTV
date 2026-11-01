package com.alexdiab.stadiatv.live

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader
import java.net.URLEncoder

/** Parses the Xtream Codes `player_api.php` responses from files already downloaded to disk. */
object XtreamLoader {
    /** Percent-encodes one URL path segment, so `/ ? # %` in a username or password can't break the stream URL. */
    fun pathSegment(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun apiUrl(host: String, credentials: XtreamCredentials, action: String): String =
        "$host/player_api.php?username=${URLEncoder.encode(credentials.username, "UTF-8")}" +
            "&password=${URLEncoder.encode(credentials.password, "UTF-8")}&action=$action"

    fun epgUrl(host: String, credentials: XtreamCredentials): String =
        "$host/xmltv.php?username=${URLEncoder.encode(credentials.username, "UTF-8")}" +
            "&password=${URLEncoder.encode(credentials.password, "UTF-8")}"

    fun parseCategories(file: File): Map<String, String> {
        val result = HashMap<String, String>()
        runCatching {
            JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                while (reader.hasNext() && result.size < 5_000) {
                    var id = ""
                    var name = ""
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "category_id" -> id = nextString(reader)
                            "category_name" -> name = nextString(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    if (id.isNotEmpty()) result[id] = name
                }
            }
        }
        return result
    }

    fun parseStreams(file: File, playlist: Playlist, credentials: XtreamCredentials, categories: Map<String, String>, isActive: () -> Boolean = { true }): List<Channel> {
        val result = ArrayList<Channel>(4096)
        val user = pathSegment(credentials.username)
        val pass = pathSegment(credentials.password)
        val host = playlist.host
        val groups = HashMap<String, String>()
        val headers = if (playlist.userAgent.isNotBlank()) mapOf(StreamHeaders.USER_AGENT to playlist.userAgent) else emptyMap()
        JsonReader(InputStreamReader(file.inputStream().buffered(), Charsets.UTF_8)).use { reader ->
            reader.beginArray()
            while (reader.hasNext() && result.size < M3uParser.MAX_CHANNELS && isActive()) {
                var streamId = "0"
                var name = "Channel"
                var icon = ""
                var epgId = ""
                var categoryId = ""
                var archive = false
                var archiveDays = 0
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stream_id" -> streamId = nextString(reader)
                        "name" -> name = nextString(reader)
                        "stream_icon" -> icon = nextString(reader)
                        "epg_channel_id" -> epgId = nextString(reader)
                        "category_id" -> categoryId = nextString(reader)
                        "tv_archive" -> archive = nextString(reader) in setOf("1", "true")
                        "tv_archive_duration" -> archiveDays = nextString(reader).toIntOrNull()?.coerceIn(0, 365) ?: 0
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val category = categories[categoryId]?.let { groups.getOrPut(it) { it } } ?: "Other"
                result += Channel(
                    id = streamId,
                    name = name.ifEmpty { "Channel" },
                    streamUrl = "$host/live/$user/$pass/$streamId.m3u8",
                    logoUrl = icon.ifEmpty { null },
                    group = category,
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    tvgId = epgId.ifEmpty { null },
                    headers = headers,
                    xtreamStreamId = streamId.toIntOrNull(),
                    xtreamCategoryId = categoryId.ifEmpty { null },
                    catchup = if (archive) CatchupSpec("xtream", archiveDays) else null,
                )
            }
        }
        return result
    }

    private fun nextString(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); "" }
        JsonToken.NUMBER, JsonToken.STRING -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> { reader.skipValue(); "" }
    }
}
