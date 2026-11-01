package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.lineup.GuideDirectory
import com.alexdiab.stadiatv.lineup.GuideListing
import com.alexdiab.stadiatv.lineup.Identity
import com.alexdiab.stadiatv.lineup.LineupBuilder
import com.alexdiab.stadiatv.lineup.ResolvedStream
import java.io.InputStream

/**
 * Reads XMLTV sources into a [GuideSnapshot] and a [GuideDirectory]. Each source is streamed once: its channel list
 * is read first, the channels worth keeping are chosen from it (see [LineupBuilder.guideInterest]), and only their
 * programmes are stored. No Android types, so it runs the same in a unit test against a real provider's guide.
 */
class GuideLoader(
    private val builder: LineupBuilder,
    private val identities: MutableMap<String, Identity> = HashMap(),
) {
    /** One XMLTV URL announced by the playlist. */
    class Source(val name: String, val open: () -> InputStream)

    class Result(
        val snapshot: GuideSnapshot,
        val directory: GuideDirectory,
        val listings: List<GuideListing>,
        val sporty: Set<String>,
        val sourcesLoaded: Int,
    )

    private class Listing(val id: String, val names: MutableList<String>, var programmes: Int)

    fun load(
        sources: List<Source>,
        streams: List<ResolvedStream>,
        countries: Set<String>,
        now: Long,
        isActive: () -> Boolean = { true },
        onProgress: (String) -> Unit = {},
    ): Result {
        val archives = streams.filter { it.channel.catchup != null }
        fun archiveStart(stream: ResolvedStream): Long = now - (stream.channel.catchup?.days?.takeIf { it > 0 } ?: 7).coerceIn(1, 7) * 86_400_000L
        val windowStart = minOf(now - BACK_MILLIS, archives.minOfOrNull(::archiveStart) ?: now)
        val archiveStarts = HashMap<String, Long>()
        fun keepArchive(id: String, start: Long) { val key = id.lowercase(); archiveStarts[key] = minOf(archiveStarts[key] ?: start, start) }
        archives.forEach { stream -> stream.channel.tvgId?.let { keepArchive(it, archiveStart(stream)) } }
        val windowEnd = now + AHEAD_MILLIS
        val descriptionFrom = (now - 2 * 3600_000L) / 60_000L
        val descriptionTo = (now + 8 * 3600_000L) / 60_000L

        val listings = LinkedHashMap<String, Listing>()
        val strings = StringTable()
        val builders = HashMap<String, ScheduleBuilder>()
        var interest = LineupBuilder.GuideInterest(emptySet(), emptySet())
        val sportyIds = HashSet<String>()

        fun directoryNow() = GuideDirectory.build(builder.guideMatcher, listings.values.map { GuideListing(it.id, it.names, it.programmes) }, identities)

        val sink = object : XmltvParser.Sink {
            override fun channel(info: XmltvParser.ChannelInfo): Boolean {
                val entry = listings.getOrPut(info.id.lowercase()) { Listing(info.id, ArrayList(2), 0) }
                info.names.forEach { if (it !in entry.names && entry.names.size < 3) entry.names += it }
                return false
            }

            override fun channelsDone() {
                val directory = directoryNow()
                interest = builder.guideInterest(streams, directory, countries)
                sportyIds += interest.sporty
                if (archives.isNotEmpty()) {
                    val refs = builder.build(streams, directory).guideByChannelKey
                    archives.forEach { stream -> refs[stream.channel.key]?.let { keepArchive(it.id, archiveStart(stream)) } }
                }
            }

            override fun acceptsUnannounced(channelId: String) = channelId.lowercase() in interest.wanted

            override fun programme(programme: Programme) {
                val id = programme.guideId.lowercase()
                // Keep a bounded archive only for streams that advertise it, not another week for every TV channel.
                if (programme.endMillis <= minOf(now - BACK_MILLIS, archiveStarts[id] ?: now)) return
                val minute = programme.startMillis / 60_000L
                val length = ((programme.endMillis - programme.startMillis) / 60_000L).toInt()
                val keepText = id in interest.sporty || minute in descriptionFrom..descriptionTo
                builders.getOrPut(id) { ScheduleBuilder() }.add(
                    minute.toInt(), length, strings.intern(programme.title), strings.intern(programme.subtitle),
                    strings.intern(programme.category), if (keepText) strings.intern(programme.description?.take(DESCRIPTION_KEPT)) else -1,
                )
                listings.getOrPut(id) { Listing(programme.guideId, ArrayList(1), 0) }.programmes++
            }
        }

        var loaded = 0
        for (source in sources) {
            if (!isActive()) break
            onProgress("Reading your provider's TV guide…")
            val ok = com.alexdiab.stadiatv.util.PerfLog.measure("guide source (provider)") {
                runCatching { source.open().use { XmltvParser.parse(it, windowStart, windowEnd, sink, isActive) } }.isSuccess
            }
            if (ok) loaded++
        }

        onProgress("Matching channels…")
        val directory = com.alexdiab.stadiatv.util.PerfLog.measure("guide directory") { directoryNow() }
        val lineup = com.alexdiab.stadiatv.util.PerfLog.measure("guide final lineup") { builder.build(streams, directory) }
        val used = lineup.guideByChannelKey.values.mapTo(HashSet()) { it.id.lowercase() }
        val kept = GuideSnapshot(builders.mapValues { it.value.build() }, strings.toArray()).compact(used)
        return Result(kept, directory, directory.channels.map { GuideListing(it.id, it.names, it.programmeCount) }, sportyIds.filterTo(HashSet()) { it in used }, loaded)
    }

    companion object {
        const val BACK_MILLIS = 6 * 3600_000L
        const val AHEAD_MILLIS = 7 * 24 * 3600_000L
        const val DESCRIPTION_KEPT = 200
    }
}
