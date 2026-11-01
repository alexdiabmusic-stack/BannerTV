package com.alexdiab.stadiatv.lineup

import androidx.compose.runtime.Immutable

/** A channel as announced in an XMLTV source, before we know what it is. */
class GuideListing(val id: String, val names: List<String>, val programmeCount: Int)

/** One channel as the XMLTV guide describes it, with what we worked out it is and how much schedule it has. */
@Immutable
class GuideChannel(
    val id: String,
    val names: List<String>,
    val identity: Identity,
    /** Programmes inside the loaded time window; zero means the channel is listed but empty. */
    val programmeCount: Int,
    /** Comparable name keys (from the id and display names) for channels outside the catalogue. */
    val keys: Set<String>,
)

/**
 * Every channel in the guide sources, indexed so a stream can find its schedule by identity as well as by the id the
 * provider attached to it. Built once per guide load.
 */
class GuideDirectory(val channels: List<GuideChannel>) {
    private val byId: Map<String, GuideChannel> = channels.associateBy { it.id.lowercase() }
    private val byCatalog: Map<String, List<GuideChannel>> = channels.filter { it.identity.catalog != null }.groupBy { it.identity.catalog!!.id }
    private val byKey: Map<String, List<GuideChannel>>

    init {
        val keys = HashMap<String, MutableList<GuideChannel>>()
        for (channel in channels) for (key in channel.keys) keys.getOrPut(key) { ArrayList(1) } += channel
        byKey = keys
    }

    operator fun get(id: String?): GuideChannel? = id?.lowercase()?.let(byId::get)

    /** Guide channels that could be this identity's schedule, best first. Only channels that have programmes. */
    fun candidatesFor(identity: Identity): List<GuideChannel> {
        val pool = identity.catalog?.let { byCatalog[it.id] } ?: byKey[identity.nameKey].orEmpty()
        return pool
            .filter { it.programmeCount > 0 && identity.sameStationAs(it.identity) }
            .sortedWith(
                compareByDescending<GuideChannel> { identity.callsign != null && identity.callsign == it.identity.callsign }
                    .thenByDescending { it.identity.feedCountry == identity.feedCountry }
                    .thenByDescending { overlap(identity, it.identity) }
                    .thenByDescending { it.programmeCount },
            )
    }

    /** A schedule borrowed from a sister station, and how far to move its times. */
    class Borrowed(val channel: GuideChannel, val offsetMinutes: Int)

    /**
     * For a local station with no schedule: the same network's schedule from another market, preferring the same time
     * zone and the network's flagship station. A Central station can borrow an Eastern schedule as it is (the network
     * airs "8/7c") and a Pacific one with three hours added; nothing else crosses zones.
     */
    fun networkFallback(identity: Identity): Borrowed? {
        val catalog = identity.catalog ?: return null
        if (!catalog.hasLocalStations || identity.variant == "news") return null
        val mine = MarketZones.of(identity.market)
            ?: when (identity.variant) { "west", "pacific" -> Zone.Pacific; "central" -> Zone.Central; "mountain" -> Zone.Mountain; else -> if (identity.market.isEmpty()) Zone.Eastern else null }
        var best: Borrowed? = null
        var bestScore = Long.MIN_VALUE
        for (donor in byCatalog[catalog.id].orEmpty()) {
            if (donor.programmeCount <= 0 || donor.identity.variant == "news") continue
            if (donor.identity.feedCountry != identity.feedCountry && donor.identity.feedCountry != null && identity.feedCountry != null) continue
            val theirs = MarketZones.of(donor.identity.market) ?: donor.identity.callsign?.let(MarketZones.FLAGSHIP_CALLSIGNS::get)
            // A callsign-only station could be anywhere; only a network's flagship (or its market-less national feed) has a known place.
            val nationalFeed = donor.identity.market.isEmpty() && donor.identity.callsign == null
            val zone = theirs ?: if (nationalFeed) Zone.Eastern else if (donor.identity.market.isNotEmpty()) null else continue
            val (offset, rank) = when {
                mine == null || zone == null -> if ((mine ?: Zone.Eastern) == (zone ?: Zone.Eastern)) 0 to 3 else continue
                zone == mine -> 0 to 3
                mine == Zone.Central && zone == Zone.Eastern -> 0 to 2
                mine == Zone.Pacific && zone == Zone.Eastern -> 180 to 1
                else -> continue
            }
            val flagship = donor.identity.market in MarketZones.FLAGSHIPS || donor.identity.callsign in MarketZones.FLAGSHIP_CALLSIGNS || nationalFeed
            val score = rank * 10_000_000L + (if (flagship) 1_000_000L else 0L) + donor.programmeCount
            if (score > bestScore) { bestScore = score; best = Borrowed(donor, offset) }
        }
        return best
    }

    private fun overlap(a: Identity, b: Identity): Int = a.marketTokens.count { it in b.marketTokens }

    companion object {
        private val SEPARATORS = Regex("[-_]")
        val EMPTY = GuideDirectory(emptyList())

        /** Builds the directory from the channels an XMLTV source announced and their programme counts. */
        fun build(matcher: CatalogMatcher, listings: List<GuideListing>, identities: MutableMap<String, Identity>? = null): GuideDirectory = GuideDirectory(
            listings.map { listing ->
                val identity = identities?.getOrPut(listing.id + "|" + listing.names.hashCode()) { matcher.identifyGuideChannel(listing.id, listing.names) }
                    ?: matcher.identifyGuideChannel(listing.id, listing.names)
                val keys = LinkedHashSet<String>()
                IdentityText.compact(listing.id.substringBeforeLast('.', listing.id).replace(SEPARATORS, " ")).takeIf { it.isNotEmpty() }?.let(keys::add)
                for (display in listing.names) {
                    val parsed = StreamName.parse(display)
                    parsed.tokens.joinToString("").takeIf { it.isNotEmpty() }?.let(keys::add)
                    parsed.alternates.forEach { alt -> alt.joinToString("").takeIf { it.isNotEmpty() }?.let(keys::add) }
                }
                GuideChannel(listing.id, listing.names, identity, listing.programmeCount, keys)
            },
        )
    }
}
