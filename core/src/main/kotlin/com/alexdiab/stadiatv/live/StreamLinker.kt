package com.alexdiab.stadiatv.live

import java.text.Normalizer
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

// StreamLinker: links a sports event to the playlist streams that carry it, using only data the playlist
// already provides (its XMLTV guide, its channel names) and, as support, the broadcaster list from the
// sports-data API. A faithful port of the iOS `StreamLinker` (MatchLinker package); the tier rules,
// confidences and ordering are identical. Immutable after construction, so one instance can be shared.

data class LinkerTeam(val name: String, val short: String? = null, val nick: String? = null, val city: String? = null, val abbr: String? = null)

data class LinkerEvent(
    val id: String,
    val league: String,
    val kickoffMillis: Long,
    val home: LinkerTeam,
    val away: LinkerTeam,
    val broadcasts: List<String> = emptyList(),
    val isNational: Boolean = false,
)

class LinkerStream(val id: String, val name: String, val category: String, val guideId: String?)

class LinkerProgramme(val guideId: String, val startMillis: Long, val endMillis: Long, val title: String, val desc: String?)

enum class LinkTier(val code: String) {
    LiveListing("T1"),           // guide lists both teams in the title
    ListingByDescription("T2"),  // generic title, description names both teams
    TeamChannel("T3"),           // dedicated team channel / "Next Game" entry naming this fixture
    EventChannel("T4"),          // event-slot channel whose NAME carries the fixture and kickoff time
    LikelyRights("T5"),          // network named by the sports API; guide does not contradict
    Coverage("T6"),              // studio / in-game coverage show about the game
}

class LinkedFeed(
    val key: String,
    val tier: LinkTier,
    val confidence: Double,
    val label: String,
    val region: String,
    val language: String,
    val displayName: String,
    val family: String,
    /** Mirrors, best quality first. */
    val streamIds: List<String>,
    val evidence: String,
    val programmeTitle: String?,
)

class LinkedFamily(val family: String, val best: LinkedFeed, val members: List<LinkedFeed>)

fun List<LinkedFeed>.groupedByFamily(): List<LinkedFamily> {
    val grouped = LinkedHashMap<String, MutableList<LinkedFeed>>()
    for (feed in this) grouped.getOrPut(feed.family) { ArrayList() } += feed
    return grouped.map { (family, members) -> LinkedFamily(family, members[0], members) }
}

class StreamLinker(private val input: List<LinkerStream>, programmesIn: List<LinkerProgramme>) {

    private class S(val chan: Int, val sports: Boolean, val isEvent: Boolean)
    private class Chan(val id: String, val streams: IntList, val progs: IntList)
    private class EventName(val head: String, val times: List<Triple<Int, Int, String>>, val iso: Triple<Int, Int, Int>?)
    private class Builder(var idx: IntArray, var tier: LinkTier = LinkTier.LikelyRights, var conf: Double = 0.0, var label: String = "", var why: String = "", var prog: String? = null)

    private val streams: Array<S>
    private val channels: List<Chan>
    private val eventWordIndex = HashMap<String, IntList>()
    private val teamWordIndex = HashMap<String, IntList>()
    private val netIndex = HashMap<String, IntList>()

    // Guide model, sorted by start time.
    private val source: Array<LinkerProgramme>
    private val starts: LongArray
    private val ends: LongArray
    private val chanOfPos: IntArray
    private val postings = HashMap<String, IntArray>()

    init {
        val chanOf = HashMap<String, Int>()
        val chans = ArrayList<Chan>()
        val ss = ArrayList<S>(input.size)
        val sportsCache = HashMap<String, Boolean>()
        for ((i, stream) in input.withIndex()) {
            val g = normalizedGuide(stream.guideId)
            var chan = -1
            if (g.isNotEmpty()) {
                chan = chanOf.getOrPut(g) { chans += Chan(g, IntList(), IntList()); chans.size - 1 }
                chans[chan].streams.add(i)
            }
            val sports = sportsCache.getOrPut(stream.category) { LinkerPatterns.sportsCategory.containsMatchIn(LinkerText.clean(stream.category)) }
            val display = displayName(stream.name)
            val cleaned = LinkerText.clean(display)
            val isEvent = hasEventHint(display, cleaned)
            if (isEvent) {
                for (word in LinkerText.distinctWords(cleaned)) eventWordIndex.getOrPut(word) { IntList() }.add(i)
            } else if (sports) {
                val ws = cleaned.split(' ').filter { it.isNotEmpty() }
                if (ws.size <= 7) for (w in ws.toSet()) teamWordIndex.getOrPut(w) { IntList() }.add(i)
            }
            val nk = netKey(cleaned)
            if (nk in networkKeys) netIndex.getOrPut(nk) { IntList() }.add(i)
            ss += S(chan, sports, isEvent)
        }
        streams = ss.toTypedArray()

        // Keep only programmes whose guide id has a stream, sorted by start.
        val keep = ArrayList<Pair<Int, Int>>(programmesIn.size) // (chan, original index)
        var lastRaw = ""
        var lastChan = -1
        for ((i, p) in programmesIn.withIndex()) {
            val c = if (p.guideId == lastRaw) lastChan else (chanOf[normalizedGuide(p.guideId)] ?: -1).also { lastRaw = p.guideId; lastChan = it }
            if (c >= 0) keep += c to i
        }
        keep.sortBy { programmesIn[it.second].startMillis }
        val n = keep.size
        source = Array(n) { programmesIn[keep[it].second] }
        starts = LongArray(n) { source[it].startMillis }
        ends = LongArray(n) { source[it].endMillis }
        chanOfPos = IntArray(n) { keep[it].first }
        val building = HashMap<String, IntList>()
        for (pos in 0 until n) {
            chans[chanOfPos[pos]].progs.add(pos)
            val p = source[pos]
            val text = if (p.desc.isNullOrEmpty()) p.title else p.title + " " + p.desc.take(300)
            for (word in LinkerText.distinctWords(LinkerText.clean(text))) building.getOrPut(word) { IntList() }.add(pos)
        }
        for ((word, list) in building) postings[word] = list.toArray()
        channels = chans
    }

    class Stats(val streams: Int, val guideChannels: Int, val programmes: Int, val postings: Int)

    val stats: Stats get() = Stats(streams.size, channels.size, source.size, postings.values.sumOf { it.size })

    // region Candidate retrieval

    private fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Sorted positions in [lo, hi) mentioning every word of at least one alias (probing its rarest word) or one `extra` token. */
    private fun candidatePositions(aliases: Set<String>, extra: Set<String>, lo: Int, hi: Int): IntArray {
        val out = IntList()
        fun take(list: IntArray) {
            var a = 0
            var b = list.size
            while (a < b) { val m = (a + b) ushr 1; if (list[m] < lo) a = m + 1 else b = m }
            while (a < list.size && list[a] < hi) { out.add(list[a]); a++ }
        }
        for (alias in aliases) {
            var best: IntArray? = null
            var absent = false
            for (w in alias.split(' ')) {
                if (LinkerText.utf8Length(w) < 3) continue
                val r = postings[w]
                if (r == null || r.isEmpty()) { absent = true; break }
                if (best == null || r.size < best.size) best = r
            }
            if (!absent && best != null) take(best)
        }
        for (t in extra) postings[t]?.takeIf { it.isNotEmpty() }?.let(::take)
        val arr = out.toArray()
        arr.sort()
        return unique(arr)
    }

    private fun eventStreamCandidates(aliases: Set<String>): IntArray {
        val out = HashSet<Int>()
        for (alias in aliases) {
            var best: IntList? = null
            var absent = false
            for (w in alias.split(' ')) {
                if (LinkerText.utf8Length(w) < 3) continue
                val l = eventWordIndex[w]
                if (l == null) { absent = true; break }
                if (best == null || l.size < best.size) best = l
            }
            if (!absent && best != null) for (i in 0 until best.size) out += best[i]
        }
        return out.toIntArray().also { it.sort() }
    }

    /** Does this guide channel have a normal-length programme on air across [t0, t1]? */
    private fun covers(channel: Int, t0: Long, t1: Long): Boolean {
        val list = channels[channel].progs
        var a = 0
        var b = list.size
        while (a < b) { val m = (a + b) ushr 1; if (starts[list[m]] <= t1) a = m + 1 else b = m }
        var i = a - 1
        var steps = 0
        while (i >= 0 && steps < 6) {
            val p = list[i]
            if (ends[p] >= t0 && ends[p] - starts[p] <= 6 * 3600_000L) return true
            i--; steps++
        }
        return false
    }

    // endregion

    // region Link

    fun link(event: LinkerEvent, preMinutes: Int = 30, postMinutes: Int = 15): List<LinkedFeed> {
        val kts = event.kickoffMillis
        val nat = event.isNational
        val a = LinkerAliases.of(event.home, nat).toMutableSet()
        val b = LinkerAliases.of(event.away, nat).toMutableSet()
        val fa = LinkerAliases.of(event.home, nat, fullOnly = true)
        val fb = LinkerAliases.of(event.away, nat, fullOnly = true)
        val shared = a.intersect(b)
        a.removeAll(shared); b.removeAll(shared)
        val fullNames = setOf(LinkerText.clean(event.home.name), LinkerText.clean(event.away.name))
        val dHome = distinctiveTokens(event.home, nat)
        val dAway = distinctiveTokens(event.away, nat)
        val tokA = dHome - dAway
        val tokB = dAway - dHome

        val feeds = LinkedHashMap<String, Builder>()
        fun add(key: String, idx: IntArray, tier: LinkTier, conf: Double, label: String, why: String, prog: String? = null) {
            val builder = feeds.getOrPut(key) { Builder(idx) }
            if (conf > builder.conf) { builder.tier = tier; builder.conf = conf; builder.label = label; builder.why = why; builder.prog = prog }
        }

        // Guide. Candidates normally overlap kickoff, but provider XMLTV clocks are sometimes wrong by a whole-zone
        // offset. An exact A-v-B title remains stronger evidence than a broadcaster guess, so allow it to begin up to
        // GUIDE_CLOCK_SKEW_MILLIS after kickoff. "Next Game" placeholders retain their separate date-based rule.
        val earlyLo = lowerBound(kts - 36 * 3600_000L)
        val lo = lowerBound(kts - 6 * 3600_000L)
        val hi = lowerBound(kts + maxOf(preMinutes * 60_000L, GUIDE_CLOCK_SKEW_MILLIS) + 1)
        val cA = candidatePositions(a + fa, tokA, earlyLo, hi)
        val cB = candidatePositions(b + fb, tokB, earlyLo, hi)
        for (i in intersect(cA, cB)) {
            val chan = channels[chanOfPos[i]]
            val src = source[i]
            val next = nextGame(src.title)
            if (next != null) {
                if (namesBoth(next.first, a, b) && abs(dayDelta(next.second, kts)) <= 1) {
                    add("g:" + chan.id, chan.streams.toArray(), LinkTier.TeamChannel, 0.62, "Team channel", "guide ${src.guideId}: ${src.title.take(70)}", src.title)
                }
                continue
            }
            if (i < lo || ends[i] < kts + postMinutes * 60_000L || ends[i] - starts[i] > 6 * 3600_000L) continue
            val (head, live) = LinkerText.splitBadge(src.title)
            if (LinkerPatterns.placeholder.containsMatchIn(head) || LinkerPatterns.replay.containsMatchIn(head)) continue
            val coverage = LinkerPatterns.coverage.containsMatchIn(head)
            val key = "g:" + chan.id
            val why = "guide ${src.guideId}: ${src.title.take(70)}"
            var matched = false
            val (ok, separators) = fixtureIn(head, a, b)
            if (ok) {
                if (coverage) add(key, chan.streams.toArray(), LinkTier.Coverage, 0.35, "Coverage show", why, src.title)
                else add(key, chan.streams.toArray(), LinkTier.LiveListing, if (separators == 1) (if (live) 0.97 else 0.92) else 0.8, "Live listing", why, src.title)
                matched = true
            } else if (namesBoth(head, a, b)) {
                add(key, chan.streams.toArray(), if (coverage) LinkTier.Coverage else LinkTier.LiveListing, if (coverage) 0.3 else 0.7, if (coverage) "Coverage show" else "Live listing", why, src.title)
                matched = true
            }
            if (!matched) { // generic title ("Hockey sur glace : NHL"): the description may carry the fixture
                val desc = src.desc.orEmpty().take(300)
                if (desc.isNotEmpty() && namesBoth(desc, fa, fb) && !LinkerPatterns.replay.containsMatchIn(desc.take(200))) {
                    add(key, chan.streams.toArray(), LinkTier.ListingByDescription, 0.72, "Live listing (description)", "guide ${src.guideId}: ${src.title.take(60)} || ${desc.take(60)}", src.title)
                    matched = true
                }
            }
            if (!matched && !coverage && fuzzyFixture(head, tokA, tokB)) {
                add(key, chan.streams.toArray(), LinkTier.LiveListing, 0.75, "Live listing (name variant)", why, src.title)
            }
        }

        // T4: event-slot channels whose NAME carries the fixture.
        for (i in intersect(eventStreamCandidates(a + fa), eventStreamCandidates(b + fb))) {
            val name = input[i].name
            val ev = parseEventName(name)
            if (!fixtureIn(ev.head, a, b).first) continue
            if (ev.iso != null && abs(utcDayNumber(ev.iso.first, ev.iso.second, ev.iso.third) - utcDayNumber(kts)) > 1) continue
            if (ev.times.isNotEmpty()) {
                if (kickoffMatches(ev.times, kts)) add("n:" + input[i].id, intArrayOf(i), LinkTier.EventChannel, 0.85, "Event channel", "channel name: ${name.take(70)}")
            } else {
                add("n:" + input[i].id, intArrayOf(i), LinkTier.EventChannel, 0.5, "Event channel (no time)", "channel name (no time): ${name.take(70)}")
            }
        }

        // T3: dedicated team channels named after a participant.
        val teamCands = java.util.TreeSet<Int>()
        val fullVariants = fullNames + fullNames.map(LinkerText::withoutConnectors)
        for (full in fullNames) {
            val ws = full.split(' ').filter { it.isNotEmpty() }
            val rare = ws.minByOrNull { teamWordIndex[it]?.size ?: 0 } ?: continue
            val list = teamWordIndex[rare] ?: continue
            for (k in 0 until list.size) {
                val i = list[k]
                if (LinkerText.hasAny(LinkerText.variants(LinkerText.clean(displayName(input[i].name))), fullVariants)) teamCands += i
            }
        }
        for (i in teamCands) {
            val s = streams[i]
            if (s.chan >= 0 && feeds.containsKey("g:" + channels[s.chan].id)) continue
            add("t:" + input[i].id, intArrayOf(i), LinkTier.TeamChannel, 0.5, "Team channel (unverified)", "team channel: ${input[i].name.take(60)}")
        }

        // T5: broadcast partners named by the sports API, same region, unless their own guide shows something else at kickoff.
        val wanted = LinkedHashMap<String, String>()
        for (n in event.broadcasts) {
            val k = LinkerText.clean(n).replace(" ", "")
            for (nk in networks[k].orEmpty()) wanted[nk] = networkRegion[k] ?: "US"
        }
        val partners = ArrayList<Pair<Int, String>>()
        for ((nk, region) in wanted) netIndex[nk]?.let { list -> for (k in 0 until list.size) partners += list[k] to region }
        partners.sortBy { it.first } // playlist order: deterministic output
        for ((i, region) in partners) {
            val s = streams[i]
            var r = regionOf(input[i].name, input[i].guideId.orEmpty())
            if (r == "CAF") r = "CA"
            if (r != region) continue
            if (s.chan >= 0) {
                val c = s.chan
                if (feeds.containsKey("g:" + channels[c].id)) continue
                if (covers(c, kts + postMinutes * 60_000L, kts + preMinutes * 60_000L)) continue
                add("r:" + channels[c].id, channels[c].streams.toArray(), LinkTier.LikelyRights, 0.6, "Likely (broadcast partner)", "sports data lists this network (channel guide ends before kickoff)")
            } else {
                add("r:" + input[i].id, intArrayOf(i), LinkTier.LikelyRights, 0.6, "Likely (broadcast partner)", "sports data lists this network (channel has no guide)")
            }
        }

        // Assemble: mirrors best quality first, feeds by confidence then discovery order.
        val out = ArrayList<LinkedFeed>(feeds.size)
        for ((key, builder) in feeds) {
            val ranked = builder.idx.withIndex()
                .map { (offset, i) -> Triple(offset, i, quality(input[i].name)) }
                .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.third }.thenBy { it.first })
            val top = input[ranked[0].second]
            val region = regionOf(top.name, top.guideId.orEmpty())
            val display = displayName(top.name)
            out += LinkedFeed(
                key = key, tier = builder.tier, confidence = builder.conf, label = builder.label, region = region,
                language = languages[region].orEmpty(), displayName = display, family = family(display, region),
                streamIds = ranked.map { input[it.second].id }, evidence = builder.why, programmeTitle = builder.prog,
            )
        }
        return out.withIndex().sortedWith(compareByDescending<IndexedValue<LinkedFeed>> { it.value.confidence }.thenBy { it.index }).map { it.value }
    }

    // endregion

    companion object {
        /** Large enough for common bad XMLTV offsets, but not large enough to turn a later replay into the live game. */
        private const val GUIDE_CLOCK_SKEW_MILLIS = 3 * 3600_000L

        private val networks: Map<String, List<String>> = mapOf(
            "espn" to listOf("espn"), "espn2" to listOf("espn2"), "espnu" to listOf("espnu"), "tnt" to listOf("tnt"), "trutv" to listOf("trutv"),
            "sn" to listOf("sportsnet", "sportsnetontario", "sportsnetone", "sportsneteast", "sportsnetpacific", "sportsnetwest", "sportsnet360"),
            "tvas" to listOf("tvasports"), "tvas2" to listOf("tvasports2"), "cnbc" to listOf("cnbc"), "usanet" to listOf("usanetwork"),
            "fs1" to listOf("foxsports1", "fs1"), "fs2" to listOf("foxsports2", "fs2"), "universo" to listOf("nbcuniverso", "universo"),
            "msgsn" to listOf("msgsportsnet", "msgsn", "msg"), "abc" to listOf("abc"), "cbs" to listOf("cbs"), "nbc" to listOf("nbc"), "fox" to listOf("fox"),
            "tsn" to listOf("tsn", "tsn1", "tsn2", "tsn3", "tsn4", "tsn5"), "nflnetwork" to listOf("nflnetwork"), "mlbnetwork" to listOf("mlbnetwork"),
            "nhlnetwork" to listOf("nhlnetwork"), "goltv" to listOf("goltv"), "univision" to listOf("univision"), "telemundo" to listOf("telemundo"),
            "cbssn" to listOf("cbssportsnetwork"),
        )
        private val networkKeys: Set<String> = networks.values.flatten().toSet()
        private val networkRegion = mapOf("sn" to "CA", "tvas" to "CA", "tvas2" to "CA", "tsn" to "CA") // everything else defaults to US
        private val languages = mapOf(
            "US" to "en", "CA" to "en", "UK" to "en", "GB" to "en", "AU" to "en", "NL" to "nl", "DE" to "de", "AT" to "de", "CH" to "de",
            "FR" to "fr", "BE" to "fr", "IT" to "it", "ES" to "es", "AR" to "es", "MX" to "es", "PT" to "pt", "BR" to "pt", "PL" to "pl",
            "GR" to "el", "TR" to "tr", "BI-AR" to "ar",
        )
        private val zones: Map<String, TimeZone> = mapOf(
            "ET" to "America/New_York", "EST" to "America/New_York", "EDT" to "America/New_York", "CT" to "America/Chicago",
            "CST" to "America/Chicago", "CDT" to "America/Chicago", "MT" to "America/Denver", "PT" to "America/Los_Angeles",
            "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles", "UK" to "Europe/London", "GMT" to "Europe/London",
            "BST" to "Europe/London", "CET" to "Europe/Paris", "CEST" to "Europe/Paris",
        ).mapValues { TimeZone.getTimeZone(it.value) }
        private val netDrop = setOf("east", "west", "pacific", "central", "mountain", "hd", "fhd", "uhd", "sd", "4k", "feed", "live", "plus")
        private val qualityTable = listOf("4k" to 9, "uhd" to 9, "fhd" to 8, "1080" to 8, "hd" to 6, "720" to 5, "sd" to 2)

        internal fun normalizedGuide(guide: String?): String = guide?.trim()?.lowercase(Locale.ROOT).orEmpty()

        private fun isSpace(u: Int) = u == 0x20 || u == 0x09 || u == 0x0A || u == 0x0D || u == 0xA0 || (u > 0x7F && Character.isWhitespace(u))
        private fun isMarker(u: Int) = u == 0x2605 || u == 0x2B50 || u == 0x2A || u == 0x7C || u == 0x3A // ★ ⭐ * | :

        private fun isWordScalar(u: Int): Boolean {
            if (u < 0x80) return u in 48..57 || u in 65..90 || u in 97..122 || u == 95
            return LinkerText.isLetterOrNumber(u) ||
                Character.getType(u) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(u) == Character.COMBINING_SPACING_MARK.toInt()
        }

        // Not String.codePoints(): that needs API 24 and the app supports Fire OS 6 (API 25 and 23-era boxes).
        private fun codePoints(s: String): IntArray {
            val text = Normalizer.normalize(s, Normalizer.Form.NFKD)
            val out = IntArray(text.codePointCount(0, text.length))
            var index = 0
            var i = 0
            while (i < text.length) {
                val point = text.codePointAt(i)
                out[index++] = point
                i += Character.charCount(point)
            }
            return out
        }

        /** Skips the provider's channel-name prefix ("US ★ ", "UK | ", "CA: "): the index after the last complete group and the first group's letters. */
        private fun prefixScan(sc: IntArray): Pair<Int, String?> {
            var i = 0
            var end = 0
            var code: String? = null
            val n = sc.size
            while (true) {
                var j = i
                while (j < n && isSpace(sc[j])) j++
                var k = j
                while (k < n && k - j < 4 && sc[k] in 65..90) k++
                if (k - j < 2) break
                var m = k
                while (m < n && isSpace(sc[m])) m++
                if (m >= n || !isMarker(sc[m])) break
                if (code == null) code = String(sc, j, k - j)
                while (m < n && isMarker(sc[m])) m++
                while (m < n && isSpace(sc[m])) m++
                i = m
                end = m
            }
            return end to code
        }

        /** "US ★ ESPN2 HD" -> "ESPN2": region prefix, ◉ ● ★ and standalone quality words removed, whitespace collapsed. */
        internal fun displayName(name: String): String {
            val sc = codePoints(name)
            var i = prefixScan(sc).first
            val n = sc.size
            val out = StringBuilder(n)
            var pendingSpace = false
            fun put(u: Int) {
                if (pendingSpace) { if (out.isNotEmpty()) out.append(' '); pendingSpace = false }
                out.appendCodePoint(u)
            }
            while (i < n) {
                val u = sc[i]
                if (isWordScalar(u)) {
                    var j = i
                    while (j < n && isWordScalar(sc[j])) j++
                    if (isQualityWord(sc, i, j)) pendingSpace = true else for (k in i until j) put(sc[k])
                    i = j
                } else {
                    if (u == 0x25C9 || u == 0x25CF || u == 0x2605 || isSpace(u)) pendingSpace = true else put(u)
                    i++
                }
            }
            var s = out.toString()
            s = s.trim { it == ' ' || it == ':' || it == '-' }
            return Normalizer.normalize(s, Normalizer.Form.NFC)
        }

        private fun isQualityWord(sc: IntArray, from: Int, to: Int): Boolean {
            val len = to - from
            if (len != 2 && len != 3) return false
            val w = StringBuilder(len)
            for (k in from until to) { if (sc[k] >= 0x80) return false; w.append((sc[k] or 0x20).toChar()) }
            return w.toString().let { it == "hd" || it == "sd" || it == "fhd" || it == "uhd" || it == "4k" }
        }

        internal fun regionOf(name: String, guide: String): String {
            prefixScan(codePoints(name)).second?.let { return it }
            val dot = guide.lastIndexOf('.')
            if (dot >= 0 && guide.length - dot > 1) return guide.substring(dot + 1).uppercase(Locale.ROOT)
            return ""
        }

        internal fun family(display: String, region: String): String {
            val stop = setOf("hd", "fhd", "uhd", "sd", "east", "west", "pacific", "central", "feed", "channel", "tv")
            val tokens = LinkerText.words(display).filter { it !in stop && it.toIntOrNull() == null }
            return (region.ifEmpty { "?" }) + ":" + (tokens.firstOrNull() ?: "?")
        }

        /** Clean display name -> network key ("espn 2 hd east" -> "espn2"). */
        internal fun netKey(cleaned: String): String = cleaned.split(' ').filter { it.isNotEmpty() && it !in netDrop }.joinToString("")

        internal fun quality(name: String): Int {
            val c = LinkerText.clean(name)
            var q = 4
            var found = false
            for ((k, v) in qualityTable) if (LinkerText.hasPhrase(c, k)) { q = if (found) maxOf(q, v) else v; found = true }
            if (c.split(' ').any { it == "backup" || it == "bk" || it == "bckp" }) q -= 3
            return q
        }

        /** Does the name read like a fixture? ("vs", "v", "at", "@", " - ") */
        internal fun hasEventHint(display: String, cleaned: String): Boolean {
            if (display.contains('@') || display.contains(" - ")) return true
            for (token in cleaned.split(' ')) if (token == "v" || token == "vs" || token == "at") return true
            return false
        }

        private fun parseEventName(name: String): EventName {
            val sc = codePoints(name)
            val start = prefixScan(sc).first
            var n = String(sc, start, sc.size - start)
            n = LinkerPatterns.bracketQuality.replace(n, " ")
            val times = ArrayList<Triple<Int, Int, String>>()
            for (m in LinkerPatterns.time.findAll(n)) {
                var h = (m.groupValues[1].toIntOrNull() ?: 0) % 12
                if (m.groupValues[3].uppercase() == "PM") h += 12
                val minute = m.groupValues[2].toIntOrNull() ?: 0
                val tz = m.groupValues[4].ifEmpty { "ET" }.uppercase()
                times += Triple(h, minute, tz)
            }
            val iso = LinkerPatterns.iso.find(n)?.let {
                Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
            }
            return EventName(n, times, iso)
        }

        /** "A <separator> B" (either order); returns (matched, number of separators). */
        private fun fixtureIn(head: String, a: Set<String>, b: Set<String>): Pair<Boolean, Int> {
            val h = Normalizer.normalize(head, Normalizer.Form.NFKD)
            val seps = LinkerPatterns.separator.findAll(h).toList()
            if (seps.isEmpty()) return false to 0
            for (m in seps) {
                val left = LinkerText.variants(LinkerText.clean(h.substring(0, m.range.first)))
                val right = LinkerText.variants(LinkerText.clean(h.substring(m.range.last + 1)))
                if ((LinkerText.hasAny(left, a) && LinkerText.hasAny(right, b)) || (LinkerText.hasAny(left, b) && LinkerText.hasAny(right, a))) return true to seps.size
            }
            return false to seps.size
        }

        /** Fallback for name variants ("Celta B" vs "RC Celta Fortuna"): each side of a single separator holds a distinctive token of a different team. */
        private fun fuzzyFixture(head: String, ta: Set<String>, tb: Set<String>): Boolean {
            val h = Normalizer.normalize(head, Normalizer.Form.NFKD)
            val seps = LinkerPatterns.separator.findAll(h).toList()
            if (seps.size != 1 || head.length >= 100) return false
            val m = seps[0]
            val left = LinkerText.words(h.substring(0, m.range.first)).toSet()
            val right = LinkerText.words(h.substring(m.range.last + 1)).toSet()
            return (left.any(ta::contains) && right.any(tb::contains)) || (left.any(tb::contains) && right.any(ta::contains))
        }

        private fun namesBoth(text: String, a: Set<String>, b: Set<String>): Boolean {
            val c = LinkerText.variants(LinkerText.clean(text))
            return LinkerText.hasAny(c, a) && LinkerText.hasAny(c, b)
        }

        private fun distinctiveTokens(team: LinkerTeam, national: Boolean): Set<String> {
            val out = HashSet<String>()
            for (alias in LinkerAliases.of(team, national)) for (t in alias.split(' ')) if (t.length >= 5 && t !in LinkerAliases.generic) out += t
            return out
        }

        private fun kickoffMatches(times: List<Triple<Int, Int, String>>, kickoff: Long, tolMinutes: Int = 25): Boolean {
            for ((h, m, tz) in times) {
                val calendar = Calendar.getInstance(zones[tz] ?: zones.getValue("ET"))
                calendar.timeInMillis = kickoff
                val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
                if (abs(minutes - (h * 60 + m)) <= tolMinutes) return true
            }
            return false
        }

        private fun nextGame(title: String): Pair<String, Triple<Int, Int, Int>>? {
            if (title.length <= 10) return null
            val m = LinkerPatterns.nextGame.find(title) ?: return null
            return m.groupValues[1] to Triple(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
        }

        private fun utcDayNumber(year: Int, month: Int, day: Int): Long {
            val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))
            calendar.clear()
            calendar.set(year, month - 1, day)
            return calendar.timeInMillis / 86_400_000L
        }

        private fun utcDayNumber(millis: Long): Long = Math.floorDiv(millis, 86_400_000L)

        private fun dayDelta(date: Triple<Int, Int, Int>, kickoff: Long): Long =
            utcDayNumber(date.first, date.second, date.third) - utcDayNumber(kickoff)

        private fun intersect(a: IntArray, b: IntArray): IntArray {
            val out = IntList()
            var i = 0
            var j = 0
            while (i < a.size && j < b.size) {
                when {
                    a[i] == b[j] -> { out.add(a[i]); i++; j++ }
                    a[i] < b[j] -> i++
                    else -> j++
                }
            }
            return out.toArray()
        }

        private fun unique(sorted: IntArray): IntArray {
            if (sorted.isEmpty()) return sorted
            val out = IntList()
            var last = Int.MIN_VALUE
            for (v in sorted) if (v != last) { out.add(v); last = v }
            return out.toArray()
        }
    }
}

/** A minimal growable int list; boxing 100k programme positions would double the index's memory. */
internal class IntList(capacity: Int = 4) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(maxOf(8, size * 2))
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]
    fun toArray(): IntArray = data.copyOf(size)
}
