package com.alexdiab.stadiatv.lineup

import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.ChannelFilterEngine

/**
 * Turns a provider's flat channel list into a lineup: streams that are the same channel at different qualities are
 * grouped, matched to the catalogue, and each group is given the best guide that can be found for it.
 */
class LineupBuilder(private val matcher: CatalogMatcher = CatalogMatcher()) {

    val guideMatcher: CatalogMatcher get() = matcher

    /** Reads every channel's name once. Cheap enough to run on each playlist change. */
    fun analyse(channels: List<Channel>): List<ResolvedStream> = channels.map { channel ->
        val name = StreamName.parse(channel.name, channel.group)
        val fast = StreamName.isFastFeed(channel.group)
        val identity = if (fast) matcher.uncatalogued(name, channel.tvgId) else matcher.identify(name, channel.tvgId)
        ResolvedStream(channel, name, identity, StreamName.isEventFeed(channel.name, channel.group), fast, ChannelFilterEngine.categories(channel))
    }

    /** Which guide channels are worth keeping programmes for, chosen before any programme has been read. */
    class GuideInterest(
        /** Lower-case guide ids to keep. */
        val wanted: Set<String>,
        /** The subset whose programme descriptions matter (sport, where a description names the teams). */
        val sporty: Set<String>,
    )

    /**
     * The ids the provider gave streams in [countries], every guide channel that is a catalogue channel of those
     * countries, and uncatalogued guide channels named like a stream. Everything else in a provider's huge guide
     * (other countries' channels nobody here carries) is skipped, which is what keeps it inside a Fire TV's memory.
     */
    fun guideInterest(streams: List<ResolvedStream>, directory: GuideDirectory, countries: Set<String>): GuideInterest {
        val wanted = HashSet<String>()
        val sporty = HashSet<String>()
        val streamKeys = HashSet<String>(streams.size)
        for (stream in streams) {
            if (stream.isEvent || stream.isFast || stream.isAdult) continue
            val country = stream.identity.catalog?.country?.takeIf { it.isNotEmpty() } ?: stream.identity.feedCountry
            if (country != null && country !in countries) continue
            val isSport = "Sports" in stream.categories
            stream.channel.tvgId?.lowercase()?.let { id -> wanted += id; if (isSport) sporty += id }
            if (stream.identity.catalog == null) streamKeys += stream.identity.nameKey
        }
        for (channel in directory.channels) {
            val catalog = channel.identity.catalog
            val id = channel.id.lowercase()
            if (catalog != null && (catalog.country.isEmpty() || catalog.country in countries)) {
                wanted += id
                if (catalog.sportsCapable || Genre.Sports in catalog.genres || Genre.Regional in catalog.genres) sporty += id
            } else if (catalog == null && channel.keys.any { it in streamKeys }) wanted += id
        }
        return GuideInterest(wanted, sporty)
    }

    fun build(streams: List<ResolvedStream>, directory: GuideDirectory?): Lineup {
        val groups = LinkedHashMap<String, MutableList<ResolvedStream>>()
        for (stream in streams) {
            if (stream.isEvent || stream.isAdult) continue
            groups.getOrPut((if (stream.isFast) "fast|" else "") + stream.identity.groupKey) { ArrayList(2) } += stream
        }
        val feedsByEntry = LinkedHashMap<String, MutableList<Feed>>()
        for ((_, members) in groups) {
            val feed = feedFor(members, directory)
            val key = (if (members.first().isFast) "fast|" else "") + feed.identity.entryKey
            feedsByEntry.getOrPut(key) { ArrayList(1) } += feed
        }
        shiftWestFeeds(feedsByEntry)
        val entries = feedsByEntry.map { (key, feeds) -> entryOf(key, feeds) }
        linkTwins(entries)
        return Lineup(entries)
    }

    // region guide resolution

    private fun feedFor(members: List<ResolvedStream>, directory: GuideDirectory?): Feed {
        val best = members.sortedWith(compareBy<ResolvedStream> { it.name.isBackup }.thenByDescending { it.quality.rank })
        val identity = best.first().identity
        if (directory == null) {
            val id = best.firstNotNullOfOrNull { it.channel.tvgId }
            return Feed(identity, best, id, if (id != null) GuideSource.Provider else GuideSource.None)
        }
        // 1. The provider's own id, if it is real, has a schedule, and belongs to this channel.
        val provided = best.mapNotNull { stream -> stream.channel.tvgId?.let { stream to it } }.distinctBy { it.second.lowercase() }
        for ((stream, id) in provided) {
            val listed = directory[id] ?: continue
            if (listed.programmeCount > 0 && identity.compatibleWith(listed.identity)) {
                val source = if (stream === best.first()) GuideSource.Provider else GuideSource.Sibling
                return Feed(identity, best, listed.id, source)
            }
        }
        // 2. Look the channel up in the guide by what it is.
        directory.candidatesFor(identity).firstOrNull()?.let { return Feed(identity, best, it.id, GuideSource.Matched) }
        // 3. A local station the guide has never heard of airs its network's schedule.
        directory.networkFallback(identity)?.let { return Feed(identity, best, it.channel.id, GuideSource.Network, it.offsetMinutes) }
        return Feed(identity, best, null, GuideSource.None)
    }

    /** A West-coast feed with no schedule of its own airs its East feed's programmes three hours later. */
    private fun shiftWestFeeds(entries: MutableMap<String, MutableList<Feed>>) {
        for ((key, feeds) in entries.entries.toList()) {
            val bare = feeds.first().identity
            if (bare.catalog == null || bare.variant != "west" && bare.variant != "pacific" || feeds.any { it.guideId != null }) continue
            val east = entries[bare.withVariant("").entryKey]?.firstOrNull { it.guideId != null } ?: continue
            entries[key] = feeds.mapTo(ArrayList(feeds.size)) { Feed(it.identity, it.streams, east.guideId, GuideSource.Shifted, WEST_OFFSET_MINUTES) }
        }
    }

    /** A channel with no schedule is paired with the same brand's channel in the other country when that one has one. */
    private fun linkTwins(entries: List<LineupEntry>) {
        fun brand(entry: LineupEntry): String? {
            val catalog = entry.catalog ?: return null
            if (catalog.hasLocalStations || entry.isFast) return null
            return IdentityText.tokens(catalog.name).filter { it !in COUNTRY_WORDS }.joinToString("").takeIf { it.isNotEmpty() }
        }
        val byBrand = entries.filter { it.hasGuide }.groupBy { brand(it) }
        for (entry in entries) {
            if (entry.hasGuide) continue
            val key = brand(entry) ?: continue
            entry.twin = byBrand[key]?.firstOrNull { it.catalog?.country != entry.catalog?.country && it.variantOf() == entry.variantOf() }
        }
    }

    private fun LineupEntry.variantOf(): String = feeds.first().identity.variant

    // endregion

    // region entries

    private fun entryOf(key: String, feeds: List<Feed>): LineupEntry {
        val first = feeds.first()
        val identity = first.identity
        val catalog = identity.catalog
        val sample = first.streams.first()
        val fast = sample.isFast
        val inferred = if (catalog == null) inferGenres(feeds.flatMap { f -> f.streams }.flatMapTo(HashSet()) { it.categories }) + if (fast) setOf(Genre.Fast) else emptySet() else emptySet()
        val name = when {
            catalog == null -> sample.name.title.ifBlank { sample.channel.name } + variantSuffix(identity.variant)
            identity.variant == "news" -> "${catalog.shortName} News ${titleCase(identity.market)}".trim()
            identity.market.isNotEmpty() -> "${catalog.shortName} ${titleCase(identity.market)}" + variantSuffix(identity.variant)
            identity.callsign != null && catalog.hasLocalStations -> "${catalog.shortName} ${identity.callsign}" + variantSuffix(identity.variant)
            else -> catalog.name + variantSuffix(identity.variant)
        }
        val country = catalog?.country ?: feeds.firstNotNullOfOrNull { it.identity.feedCountry }.orEmpty()
        val language = catalog?.language ?: if (feeds.any { f -> f.streams.any { it.name.french } }) "fr" else ChannelFilterEngine.languageTags(sample.channel.name + " " + sample.channel.language.orEmpty()).firstOrNull() ?: "en"
        return LineupEntry(key, catalog, name, identity.market, country, language, catalog?.genres ?: inferred, feeds)
    }

    private fun variantSuffix(variant: String) = if (variant.isEmpty() || variant == "news") "" else " (${titleCase(variant)})"

    private companion object {
        const val WEST_OFFSET_MINUTES = 180
        val COUNTRY_WORDS = setOf("canada", "canadian", "us", "usa")
    }

    private fun titleCase(text: String) = text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    /** Genres for channels the catalogue doesn't know, from the provider's category and the name. */
    private fun inferGenres(categories: Set<String>): Set<Genre> {
        val out = LinkedHashSet<Genre>()
        for (category in categories) {
            when (category) {
                "Sports" -> out += Genre.Sports
                "News" -> out += Genre.News
                "Movies" -> out += Genre.Movies
                "Kids" -> out += Genre.Kids
                "Entertainment", "Series", "Game Shows" -> out += Genre.Entertainment
                "Music" -> out += Genre.Music
                "Documentary" -> out += Genre.Documentary
                "Lifestyle" -> out += Genre.Lifestyle
            }
        }
        return out
    }

    // endregion
}
