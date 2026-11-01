package com.alexdiab.stadiatv.lineup

import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.live.Channel

/** How a channel's schedule was found, so the app can tell a sure thing from a fallback. */
enum class GuideSource {
    /** The id the provider attached to the stream itself. */
    Provider,

    /** The id the provider attached to another quality of the same channel (the stream's own was missing or wrong). */
    Sibling,

    /** Found by what the channel is, in the guide's channel list. */
    Matched,

    /** The network's schedule from a sister station in the same time zone: right for the programmes, not for local news. */
    Network,

    /** The same brand's feed from the other country, which does have a schedule; the pick plays that feed so the two agree. */
    Twin,

    /** The East-coast feed's schedule moved three hours: the West feed shows the same programmes later. */
    Shifted,

    /** Taken from another feed of the same channel (the Canadian feed borrowing the US feed's schedule). */
    Alternate,
    None,
}

/** The viewer's preferences that decide which stream plays when a channel has several. */
@Immutable
class LineupPrefs(
    /** HD (and FHD) before standard definition. Off prefers standard definition, for slow connections. */
    val preferHd: Boolean = true,
    /** Prefer 4K streams when there are any. Off keeps them behind 1080p, which Fire TV sticks play comfortably. */
    val preferUhd: Boolean = false,
    /** The viewer's country ("CA" or "US"): its feed is preferred when both have a schedule. */
    val homeCountry: String = "US",
)

@Immutable
class ResolvedStream(
    val channel: Channel,
    val name: StreamName,
    val identity: Identity,
    val isEvent: Boolean,
    val isFast: Boolean = false,
    /** The provider-independent categories read from the channel's name and group ("Sports", "Kids", "Adult" ...). */
    val categories: Set<String> = emptySet(),
) {
    val quality: Quality get() = name.quality
    val isAdult: Boolean get() = "Adult" in categories
}

/** One provider feed of a channel: all the qualities of it (HD, FHD, SD ...) share one schedule. */
@Immutable
class Feed(
    val identity: Identity,
    val streams: List<ResolvedStream>,
    val guideId: String?,
    val guideSource: GuideSource,
    /** Minutes to add to the guide's times (a West feed airs its East feed's programmes three hours later). */
    val guideOffsetMinutes: Int = 0,
)

/** The stream chosen to play for a channel and the guide that goes with it. */
@Immutable
class Pick(val stream: ResolvedStream, val guideId: String?, val guideSource: GuideSource, val guideOffsetMinutes: Int, val feed: Feed) {
    val channel: Channel get() = stream.channel
}

/**
 * One channel in the guide: a catalogue channel (in one local market) or an uncatalogued provider channel, with every
 * feed and quality the provider has for it.
 */
@Immutable
class LineupEntry(
    val key: String,
    val catalog: CatalogChannel?,
    val name: String,
    val market: String,
    /** "CA" or "US" for the channel's own country, "" when unknown or international. */
    val country: String,
    val language: String,
    val genres: Set<Genre>,
    val feeds: List<Feed>,
) {
    val tier: Tier get() = catalog?.tier ?: Tier.Optional
    val hasGuide: Boolean get() = feeds.any { it.guideId != null }
    val streamCount: Int get() = feeds.sumOf { it.streams.size }
    val isLocalStation: Boolean get() = catalog?.hasLocalStations == true && market.isNotEmpty()
    val isFast: Boolean get() = Genre.Fast in genres

    /** The other country's channel of the same name when this one has no schedule anywhere; set once while the lineup is built. */
    var twin: LineupEntry? = null
        internal set

    /**
     * Every stream of this channel in the order to try them: the pick first, then the rest of its feed (so the other
     * quality), then the other feeds, and backup copies last. Used to fail over when a stream stops responding.
     */
    fun streamsInOrder(prefs: LineupPrefs): List<ResolvedStream> {
        val order = Quality.order(prefs.preferHd, prefs.preferUhd)
        val feeds = feeds.sortedWith(
            compareByDescending<Feed> { it.guideId != null }
                .thenByDescending { it.identity.feedCountry == prefs.homeCountry }
                .thenComparator { a, b -> order.compare(b.streams.maxOf { it.quality }, a.streams.maxOf { it.quality }) },
        )
        val sorted = feeds.flatMap { feed -> feed.streams.sortedWith(compareBy<ResolvedStream> { it.name.isBackup }.thenComparator { a, b -> order.compare(b.quality, a.quality) }) }
        return sorted.filterNot { it.name.isBackup } + sorted.filter { it.name.isBackup }
    }

    /** The stream to play: the feed with a schedule first, then the viewer's own country's feed, then the best picture. */
    fun pick(prefs: LineupPrefs): Pick {
        val order = Quality.order(prefs.preferHd, prefs.preferUhd)
        fun bestStream(feed: Feed): ResolvedStream =
            feed.streams.sortedWith(compareBy<ResolvedStream> { it.name.isBackup }.thenComparator { a, b -> order.compare(b.quality, a.quality) }).first()
        val feed = feeds.sortedWith(
            compareByDescending<Feed> { it.guideId != null }
                .thenByDescending { it.identity.feedCountry == prefs.homeCountry }
                .thenComparator { a, b -> order.compare(bestStream(b).quality, bestStream(a).quality) },
        ).first()
        val stream = bestStream(feed)
        if (feed.guideId != null) return Pick(stream, feed.guideId, feed.guideSource, feed.guideOffsetMinutes, feed)
        // No schedule for this feed: borrow another feed's, but only if one exists.
        val donor = feeds.firstOrNull { it.guideId != null }
        if (donor == null) {
            // Nothing for this channel anywhere: play its twin's feed, whose schedule we do have.
            twin?.takeIf { it.hasGuide }?.pick(prefs)?.let { return Pick(it.stream, it.guideId, GuideSource.Twin, it.guideOffsetMinutes, it.feed) }
        }
        return Pick(stream, donor?.guideId, if (donor != null) GuideSource.Alternate else GuideSource.None, donor?.guideOffsetMinutes ?: 0, feed)
    }
}

/** Where a stream's schedule comes from: a guide channel, shifted by [offsetMinutes]. */
@Immutable
class GuideRef(val id: String, val offsetMinutes: Int, val source: GuideSource)

@Immutable
class Lineup(val entries: List<LineupEntry>, val revision: Int = counter.incrementAndGet()) {
    private val byChannelKey: Map<String, LineupEntry> = buildMap {
        for (entry in this@Lineup.entries) for (feed in entry.feeds) for (stream in feed.streams) put(stream.channel.key, entry)
    }

    fun entryFor(channelKey: String): LineupEntry? = byChannelKey[channelKey]

    /** The schedule for every stream in the lineup (a stream whose own feed has none borrows its channel's). */
    val guideByChannelKey: Map<String, GuideRef> = buildMap {
        for (entry in this@Lineup.entries) {
            val donor = entry.feeds.firstOrNull { it.guideId != null }
            for (feed in entry.feeds) {
                val source = if (feed.guideId != null) feed else donor ?: continue
                val ref = GuideRef(source.guideId!!, source.guideOffsetMinutes, if (feed.guideId != null) feed.guideSource else GuideSource.Alternate)
                for (stream in feed.streams) put(stream.channel.key, ref)
            }
        }
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicInteger()
        val EMPTY = Lineup(emptyList(), 0)
    }
}
