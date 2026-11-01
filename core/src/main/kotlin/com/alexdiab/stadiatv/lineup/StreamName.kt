package com.alexdiab.stadiatv.lineup

import androidx.compose.runtime.Immutable

/** Picture quality read from a stream's name. Untagged streams are standard definition. */
enum class Quality(val label: String, val rank: Int) {
    Sd("SD", 1), Hd("HD", 2), Fhd("FHD", 3), Uhd("4K", 4);

    companion object {
        /** Fire TV sticks decode 1080p comfortably; 4K is only preferred when the user asks for it. */
        fun order(preferHd: Boolean, preferUhd: Boolean): Comparator<Quality> = Comparator { a, b ->
            fun score(q: Quality): Int = when {
                !preferHd -> 5 - q.rank // standard definition first, for slow connections
                q == Uhd -> if (preferUhd) 5 else 1
                else -> q.rank
            }
            score(a).compareTo(score(b))
        }
    }
}

/** A provider's channel name taken apart: what it is, where it is from, how good it is and whether it is a real channel. */
@Immutable
class StreamName(
    val raw: String,
    /** Country of the feed ("CA", "US", "GB" ...) from a name prefix like "CA ★", or null when the name doesn't say. */
    val prefixCountry: String?,
    val french: Boolean,
    /** Normalised words of the name with prefix, quality, brackets and decoration removed. */
    val tokens: List<String>,
    /** Other readings of the name ("TMN1E - CRAVE 1 TV" is both halves), for matching. */
    val alternates: List<List<String>>,
    val quality: Quality,
    /** A clean name to show people: provider prefix, quality tags and decoration gone, ALL CAPS softened. */
    val title: String,
    /** The text of any [BRACKET] or (PARENTHESIS) groups, normalised: [EAST], [MIAMI], (WCTI). */
    val hints: List<String>,
    /** A backup or alternate copy of a stream; used only if nothing better exists. */
    val isBackup: Boolean,
) {
    val compact: String get() = tokens.joinToString("")

    companion object {
        private val NBSP = Regex("[\\u00A0\\u2007\\u202F]")
        private val ATTACHED_PREFIX = Regex("^([A-Za-z]{2,5})\\s*[★✮⭐❖]\\s*")
        private val HALF_SEPARATOR = Regex("\\s[-–—]\\s")
        private val PIPES = Regex("[|]+")
        private val WHITESPACE = Regex("\\s+")
        private val PLUS_WORD = Regex("[+]|plus", RegexOption.IGNORE_CASE)
        private val BRACKET_GROUP = Regex("[\\[(]([^\\])]{1,40})[\\])]")
        private val DECORATION = Regex("[★✮⭐❖◉●◆▶►◀◄◈◇⊕⊗⊘☆✦✧²³¹⁰⁴⁵⁶⁷⁸⁹ᴴᴰ]+")
        private val PREFIX = Regex("^[\\s|]*([A-Za-z]{2,5})\\s*(?:[★✮⭐❖►»|:]+|\\s[-–—]\\s)\\s*")
        private val TRAILING_EVENT = Regex("\\s\\d{1,3}\\s*[:：]\\s*.*$")
        private val QUALITY_WORDS = mapOf(
            "4k" to Quality.Uhd, "uhd" to Quality.Uhd, "2160p" to Quality.Uhd, "2160" to Quality.Uhd,
            "fhd" to Quality.Fhd, "1080p" to Quality.Fhd, "1080i" to Quality.Fhd, "1080" to Quality.Fhd,
            "hd" to Quality.Hd, "720p" to Quality.Hd, "720" to Quality.Hd, "hevc" to Quality.Hd, "h265" to Quality.Hd,
            "sd" to Quality.Sd, "480p" to Quality.Sd, "576p" to Quality.Sd, "576i" to Quality.Sd, "480i" to Quality.Sd,
        )
        private val NOISE_WORDS = setOf("raw", "50fps", "60fps", "fps")
        private val BACKUP_WORDS = setOf("backup", "bk", "bckp", "bkp", "alternate", "alt", "alternative")

        /** Prefix codes that mean the French-Canadian feed. */
        private val FRENCH_PREFIXES = setOf("CAF", "CAFR", "QC")
        private val PREFIX_COUNTRY = mapOf("CAF" to "CA", "CAFR" to "CA", "QC" to "CA", "USA" to "US", "UK" to "GB")
        private val KNOWN_PREFIXES = setOf(
            "CA", "CAF", "CAFR", "QC", "US", "USA", "UK", "GB", "FR", "DE", "ES", "IT", "PT", "NL", "BE", "CH", "AT", "SE", "NO", "DK", "FI", "PL", "RO",
            "GR", "TR", "AR", "AE", "SA", "EG", "IN", "PK", "BR", "MX", "LAT", "LATIN", "CAR", "AU", "NZ", "IE", "AL", "ALB", "EXYU", "MY", "ID", "IR", "KU",
            "CZ", "HU", "MT", "BG", "RU", "UA", "IL", "QA", "MA", "DZ", "TN", "LB", "SW", "DSTV", "AF", "PR", "VIX",
        )

        fun parse(name: String, group: String = ""): StreamName {
            var text = NBSP.replace(name, " ").trim()
            var country: String? = null
            var french = false
            val prefix = PREFIX.find(text)
            if (prefix != null) {
                val code = prefix.groupValues[1].uppercase()
                if (code in KNOWN_PREFIXES) {
                    country = PREFIX_COUNTRY[code] ?: code
                    french = code in FRENCH_PREFIXES
                    text = text.substring(prefix.range.last + 1)
                }
            } else {
                // "CA★ TSN", "CA  ★ CBC": the decoration is attached to the code
                ATTACHED_PREFIX.find(text)?.let { m ->
                    val code = m.groupValues[1].uppercase()
                    if (code in KNOWN_PREFIXES) {
                        country = PREFIX_COUNTRY[code] ?: code
                        french = code in FRENCH_PREFIXES
                        text = text.substring(m.range.last + 1)
                    }
                }
            }
            if (country == null) country = countryOfGroup(group)

            val hints = ArrayList<String>()
            text = BRACKET_GROUP.replace(text) { match ->
                IdentityText.tokens(match.groupValues[1]).joinToString(" ").takeIf { it.isNotEmpty() }?.let(hints::add)
                " "
            }
            text = DECORATION.replace(text, " ")

            // "TMN1E  - CRAVE 1 TV  HD": read both halves; the first is usually a provider code.
            val halves = text.split(HALF_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            val readings = halves.map(::read)
            val primary = readings.firstOrNull() ?: Reading(emptyList(), null, false)
            var quality = readings.mapNotNull { it.quality }.maxByOrNull { it.rank }
            val backup = readings.any { it.backup } || hints.any { h -> h.split(' ').any { it in BACKUP_WORDS } }
            if (hints.any { h -> h.split(' ').any { it in QUALITY_WORDS } }) {
                quality = quality ?: hints.flatMap { it.split(' ') }.mapNotNull { QUALITY_WORDS[it] }.maxByOrNull { it.rank }
            }
            val meaningfulHints = hints.filter { h -> h.split(' ').none { it in BACKUP_WORDS || it in QUALITY_WORDS } }
            return StreamName(
                raw = name,
                prefixCountry = country,
                french = french,
                tokens = primary.tokens,
                alternates = readings.drop(1).map { it.tokens }.filter { it.isNotEmpty() },
                quality = quality ?: Quality.Sd,
                title = prettify(QUALITY_TEXT.replace(text, " ")),
                hints = meaningfulHints,
                isBackup = backup,
            )
        }

        private val QUALITY_TEXT = Regex("(?i)(?<![\\p{L}\\p{N}])(4k|uhd\\+?|fhd|hd|sd|raw|hevc|h265|1080p|720p|backup)(?![\\p{L}\\p{N}])")
        private val ACRONYMS = setOf(
            "ESPN", "ESPN2", "NBCSN", "NESN", "CNBC", "MSNBC", "WWE", "UFC", "BNN", "HGTV", "NHL", "NBA", "NFL", "MLB", "MLS", "TNT", "TBS", "AMC", "BET", "CMT", "MTV",
            "ABC", "CBS", "NBC", "FOX", "PBS", "CNN", "BBC", "CBC", "CTV", "TVA", "RDS", "TSN", "APTN", "CPAC", "CHCH", "HBO", "MGM", "IFC", "SYFY", "TLC", "OWN", "GSN",
            "CP24", "RDI", "LCN", "TFO", "ICI", "WGN", "KTLA", "TUDN", "ION", "MSG", "SNY", "YES", "MASN", "BTN", "SEC", "ACC", "QVC", "HSN", "TCM", "TVO", "DAZN",
        )

        /** "SPORTSNET EAST" -> "Sportsnet East"; short words and known acronyms stay upper case; mixed case is left alone. */
        fun prettify(text: String): String {
            val words = text.replace(PIPES, " ").trim().split(WHITESPACE).filter { it.isNotEmpty() }
            return words.joinToString(" ") { word ->
                val letters = word.filter { it.isLetter() }
                when {
                    letters.isEmpty() || letters.any { it.isLowerCase() } -> word
                    letters.length <= 3 || word.any { it.isDigit() } || word in ACRONYMS || word.any { it == '&' || it == '+' || it == '!' } -> word
                    else -> word.lowercase().replaceFirstChar { it.uppercase() }
                }
            }.trim(' ', '-', ':')
        }

        private class Reading(val tokens: List<String>, val quality: Quality?, val backup: Boolean)

        private fun read(part: String): Reading {
            val words = IdentityText.tokens(part)
            var quality: Quality? = null
            var backup = false
            val kept = ArrayList<String>(words.size)
            var previousWasQuality = false
            for ((index, word) in words.withIndex()) {
                val q = QUALITY_WORDS[word]
                val isQuality = q != null && isQualityContext(word, index == words.lastIndex)
                when {
                    isQuality -> if (quality == null || q!!.rank > quality.rank) quality = q
                    // "UHD+" tokenises to "uhd", "plus": the plus belongs to the quality tag, unlike "Sportsnet+".
                    word == "plus" && previousWasQuality -> Unit
                    word in BACKUP_WORDS && index == words.lastIndex -> backup = true
                    word in NOISE_WORDS && index == words.lastIndex -> Unit
                    else -> kept += word
                }
                previousWasQuality = isQuality
            }
            return Reading(kept, quality, backup)
        }

        /** "720" or "1080" mid-name could belong to the name ("Channel 1080"); only a trailing number reads as quality. */
        private fun isQualityContext(word: String, isLast: Boolean): Boolean = !word.all { it.isDigit() } || isLast

        private val GROUP_PREFIX = Regex("^\\s*([A-Za-z]{2,5})\\s*[❖★|:]")

        private fun countryOfGroup(group: String): String? {
            val code = GROUP_PREFIX.find(group)?.groupValues?.get(1)?.uppercase() ?: return null
            if (code !in KNOWN_PREFIXES) return null
            return PREFIX_COUNTRY[code] ?: code
        }

        /** Does the category or name mark a streaming-service event feed rather than a channel with a schedule? */
        fun isEventFeed(name: String, group: String): Boolean {
            val g = group.lowercase()
            if (EVENT_GROUP.containsMatchIn(g)) return true
            if (EVENT_NAME.containsMatchIn(NBSP.replace(name, " "))) return true
            return TRAILING_EVENT.containsMatchIn(NBSP.replace(name, " ")) && PLUS_WORD.containsMatchIn(name)
        }

        /** "MLS 01: " and "event 3:" placeholders: numbered slots a provider fills with whatever game is on. */
        private val EVENT_NAME = Regex("\\b(?:mls|mlb|milb|nhl|nfl|nba|wnba|ncaa|tennis|ufc|wwe|f1|event|game|match|fight)\\s*\\d{1,3}\\s*:", RegexOption.IGNORE_CASE)

        /** Free ad-supported streaming channels (Pluto TV, Samsung TV Plus ...): 24/7 loops with no programme schedule. */
        fun isFastFeed(group: String): Boolean = FAST_GROUP.containsMatchIn(group.lowercase())

        private val FAST_GROUP = Regex("pluto|samsung|\\btubi\\b|roku|plex|xumo|\\bfast\\b|freevee|distro|stirr|local now|vizio|redbox|24[-/ ]?7")

        private val EVENT_GROUP = Regex(
            "\\bppv\\b|\\bevents?\\b|flosports|victory\\s*\\+|espn\\s*plus|espn\\+|\\btsn\\s*\\+|sportsnet\\s*\\+|\\bdazn\\b|peacock|\\bprime\\b|paramount\\s*(ppv|\\+)|" +
                "f1\\s*&\\s*motogp|hbo\\s*max|\\bsling\\b|apple\\s*tv|hulu|disney\\s*\\+|\\bbtn\\s*\\+|\\bncaa\\b|\\bnetflix\\b|on[- ]demand|\\bvod\\b|series\\b|cinemania|christmas|play\\s*kidz",
        )
    }
}
