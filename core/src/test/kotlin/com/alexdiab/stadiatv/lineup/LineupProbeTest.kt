package com.alexdiab.stadiatv.lineup

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explains how individual stream names are read and matched. Reads `probe.tsv` (name, group, epg id per line) from the
 * saved lineup folder and writes `probe-out.txt` beside the report. Skipped when there is no such file.
 */
class LineupProbeTest {
    @Test
    fun explainTheProbeNames() {
        val dir = System.getProperty("lineup.dataDir")?.let(::File)
        val input = dir?.let { File(it, "probe.tsv") }
        assumeTrue("no probe file", input != null && input.exists())
        val matcher = CatalogMatcher()
        val out = buildString {
            input!!.readLines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.split('\t')
                val channel = LineupFixture.channel(0, parts[0], parts.getOrElse(1) { "" }, parts.getOrNull(2)?.takeIf { it.isNotBlank() })
                val name = StreamName.parse(channel.name, channel.group)
                val id = matcher.identify(name, channel.tvgId)
                appendLine(parts[0])
                appendLine("   prefix=${name.prefixCountry} fr=${name.french} q=${name.quality} backup=${name.isBackup} event=${StreamName.isEventFeed(channel.name, channel.group)} tokens=${name.tokens} alt=${name.alternates} hints=${name.hints} title='${name.title}'")
                appendLine("   -> catalog=${id.catalog?.id} market='${id.market}' call=${id.callsign} country=${id.feedCountry} variant='${id.variant}' key='${id.nameKey}' group=${id.groupKey}")
            }
        }
        File(System.getProperty("lineup.reportDir")!!, "probe-out.txt").apply { parentFile.mkdirs() }.writeText(out)
    }
}
