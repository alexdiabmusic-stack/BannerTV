package com.alexdiab.stadiatv.playback

import java.util.Locale
import kotlin.math.min

/** Preferences name content, never an index in a provider's current track array. */
object PlaybackLanguages {
    private val aliases = mapOf("eng" to "en", "fra" to "fr", "fre" to "fr", "spa" to "es", "deu" to "de", "ger" to "de", "ita" to "it", "por" to "pt", "nld" to "nl", "dut" to "nl", "zho" to "zh", "chi" to "zh", "jpn" to "ja", "kor" to "ko", "rus" to "ru", "ara" to "ar", "hin" to "hi", "pan" to "pa", "urd" to "ur", "tam" to "ta", "ben" to "bn", "ell" to "el", "gre" to "el", "heb" to "he", "ind" to "id")
    private val isoLanguages by lazy { Locale.getISOLanguages().mapNotNull { language -> runCatching { Locale.forLanguageTag(language).isO3Language to language }.getOrNull() }.toMap() + aliases }
    fun normalize(raw: String?): String {
        val value = raw.orEmpty().trim().replace('_', '-')
        if (value.length > 64 || !value.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*"))) return ""
        val pieces = value.split('-'); val language = pieces.first().lowercase(Locale.ROOT)
        if (language in setOf("und", "zxx", "mul")) return ""
        return Locale.forLanguageTag((listOf(isoLanguages[language] ?: language) + pieces.drop(1)).joinToString("-")).toLanguageTag().takeUnless { it == "und" }.orEmpty()
    }
    fun label(raw: String): String = normalize(raw).takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it).displayName } ?: "Automatic"
    fun preferred(tracks: List<TrackOption>, language: String): TrackOption? {
        val wanted = normalize(language)
        val exact = tracks.filter { wanted.isNotEmpty() && normalize(it.language) == wanted }
        val related = tracks.filter { wanted.isNotEmpty() && normalize(it.language).substringBefore('-') == wanted.substringBefore('-') }
        val choices = exact.ifEmpty { related }.ifEmpty { tracks }
        return choices.firstOrNull { it.selected } ?: choices.firstOrNull()
    }
}

object PlaybackStartupPolicy {
    fun shouldRecover(firstFrame: Boolean, playing: Boolean, paused: Boolean): Boolean = !firstFrame && !playing && !paused
}

data class VideoFrameSize(val width: Float, val height: Float)

object VideoSizing {
    /** Physical display pixels on both sides; callers convert only the result to dp. No upscaling. */
    fun original(width: Int, height: Int, pixelRatio: Float, availableWidth: Float, availableHeight: Float): VideoFrameSize {
        val available = VideoFrameSize(availableWidth.takeIf { it.isFinite() && it > 0 } ?: 0f, availableHeight.takeIf { it.isFinite() && it > 0 } ?: 0f)
        if (width <= 0 || height <= 0 || !availableWidth.isFinite() || !availableHeight.isFinite()) return available
        val naturalWidth = width * (pixelRatio.takeIf { it.isFinite() && it > 0 } ?: 1f)
        if (!naturalWidth.isFinite()) return available
        val scale = min(1f, min(available.width / naturalWidth, available.height / height))
        return VideoFrameSize(naturalWidth * scale, height * scale)
    }
}

/** A menu from an old source cannot select a coincidentally reused index in a new stream. */
object TrackSelectionIdentity {
    fun encode(generation: Int, group: Int, track: Int) = "$generation:$group:$track"
    fun indices(id: String, generation: Int): Pair<Int, Int>? {
        val values = id.split(':').map { it.toIntOrNull() }
        if (values.size != 3 || values[0] != generation || values.any { it == null || it < 0 }) return null
        return values[1]!! to values[2]!!
    }
}
