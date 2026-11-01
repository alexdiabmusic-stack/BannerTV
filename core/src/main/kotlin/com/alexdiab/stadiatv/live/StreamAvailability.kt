package com.alexdiab.stadiatv.live

import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** One playable option for a match: a channel plus why it was surfaced. */
@Immutable
data class RankedSource(
    val channel: Channel,
    val tier: LinkTier,
    val confidence: Double,
    val label: String,
    val evidence: String,
    val family: String,
    val programmeTitle: String? = null,
) {
    /** Automatic Watch requires actual playlist EPG evidence, not a confidently parsed channel name. */
    val isConfirmed: Boolean get() =
        (tier == LinkTier.LiveListing || tier == LinkTier.ListingByDescription) &&
            confidence in 0.7..1.0 && !programmeTitle.isNullOrBlank()
}

@Immutable
data class MatchSources(val sources: List<RankedSource>) {
    val count: Int get() = sources.size
    val confirmed: List<RankedSource> get() = sources.filter { it.isConfirmed }
    val possible: List<RankedSource> get() = sources.filterNot { it.isConfirmed }
    companion object { val EMPTY = MatchSources(emptyList()) }
}

/**
 * Links playlist channels to matches with the [StreamLinker] and serves the results to every screen. The index
 * is rebuilt (off the main thread, at low priority) only when the lineup or guide actually changed; linking
 * one match is sub-millisecond on a desktop and still cheap on a Fire TV.
 */
class StreamAvailabilityStore(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var linker: StreamLinker? = null
    @Volatile private var builtSignature: String? = null
    private var channelByKey: Map<String, Channel> = emptyMap()
    private data class CachedSources(val event: LinkerEvent, val sources: MatchSources)
    private val cache = ConcurrentHashMap<String, CachedSources>()
    private var scanJob: Job? = null

    private val _sources = MutableStateFlow<Map<String, MatchSources>>(emptyMap())
    /** Sources by [Match.key]. Absent means "not scanned yet". */
    val sources: StateFlow<Map<String, MatchSources>> = _sources.asStateFlow()

    fun sourcesFor(match: Match): MatchSources {
        if (match.state == GameState.FINAL || builtSignature == null) return MatchSources.EMPTY
        val accepted = cache[match.key] ?: return MatchSources.EMPTY
        // Hide a prior confirmation immediately when the caller has a corrected fixture, even
        // before its asynchronous ensure completes. Cleared/replaced public entries cannot resurrect.
        // StateFlow conflates equal maps: a valid index rebuild may retain an equal older public
        // object. Compare evidence values, not object identity, after validating the current event.
        return if (accepted.event == event(match) && _sources.value[match.key] == accepted.sources) accepted.sources else MatchSources.EMPTY
    }

    /** Links every upcoming or live match within 48 hours. Coalesces bursts of calls. */
    fun scan(matches: List<Match>, channels: List<Channel>, programmes: List<Programme>, guideIdFor: (Channel) -> String?, guideRevision: Int) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.Default) {
            delay(SCAN_DEBOUNCE_MILLIS)
            lock.withLock {
                val horizon = System.currentTimeMillis() + 48 * 3600_000L
                val candidates = matches.filter { it.state != GameState.FINAL && it.startMillis <= horizon }
                // With no lineup to link against yet, leave every match "not scanned" rather than "scanned, nothing found":
                // the screens say "checking" for the first and "no channel" only for the second.
                val active = ensureLinker(channels, programmes, guideIdFor, guideRevision) ?: return@withLock
                val result = HashMap<String, MatchSources>(candidates.size)
                for (match in candidates) result[match.key] = cached(active, match)
                val finished = matches.filter { it.state == GameState.FINAL }.map { it.key }.toSet()
                _sources.value = (_sources.value - finished) + result
            }
        }
    }

    /** Links one match right now (the Match screen opening), without disturbing the batch scan or other matches' results. */
    suspend fun ensure(match: Match, channels: List<Channel>, programmes: List<Programme>, guideIdFor: (Channel) -> String?, guideRevision: Int) =
        ensure(match, channels, { programmes }, guideIdFor, guideRevision)

    /** The guide projection is expensive: evaluate it once per index revision, not once per roster player. */
    suspend fun ensure(match: Match, channels: List<Channel>, programmes: () -> List<Programme>, guideIdFor: (Channel) -> String?, guideRevision: Int) = withContext(Dispatchers.Default) {
        if (match.state == GameState.FINAL) return@withContext
        lock.withLock {
            val active = if (channels.isEmpty()) null else if (signature(channels, guideRevision) == builtSignature) linker
                else ensureLinker(channels, programmes(), guideIdFor, guideRevision)
            val sources = if (active == null) MatchSources.EMPTY else cached(active, match)
            _sources.value = _sources.value + (match.key to sources)
        }
    }

    private fun cached(active: StreamLinker, match: Match): MatchSources {
        val input = event(match)
        cache[match.key]?.takeIf { it.event == input }?.let { return it.sources }
        // The same event ID can be rescheduled or corrected. An ID-only cache can keep the wrong
        // guide listing confirmed after kickoff/opponents/broadcasts changed.
        return MatchSources(rank(active, input)).also { cache[match.key] = CachedSources(input, it) }
    }

    /** Rebuilds the linker only when the lineup or guide changed since the last build. Call under [lock]. */
    private fun ensureLinker(channels: List<Channel>, programmes: List<Programme>, guideIdFor: (Channel) -> String?, guideRevision: Int): StreamLinker? {
        if (channels.isEmpty()) return null
        val signature = signature(channels, guideRevision)
        if (signature != builtSignature) {
            val linkerStreams = channels.map { LinkerStream(it.key, repairBadgeCorruption(it.name), it.group, guideIdFor(it)) }
            val linkerProgrammes = programmes.map { LinkerProgramme(it.guideId, it.startMillis, it.endMillis, it.title, it.description?.take(300)) }
            linker = com.alexdiab.stadiatv.util.PerfLog.measure("stream linker (${linkerStreams.size} streams, ${linkerProgrammes.size} programmes)") { StreamLinker(linkerStreams, linkerProgrammes) }
            channelByKey = channels.associateBy { it.key }
            builtSignature = signature
            cache.clear()
        }
        return linker
    }

    /** Forces the next scan to rebuild the index, e.g. after an event-slot channel was renamed in place. */
    fun invalidate() { builtSignature = null }

    private fun rank(linker: StreamLinker, event: LinkerEvent): List<RankedSource> {
        val families = linker.link(event).groupedByFamily()
        val out = ArrayList<RankedSource>()
        for (family in families) for (feed in family.members) for (streamId in feed.streamIds) {
            val channel = channelByKey[streamId] ?: continue
            out += RankedSource(channel, feed.tier, feed.confidence, feed.label, feed.evidence, feed.family, feed.programmeTitle)
        }
        // An exact timed event-slot name can score above a generic-title EPG description.
        // Listing-backed choices must still lead; duplicate evidence must not duplicate a channel.
        return out.sortedByDescending { it.isConfirmed }.distinctBy { it.channel.key }
    }

    private fun signature(channels: List<Channel>, guideRevision: Int): String {
        var hash = channels.size
        for (channel in channels) {
            // IDs/names can stay the same while an expiring playback URL, headers, artwork or
            // guide binding changes. Rebuild channel references too, not just the text index.
            hash = 31 * hash + channel.hashCode()
        }
        return "$hash|$guideRevision"
    }

    companion object {
        private const val SCAN_DEBOUNCE_MILLIS = 400L

        fun event(match: Match) = LinkerEvent(
            id = match.id,
            league = match.league.path,
            kickoffMillis = match.startMillis,
            home = team(match.home),
            away = team(match.away),
            broadcasts = match.broadcasts.toList(),
            isNational = false, // every club competition the app carries today
        )

        /**
         * `short` and `nick` must be real nicknames ("Panthers", "Red Sox"), never bare abbreviations: abbreviation-only
         * aliases are what produced junk candidates like "CAR" matching "CAR CHASE" in the old matcher.
         */
        fun team(side: TeamSide): LinkerTeam {
            val short = side.shortName
            val looksLikeNickname = short.contains(' ') || short.length > 5
            return LinkerTeam(name = side.displayName, short = if (looksLikeNickname) short else null, abbr = side.abbreviation)
        }

        private val corruption = Regex("""(\w+)US ★(\w*)""")

        /** Reverses a provider panel's mid-word "US ★" badge injection, e.g. "MUS ★kingum" -> "Muskingum". */
        fun repairBadgeCorruption(text: String): String =
            if (!text.contains("US ★")) text else corruption.replace(text) { "${it.groupValues[1]}us${it.groupValues[2]}" }
    }
}
