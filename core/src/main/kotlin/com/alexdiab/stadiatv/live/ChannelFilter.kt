package com.alexdiab.stadiatv.live

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.util.Locale

@Immutable
data class ChannelFilter(
    val favoritesOnly: Boolean = false,
    val playlistIds: Set<String> = emptySet(),
    val categories: Set<String> = emptySet(),
    val qualities: Set<String> = emptySet(),
    val languages: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() = listOf(favoritesOnly, playlistIds.isNotEmpty(), categories.isNotEmpty(), qualities.isNotEmpty(), languages.isNotEmpty()).count { it }
}

/** Everything the filter needs about one channel, computed once so typing in search never re-reads 30,000 names. */
@Immutable
class IndexedChannel(
    val channel: Channel,
    val search: String,
    val categories: Set<String>,
    val quality: String,
    val languages: Set<String>,
)

object ChannelFilterEngine {
    val CATEGORIES = listOf("Sports", "News", "Entertainment", "Movies", "Series", "Kids", "Game Shows", "Music", "Documentary", "Lifestyle", "International", "Radio", "Adult", "Other")
    val QUALITIES = listOf("4K", "FHD", "HD", "SD")

    fun index(channels: List<Channel>): List<IndexedChannel> = channels.map { channel ->
        IndexedChannel(
            channel = channel,
            search = normalize("${channel.name} ${channel.group} ${channel.playlistName}"),
            categories = categories(channel),
            quality = quality(channel),
            languages = languageTags("${channel.name} ${channel.language.orEmpty()}"),
        )
    }

    /** AND between dimensions, OR within one dimension. */
    fun apply(index: List<IndexedChannel>, filter: ChannelFilter, query: String, favoriteKeys: Set<String>): List<Channel> {
        val needle = normalize(query)
        val out = ArrayList<Channel>()
        for (entry in index) {
            val channel = entry.channel
            if (filter.favoritesOnly && channel.key !in favoriteKeys && channel.id !in favoriteKeys) continue
            if (filter.playlistIds.isNotEmpty() && channel.playlistId !in filter.playlistIds) continue
            if (filter.categories.isNotEmpty() && entry.categories.none { it in filter.categories }) continue
            if (filter.qualities.isNotEmpty() && entry.quality !in filter.qualities) continue
            if (filter.languages.isNotEmpty() && entry.languages.none { it in filter.languages }) continue
            if (needle.isNotEmpty() && !entry.search.contains(needle)) continue
            out += channel
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
    }

    private val UHD_WORDS = setOf("4k", "uhd", "2160p")
    private val FHD_WORDS = setOf("fhd", "1080", "1080p")
    private val HD_WORDS = setOf("hd", "720", "720p")
    private val SD_WORDS = setOf("sd", "480p", "576p")

    // Compiled once: on Android a Regex with Unicode classes is expensive to build, and this runs for every channel.
    private val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}")

    fun quality(channel: Channel): String {
        val tokens = tokens(channel.name)
        return when {
            tokens.any { it in UHD_WORDS } -> "4K"
            tokens.any { it in FHD_WORDS } -> "FHD"
            tokens.any { it in HD_WORDS } -> "HD"
            tokens.any { it in SD_WORDS } -> "SD"
            else -> ""
        }
    }

    fun categories(channel: Channel): Set<String> {
        val text = normalize("${channel.group} ${channel.name} ${channel.playlistName}")
        val tokens = tokens(text)
        val out = LinkedHashSet<String>()
        fun match(category: String, vararg words: String) {
            if (words.any { (it.contains(' ') && text.contains(it)) || it in tokens }) out += category
        }
        match("Sports", "sports", "sport", "espn", "fox sports", "fs1", "fs2", "nba", "nfl", "mlb", "nhl", "pga", "golf", "soccer", "football", "tennis", "racing", "f1")
        match("News", "news", "cnn", "msnbc", "fox news", "bbc", "sky news", "cnbc", "bloomberg", "al jazeera", "cbc news", "ctv news", "weather")
        match("Entertainment", "entertainment", "general", "variety", "comedy", "drama", "bravo", "tbs", "tnt", "usa", "fx", "amc", "paramount")
        match("Movies", "movie", "movies", "cinema", "film", "hbo", "showtime", "starz", "cinemax", "mgm", "hallmark")
        match("Series", "series", "shows", "episodes", "box sets", "tv series")
        match("Kids", "kids", "children", "cartoon", "disney", "nick", "nickelodeon", "boomerang", "pbs kids", "baby")
        match("Game Shows", "game show", "gameshow", "gsn", "family feud", "jeopardy", "wheel of fortune")
        match("Music", "music", "mtv", "vh1", "vevo", "cmt")
        match("Documentary", "documentary", "history", "discovery", "national geographic", "nat geo", "science", "smithsonian", "animal planet")
        match("Lifestyle", "lifestyle", "food", "cooking", "travel", "home", "hgtv", "tlc", "fashion", "health", "fitness")
        match("International", "international", "world", "latino", "arabic", "asian", "europe", "canada", "france", "germany", "italy", "spain", "india")
        match("Radio", "radio", "fm", "am radio", "audio")
        match("Adult", "adult", "xxx", "18", "playboy")
        if (out.isEmpty()) out += "Other"
        return out
    }

    /** Language codes found as whole words in a channel name or its `tvg-language`. */
    fun languageTags(text: String): Set<String> {
        val decomposed = if (text.all { it.code < 128 }) text else Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "")
        val folded = decomposed.lowercase(Locale.US).replace(NOT_ALNUM, " ").trim()
        val words = folded.split(' ').toSet()
        val out = LinkedHashSet<String>()
        fun add(code: String, vararg aliases: String) { if (code in words || aliases.any { it in words }) out += code }
        add("en", "english", "eng")
        add("fr", "french", "fra", "francais")
        add("es", "spanish", "spa", "espanol")
        add("de", "german", "deu", "deutsch")
        add("it", "italian", "ita")
        add("pt", "portuguese", "por")
        add("ar", "arabic", "ara")
        add("nl", "dutch", "nederlands")
        add("tr", "turkish")
        add("pl", "polish", "polska")
        add("ru", "russian")
        add("el", "greek")
        return out
    }

    private fun tokens(value: String): Set<String> = normalize(value).split(' ').toSet()

    private fun normalize(value: String?): String =
        value?.lowercase(Locale.US)?.replace(NOT_ALNUM, " ")?.trim().orEmpty()
}
