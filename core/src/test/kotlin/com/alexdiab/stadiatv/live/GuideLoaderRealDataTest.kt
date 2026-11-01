package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.CatalogMatcher
import com.alexdiab.stadiatv.lineup.LineupBuilder
import com.alexdiab.stadiatv.lineup.LineupPrefs
import com.alexdiab.stadiatv.lineup.LineupReport
import com.alexdiab.stadiatv.lineup.SavedProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Streams a real provider's whole XMLTV (hundreds of MB) through the loader and reports how long
 * it took, how much memory the result holds and how many channels ended up with a schedule. Skipped without the saved data.
 */
class GuideLoaderRealDataTest {
    @Test
    fun loadsAProvidersWholeGuide() {
        val dir = SavedProvider.dir
        val xml = dir?.let { File(it, "xmltv.xml") }
        assumeTrue("no saved guide", xml != null && xml.exists())
        val dataDir = requireNotNull(dir)
        val guideFile = requireNotNull(xml)
        val builder = LineupBuilder(CatalogMatcher())
        val streams = builder.analyse(SavedProvider.channels(dataDir))
        val sources = listOf(GuideLoader.Source("provider") { guideFile.inputStream() })

        System.gc()
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val started = System.nanoTime()
        val progress = ArrayList<String>()
        val result = GuideLoader(builder).load(sources, streams, setOf("CA", "US"), System.currentTimeMillis(), onProgress = { progress += it })
        val seconds = (System.nanoTime() - started) / 1e9
        System.gc()
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        val lineup = builder.build(streams, result.directory)
        val prefs = LineupPrefs()
        val real = lineup.entries.filter { !it.isFast && (it.country == "CA" || it.country == "US") }
        val guided = real.count { e -> e.pick(prefs).guideId?.let { result.snapshot.has(it) } == true }
        val text = buildString {
            appendLine("loaded ${result.sourcesLoaded} sources in ${"%.1f".format(seconds)} s; sources read: $progress")
            appendLine("kept ${result.snapshot.programmeCount} programmes in ${result.snapshot.schedules.size} schedules, ${result.snapshot.strings.size} strings; heap held ${(after - before) / 1_000_000} MB")
            appendLine("CA/US channels ${real.size}, with programmes in the guide $guided")
            appendLine()
            append(LineupReport.write(lineup, streams))
        }
        File(System.getProperty("lineup.reportDir")!!, "guide-load-report.txt").apply { parentFile?.mkdirs() }.writeText(text)
        assertTrue("guide covers too little: $guided of ${real.size}", guided > real.size * 0.8)
    }
}
