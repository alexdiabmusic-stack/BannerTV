package com.alexdiab.stadiatv.lineup

import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the whole pipeline over a saved copy of a real provider's lineup and writes what matched (and what didn't) to
 * a text file. Skipped unless `LINEUP_DATA_DIR` in local.properties points at the saved player_api and guide-index JSON.
 */
class LineupReportTest {
    @Test
    fun reportOnTheSavedProviderLineup() {
        val dir = System.getProperty("lineup.dataDir")?.let(::File)
        val indexFile = dir?.let { File(it, "xmltv_index.json") }
        assumeTrue("no saved lineup", dir != null && File(dir, "get_live_streams.json").exists() && indexFile?.exists() == true)
        val dataDir = requireNotNull(dir)
        val channels = SavedProvider.channels(dataDir)
        // Only the provider's own guide is eligible. Public indexes must not influence the lineup or event matching.
        val merged = LinkedHashMap<String, Pair<MutableList<String>, Int>>()
        val indexes = listOf(requireNotNull(indexFile))
        for (file in indexes) {
            val index = JSONObject(file.readText())
            val programmes = index.getJSONObject("programmes")
            val names = index.getJSONObject("channels")
            for (id in names.keys().asSequence().filter { it.isNotEmpty() }) {
                val display = names.getJSONArray(id).let { a -> (0 until a.length()).map(a::getString) }
                val have = merged[id]
                merged[id] = if (have == null) display.toMutableList() to programmes.optInt(id, 0)
                else { have.first.addAll(display.filter { it !in have.first }); have.first to have.second + programmes.optInt(id, 0) }
            }
        }
        val listings = merged.map { (id, v) -> GuideListing(id, v.first, v.second) }

        val builder = LineupBuilder(CatalogMatcher())
        val directory = GuideDirectory.build(builder.guideMatcher, listings)
        val streams = builder.analyse(channels)
        val lineup = builder.build(streams, directory)

        val out = File(System.getProperty("lineup.reportDir") ?: System.getProperty("java.io.tmpdir"), "lineup-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(LineupReport.write(lineup, streams))
        println("lineup report written to ${out.absolutePath}")
    }
}
