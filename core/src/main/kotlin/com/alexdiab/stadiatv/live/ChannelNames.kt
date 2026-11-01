package com.alexdiab.stadiatv.live

import java.text.Normalizer
import java.util.Locale

/** Channel-name normalisation shared by guide matching and IPTV-org metadata (port of `ChannelMetadataRepository.normalize`). */
object ChannelNames {
    private val BRACKETS = Regex("\\[.{1,40}?]")
    private val PREFIX = Regex("^[A-Z]{2,4}\\s*[★*⭐]\\s*")
    private val SYMBOLS = Regex("[★◉⭐●◆▶►◀◄◈◇⊕⊗⊘☆✦✧]")
    private val QUALITY = Regex("\\b(4k|uhd|fhd|hd|sd|1080p?|720p?|480p?|hevc|h265|backup)\\b", RegexOption.IGNORE_CASE)
    private val TRAILING_NUMBER = Regex("\\s+\\d{3,}\\s*:?[ ]*$")
    private val MARKS = Regex("\\p{M}")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val COUNTRY_PREFIX = Regex("^([A-Z]{2,4})\\s*[★*⭐]")
    private val SPACES = Regex("\\s+")

    fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        var result = BRACKETS.replace(value, " ")
        result = PREFIX.replace(result, "")
        result = SYMBOLS.replace(result, " ")
        result = QUALITY.replace(result, " ")
        result = TRAILING_NUMBER.replace(result, "")
        result = MARKS.replace(Normalizer.normalize(result, Normalizer.Form.NFD), "")
        return NON_ALNUM.replace(result, " ").trim().replace(SPACES, " ")
    }

    fun key(value: String?): String = normalize(value).lowercase(Locale.US)

    fun countryHint(value: String?): String {
        val prefix = COUNTRY_PREFIX.find(value?.trim().orEmpty())?.groupValues?.get(1) ?: return ""
        return when {
            prefix == "CAF" || prefix == "CA" -> "CA"
            prefix == "UK" -> "GB"
            prefix.length == 2 -> prefix
            else -> ""
        }
    }
}
