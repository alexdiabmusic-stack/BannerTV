package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.live.ChannelFilterEngine
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Times each stage of turning a real provider's 22,000 streams into a lineup. Skipped without the saved data. */
class LineupPerfTest {
    private fun <T> timed(name: String, out: StringBuilder, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        out.appendLine("%-34s %6d ms".format(name, (System.nanoTime() - start) / 1_000_000))
        return result
    }

    @Test
    fun timeTheStages() {
        val dir = SavedProvider.dir
        assumeTrue("no saved lineup", dir != null)
        dir!!
        val channels = SavedProvider.channels(dir)
        val out = StringBuilder()
        // Warm the JIT the way a real launch would not: run everything twice and report the first (cold) pass.
        val builder = LineupBuilder(CatalogMatcher())
        timed("ChannelFilterEngine.categories x22k", out) { channels.forEach { ChannelFilterEngine.categories(it) } }
        timed("ChannelFilterEngine.index", out) { ChannelFilterEngine.index(channels) }
        val streams = timed("LineupBuilder.analyse", out) { builder.analyse(channels) }
        val directory = LineupFixtureDirectory.load(dir, builder)
        timed("LineupBuilder.build (no guide)", out) { builder.build(streams, null) }
        val lineup = timed("LineupBuilder.build (guide)", out) { builder.build(streams, directory) }
        val linkerStreams = channels.map { com.alexdiab.stadiatv.live.LinkerStream(it.key, com.alexdiab.stadiatv.live.StreamAvailabilityStore.repairBadgeCorruption(it.name), it.group, lineup.guideByChannelKey[it.key]?.id ?: it.tvgId) }
        val linker = timed("StreamLinker build (22k streams)", out) { com.alexdiab.stadiatv.live.StreamLinker(linkerStreams, emptyList()) }
        val event = com.alexdiab.stadiatv.live.LinkerEvent(
            id = "1", league = "mlb", kickoffMillis = System.currentTimeMillis() + 3_600_000L,
            home = com.alexdiab.stadiatv.live.LinkerTeam("Cleveland Guardians", abbr = "CLE"), away = com.alexdiab.stadiatv.live.LinkerTeam("Tampa Bay Rays", abbr = "TB"),
        )
        timed("StreamLinker.link one match", out) { linker.link(event) }
        val settings = com.alexdiab.stadiatv.data.UserSettings(homeCountry = "CA", homeMarket = "ottawa")
        val prefs = LineupPrefs(homeCountry = "CA")
        timed("GuideLogic.build (all chips)", out) {
            repeat(3) { com.alexdiab.stadiatv.ui.livetv.GuideLogic.build(lineup, settings, prefs, com.alexdiab.stadiatv.ui.livetv.GuideFilter.All, com.alexdiab.stadiatv.ui.livetv.GuideOptions(), "", { _, _ -> null to null }, System.currentTimeMillis()) }
        }
        File(System.getProperty("lineup.reportDir")!!, "perf.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
    }
}

/** The directory of a saved provider guide plus any public ones saved beside it, as the report test builds it. */
object LineupFixtureDirectory {
    fun load(dir: File, builder: LineupBuilder): GuideDirectory {
        val merged = LinkedHashMap<String, Pair<MutableList<String>, Int>>()
        val indexes = listOf(File(dir, "xmltv_index.json")) + (File(dir, "extra").listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty())
        for (file in indexes) {
            val index = org.json.JSONObject(file.readText())
            val programmes = index.getJSONObject("programmes")
            val names = index.getJSONObject("channels")
            for (id in names.keys().asSequence().filter { it.isNotEmpty() }) {
                val display = names.getJSONArray(id).let { a -> (0 until a.length()).map(a::getString) }
                val have = merged[id]
                merged[id] = if (have == null) display.toMutableList() to programmes.optInt(id, 0)
                else { have.first.addAll(display.filter { it !in have.first }); have.first to have.second + programmes.optInt(id, 0) }
            }
        }
        return GuideDirectory.build(builder.guideMatcher, merged.map { (id, v) -> GuideListing(id, v.first, v.second) })
    }
}
