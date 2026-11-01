package com.alexdiab.stadiatv.lineup

import java.text.Normalizer
import java.util.Locale

/**
 * Text normalisation for channel identity. Provider names ("CA ★ TSN 1 HD"), guide ids ("CTVOttawa-CJOH.ca") and the
 * catalogue's own names all have to meet in the same form, so spaces, case, accents, "&" and "+" are made irrelevant:
 * "A&E", "AandE" and "A and E" are one key, and so are "TSN 1" and "TSN1".
 */
object IdentityText {
    private val MARKS = Regex("\\p{M}+")
    private val SEPARATORS = Regex("[^a-z0-9]+")

    /** Words that decorate a channel name without identifying it. */
    private val GENERIC_TAIL = setOf("channel", "network", "tv", "television", "canada", "canadian")

    fun tokens(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace("+", " plus ")
        return SEPARATORS.split(folded).filter { it.isNotEmpty() }
    }

    fun compact(text: String?): String = tokens(text).joinToString("")

    /**
     * Every spelling of a name worth matching on, most specific first: the full name, without a leading "the", and with
     * decorative words dropped from the end ("The Weather Channel" -> "theweatherchannel", "weatherchannel", "weather").
     */
    fun aliasKeys(text: String?): List<String> = variants(tokens(text))

    fun variants(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        val bases = if (tokens.size > 1 && tokens.first() == "the") listOf(tokens, tokens.drop(1)) else listOf(tokens)
        for (base in bases) {
            var current = base
            out += current.joinToString("")
            while (current.size > 1 && current.last() in GENERIC_TAIL) {
                current = current.dropLast(1)
                out += current.joinToString("")
            }
        }
        out.remove("")
        return out.toList()
    }
}
