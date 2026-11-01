package com.alexdiab.stadiatv.lineup

import androidx.compose.runtime.Immutable

/** How much a channel matters to a guide: essentials first, then recommended, then optional expansion. */
enum class Tier { Essential, Recommended, Optional }

/**
 * What a channel is for. One channel can carry several (a broadcast network that also airs sport, a French
 * sports channel). The guide's filters are built from these plus the channel's country and language.
 */
enum class Genre(val label: String) {
    Broadcast("Broadcast"),
    Sports("Sports"),
    News("News"),
    Business("Business"),
    Weather("Weather"),
    Entertainment("Entertainment"),
    Drama("Drama"),
    Movies("Movies"),
    Premium("Premium"),
    Documentary("Documentary"),
    Lifestyle("Lifestyle"),
    Kids("Kids"),
    Culture("Culture"),
    Music("Music"),
    Shopping("Shopping"),
    Faith("Faith"),
    Fast("Free streaming"),
    Regional("Regional sports"),
}

/** One channel in the master lineup. [id] is stable, so favourites and settings can refer to it. */
@Immutable
class CatalogChannel(
    val id: String,
    val name: String,
    /** The country the channel belongs to: "CA", "US" or "" for international. */
    val country: String,
    val language: String,
    val genres: Set<Genre>,
    val tier: Tier,
    /** Normalised names providers use for it (see [IdentityText.compact]). */
    val aliasKeys: Set<String>,
    /** Countries whose provider feeds may legitimately carry it (a US network on a Canadian lineup, say). */
    val feedCountries: Set<String>,
    /**
     * A broadcast network with local stations: provider feeds are "CTV Ottawa" or "ABC 7 Chicago", and the
     * market is part of the identity, so two cities never share a schedule.
     */
    val hasLocalStations: Boolean = false,
    /** Airs live sport from time to time, so the Sports filter includes it while a game is on. */
    val sportsCapable: Boolean = false,
    /** What a local station is called with its city after it: "CBC" in "CBC Ottawa". */
    val shortName: String = name,
) {
    val isFrench: Boolean get() = language == "fr"
    override fun toString() = "CatalogChannel($id)"
}

/** Builds the catalogue. Kept tiny so the data file below reads like the lineup it describes. */
internal class CatalogBuilder {
    private val channels = ArrayList<CatalogChannel>()
    private val ids = HashSet<String>()

    fun build(): List<CatalogChannel> = channels.toList()

    /**
     * @param aliases extra names providers use, separated by `|`. The channel's own name is always included.
     * @param feeds countries whose feeds may carry it, in addition to its own.
     */
    fun add(
        name: String,
        country: String,
        tier: Tier,
        vararg genres: Genre,
        aliases: String = "",
        language: String = "en",
        feeds: String = "",
        locals: Boolean = false,
        sports: Boolean = false,
        short: String? = null,
        id: String? = null,
    ) {
        val slug = id ?: (country.lowercase() + "." + IdentityText.compact(name))
        require(ids.add(slug)) { "duplicate catalogue id $slug" }
        val keys = LinkedHashSet<String>()
        (listOf(name) + aliases.split('|').filter { it.isNotBlank() }).forEach { alias -> keys += IdentityText.aliasKeys(alias) }
        val feedSet = buildSet {
            if (country.isNotEmpty()) add(country)
            feeds.split('|').filter { it.isNotBlank() }.forEach(::add)
        }
        channels += CatalogChannel(slug, name, country, language, genres.toSet(), tier, keys, feedSet, locals, sports, short ?: name)
    }

    fun ca(name: String, tier: Tier, vararg genres: Genre, aliases: String = "", fr: Boolean = false, feeds: String = "", locals: Boolean = false, sports: Boolean = false, short: String? = null) =
        add(name, "CA", tier, *genres, aliases = aliases, language = if (fr) "fr" else "en", feeds = feeds, locals = locals, sports = sports, short = short)

    fun us(name: String, tier: Tier, vararg genres: Genre, aliases: String = "", es: Boolean = false, feeds: String = "", locals: Boolean = false, sports: Boolean = false, short: String? = null) =
        add(name, "US", tier, *genres, aliases = aliases, language = if (es) "es" else "en", feeds = feeds, locals = locals, sports = sports, short = short)

    /** A channel numbered 1..n whose members share a family name ("TSN 1" ... "TSN 5"). */
    fun ca(family: String, numbers: IntRange, tier: Tier, vararg genres: Genre, aliases: String = "", fr: Boolean = false, sports: Boolean = false) {
        for (n in numbers) ca("$family $n", tier, *genres, aliases = aliases.split('|').filter { it.isNotBlank() }.joinToString("|") { "$it $n" }, fr = fr, sports = sports)
    }

    fun us(family: String, numbers: IntRange, tier: Tier, vararg genres: Genre, aliases: String = "", sports: Boolean = false) {
        for (n in numbers) us("$family $n", tier, *genres, aliases = aliases.split('|').filter { it.isNotBlank() }.joinToString("|") { "$it $n" }, sports = sports)
    }
}
