package com.alexdiab.stadiatv.live

import java.text.Normalizer
import java.util.Locale

/**
 * Text utilities for the stream linker (port of iOS `LinkerText`, `LinkerAliases`, `LinkerPatterns`
 * and `LinkerCountryNames`). "Clean" text is lower-case, NFKD-folded (so the superscript `ᴸᶦᵛᵉ` badge reads as
 * "live"), diacritics removed, and every run of non-letters/digits collapsed to a single space.
 */
internal object LinkerText {
    fun isAscii(s: String): Boolean {
        for (ch in s) if (ch.code >= 0x80) return false
        return true
    }

    fun clean(s: String): String {
        if (s.isEmpty()) return ""
        val source = if (isAscii(s)) s else Normalizer.normalize(s, Normalizer.Form.NFKD)
        val out = StringBuilder(source.length)
        var separator = true
        var index = 0
        while (index < source.length) {
            val cp = source.codePointAt(index)
            index += Character.charCount(cp)
            when {
                cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code -> { out.append(cp.toChar()); separator = false }
                cp in 'A'.code..'Z'.code -> { out.append((cp or 0x20).toChar()); separator = false }
                cp < 0x80 -> if (!separator) { out.append(' '); separator = true }
                else -> {
                    val type = Character.getType(cp)
                    if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                        type == Character.COMBINING_SPACING_MARK.toInt()
                    ) continue // combining marks are dropped without breaking the word
                    val mapped = when (cp) {
                        0x026A -> 'i'.code // small-capital I left over from the superscript "ᶦ" of the "ᴸᶦᵛᵉ" badge
                        0x1D00 -> 'a'.code
                        else -> Character.toLowerCase(cp)
                    }
                    if (isLetterOrNumber(mapped)) {
                        out.appendCodePoint(mapped); separator = false
                    } else if (!separator) { out.append(' '); separator = true }
                }
            }
        }
        if (out.isNotEmpty() && out[out.length - 1] == ' ') out.setLength(out.length - 1)
        return out.toString()
    }

    fun isLetterOrNumber(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.UPPERCASE_LETTER.toInt(), Character.LOWERCASE_LETTER.toInt(), Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(), Character.OTHER_LETTER.toInt(), Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
        -> true
        else -> false
    }

    fun words(s: String): List<String> = clean(s).split(' ').filter { it.isNotEmpty() }

    /** Whole-word phrase test on cleaned strings. */
    fun hasPhrase(text: String, phrase: String): Boolean {
        if (phrase.isEmpty() || text.length < phrase.length) return false
        var from = 0
        while (true) {
            val index = text.indexOf(phrase, from)
            if (index < 0) return false
            val startOk = index == 0 || text[index - 1] == ' '
            val end = index + phrase.length
            val endOk = end == text.length || text[end] == ' '
            if (startOk && endOk) return true
            from = index + 1
        }
    }

    /** Connector words clubs and guides drop or add ("Atlético de San Luis" vs "Atlético San Luis"). Both spellings are tried. */
    val connectors = setOf("de", "del", "di", "da", "do", "dos", "das", "du", "of", "the", "van", "von", "der", "den", "af")

    fun withoutConnectors(cleaned: String): String =
        cleaned.split(' ').filter { it !in connectors }.joinToString(" ")

    fun variants(cleaned: String): List<String> {
        val stripped = withoutConnectors(cleaned)
        return if (stripped == cleaned) listOf(cleaned) else listOf(cleaned, stripped)
    }

    fun hasAny(texts: List<String>, aliases: Set<String>): Boolean {
        for (alias in aliases) for (text in texts) if (hasPhrase(text, alias)) return true
        return false
    }

    /** Splits the provider's trailing badge ("ᴸᶦᵛᵉ", "ᴺᵉʷ") from a programme title; `live` is true for a "Live" badge. */
    fun splitBadge(title: String): Pair<String, Boolean> {
        var lastVisible = title.length - 1
        while (lastVisible >= 0 && title[lastVisible] == ' ') lastVisible--
        if (lastVisible < 0 || title[lastVisible].code < 0x80) return title.trim() to false
        var i = title.length
        while (i > 0) {
            val ch = title[i - 1]
            if (ch == ' ' || Character.getType(ch) == Character.MODIFIER_LETTER.toInt()) i-- else break
        }
        val tail = title.substring(i)
        if (tail.none { Character.getType(it) == Character.MODIFIER_LETTER.toInt() }) return title.trim() to false
        return title.substring(0, i).trim() to clean(tail).contains("live")
    }

    fun utf8Length(word: String): Int {
        var n = 0
        for (ch in word) n += if (ch.code < 0x80) 1 else if (ch.code < 0x800) 2 else 3
        return n
    }

    /** Distinct words of at least [minBytes] UTF-8 bytes. */
    fun distinctWords(cleaned: String, minBytes: Int = 3): Set<String> {
        val out = HashSet<String>()
        var start = 0
        while (start < cleaned.length) {
            var end = cleaned.indexOf(' ', start)
            if (end < 0) end = cleaned.length
            if (end > start) {
                val word = cleaned.substring(start, end)
                if (utf8Length(word) >= minBytes) out += word
            }
            start = end + 1
        }
        return out
    }
}

internal object LinkerCountryNames {
    private val locales = listOf(
        "en", "de", "fr", "es", "it", "pt", "nl", "tr", "sv", "pl", "da", "nb", "fi", "cs", "hu", "ro", "sk", "hr", "sl", "sq", "bg",
        "sr-Latn", "lt", "lv", "et", "id", "ms", "vi", "ru", "uk", "ar", "fa", "he", "el", "zh-Hans", "ja", "ko", "th", "hi", "ka", "hy",
    )
    private val stop = setOf("and", "the", "of", "de", "la", "el", "republic")

    fun core(s: String): String {
        val cleaned = LinkerText.clean(s).replace("u s ", "us ").replace("st ", "saint ")
        return cleaned.split(' ').filter { it !in stop }.joinToString(" ")
    }

    class Table(val byCore: Map<String, String>, val names: Map<String, Set<String>>)

    /** core name (any language) -> region code, and region code -> every cleaned name. Built once, on the first national-team event. */
    val table: Table by lazy {
        val byCore = HashMap<String, String>()
        val names = HashMap<String, MutableSet<String>>()
        fun add(code: String, name: String) {
            val c = core(name)
            if (c.isEmpty()) return
            byCore[c] = code
            names.getOrPut(code) { HashSet() } += LinkerText.clean(name)
        }
        val locs = locales.map { Locale.forLanguageTag(it) }
        for (code in Locale.getISOCountries()) {
            val region = Locale.Builder().setRegion(code).build()
            for (locale in locs) region.getDisplayCountry(locale).takeIf { it.isNotBlank() && it != code }?.let { add(code, it) }
        }
        listOf("England", "Inglaterra", "Angleterre", "Inghilterra", "Engeland", "Anglia", "Engelska").forEach { add("GB-ENG", it) }
        listOf("Scotland", "Escocia", "Ecosse", "Écosse", "Schottland", "Schotland", "Scozia", "Skotland", "Skottland", "Skotsko").forEach { add("GB-SCT", it) }
        listOf("Wales", "Gales", "Pays de Galles", "Galles", "Walia", "Wallis").forEach { add("GB-WLS", it) }
        listOf("Northern Ireland", "Irlanda del Norte", "Irlande du Nord", "Nordirland", "Irlanda del Nord", "Noord-Ierland").forEach { add("GB-NIR", it) }
        add("PF", "Tahiti")
        listOf("USA", "US", "United States of America").forEach { add("US", it) }
        add("CZ", "Czech Republic")
        add("MK", "Macedonia")
        Table(byCore, names)
    }
}

internal object LinkerAliases {
    val generic: Set<String> = (
        "fc cf sc afc ac as cd ca club deportivo real sporting athletic atletico united city town county state university " +
            "st saint los las new de del la el the of and sk fk bk if ik islands island"
        ).split(' ').toSet()

    fun allGeneric(alias: String): Boolean = alias.split(' ').all { it in generic }

    /** `fullOnly` is used for description matching, where short names are too ambiguous. */
    fun of(team: LinkerTeam, national: Boolean, fullOnly: Boolean = false): Set<String> {
        val full = LinkerText.clean(team.name)
        val short = LinkerText.clean(team.short.orEmpty())
        val nick = LinkerText.clean(team.nick.orEmpty())
        val city = LinkerText.clean(team.city.orEmpty())
        val aliases = HashSet<String>()
        aliases += full
        if (!fullOnly) {
            for (candidate in listOf(short, nick)) if (candidate.length >= 4 && !allGeneric(candidate)) aliases += candidate
            if (city.isNotEmpty() && nick.isNotEmpty() && !allGeneric(nick)) aliases += "$city $nick"
        }
        if (national) {
            val table = LinkerCountryNames.table
            val code = table.byCore[LinkerCountryNames.core(team.name)] ?: table.byCore[LinkerCountryNames.core(team.short.orEmpty())]
            if (code != null) table.names[code]?.let(aliases::addAll)
        }
        for (alias in aliases.toList()) {
            val stripped = LinkerText.withoutConnectors(alias)
            if (stripped != alias) aliases += stripped
        }
        return aliases.filterTo(HashSet()) { it.length >= 3 && !allGeneric(it) }
    }
}

internal object LinkerPatterns {
    private fun re(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    val separator = re("""\s(?:vs\.?|v\.?|versus|at|@|c\.|x|contre|gegen|contra|-|–|—)\s""")
    val placeholder = re("""^\s*(?:no\s+(?:game|match|event)s?(?:\s+today|\s+scheduled)?|next\s+(?:game|match)|teams?\s+tba|to\s+be\s+announced|tbd|off\s+air|programming\s+resumes|sign\s*off|check\s+local)""")
    val replay = re("""\b(?:replay|re-?run|re-?air|encore|classics?|highlights?|condensed|recap|recorded|tape[- ]delay(?:ed)?|delayed|throwback|archive|best\s+of|resumen|zusammenfassung|magazine|melhores\s+momentos)\b""")
    val coverage = re("""\b(?:in-?game|pre-?game|post-?game|pre-?show|post-?show|preview|countdown|analysis|studio|scoreboard|tonight|whip|betting|odds|picks|fantasy|talk|weekly|report|daily|live\s+look|coverage\s+of)\b""")
    val nextGame = re("""next\s+(?:game|match)\s*:?\s*(.+?)\s+on\s+(\d{4})-(\d{2})-(\d{2})""")
    val time = re("""\b(\d{1,2})(?::(\d{2}))?\s*(AM|PM)\s*(ET|EST|EDT|CT|CST|CDT|MT|PT|PST|PDT|UK|GMT|BST|CET|CEST)?\b""")
    val iso = re("""\b(20\d{2})-(\d{2})-(\d{2})(?:\s+(\d{2}):(\d{2}))?""")
    val bracketQuality = re("""\[(?:HD|FHD|UHD|SD|4K|BACKUP|BK)]""")
    val sportsCategory = re("""\b(teams?|nhl|nba|mlb|nfl|mls|sports?|wnba)\b""")
}
