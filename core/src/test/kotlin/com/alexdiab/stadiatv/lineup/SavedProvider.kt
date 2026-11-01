package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.live.Channel
import java.io.File
import org.json.JSONArray

/** A real provider's channel list saved as player_api JSON (see `LINEUP_DATA_DIR`), for the tests that need all of it. */
object SavedProvider {
    val dir: File? get() = System.getProperty("lineup.dataDir")?.let(::File)?.takeIf { File(it, "get_live_streams.json").exists() }

    fun channels(dir: File): List<Channel> {
        val categories = JSONArray(File(dir, "get_live_categories.json").readText()).let { array ->
            (0 until array.length()).associate { array.getJSONObject(it).getString("category_id") to array.getJSONObject(it).getString("category_name") }
        }
        val raw = JSONArray(File(dir, "get_live_streams.json").readText())
        return (0 until raw.length()).map { i ->
            val o = raw.getJSONObject(i)
            val id = o.get("stream_id").toString()
            Channel(
                id = id, name = o.getString("name"), streamUrl = "http://example.test/$id.m3u8", logoUrl = null,
                group = categories[o.get("category_id").toString()].orEmpty(), playlistId = "p", playlistName = "Provider",
                tvgId = o.optString("epg_channel_id").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}
