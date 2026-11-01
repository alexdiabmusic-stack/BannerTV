package com.alexdiab.stadiatv.lineup

import androidx.compose.runtime.Immutable

/**
 * What a stream or a guide channel *is*, independent of how its name happens to be spelled: which catalogue channel
 * (if any), which local station for network affiliates, which country's feed, and which coast.
 */
@Immutable
class Identity(
    val catalog: CatalogChannel?,
    /** The city of a local station of a network ("ottawa", "new york", "st louis"); empty for national channels. */
    val market: String,
    /** State or province code of the market ("oh", "ms"), lowercase, when the name gave one. */
    val region: String,
    /** Station callsign (CJOH, WABC) when the name or id carries one. */
    val callsign: String?,
    /** Country of the feed ("CA", "US", "GB" ...), null when unknown. */
    val feedCountry: String?,
    /** "west", "pacific" ... for the later coast feeds, "news" for a local station's news stream; empty for the main feed. */
    val variant: String,
    /** The channel's name reduced to a comparable key, for channels outside the catalogue. */
    val nameKey: String,
) {
    val marketTokens: Set<String> get() = if (market.isEmpty()) emptySet() else market.split(' ').toSet()

    fun withVariant(variant: String) = if (variant == this.variant) this else Identity(catalog, market, region, callsign, feedCountry, variant, nameKey)

    /** The words of the market that name it ("louis" in "st louis"), so "St. Louis" and "St. Paul" don't look alike. */
    private val significantTokens: Set<String>
        get() = marketTokens.filterTo(HashSet()) { it !in GENERIC_PLACE }.ifEmpty { marketTokens }

    private fun sharesPlaceWith(other: Identity): Boolean = significantTokens.any { it in other.significantTokens }

    /** Streams that share this key are the same channel at different qualities and share one guide. */
    val groupKey: String
        get() = if (catalog != null) "c:${catalog.id}|${feedCountry.orEmpty()}|$market|$region|${callsign.orEmpty()}|$variant" else "g:${feedCountry.orEmpty()}|$nameKey|$variant"

    /** The key of the lineup entry: one per channel and place, whatever the feed or quality. */
    val entryKey: String
        get() = when {
            catalog == null -> "g:${feedCountry.orEmpty()}|$nameKey|$variant"
            // A bare state code only tells apart the cities that share a name (Columbus OH / MS).
            else -> "c:${catalog.id}|$market${if (market in AMBIGUOUS_CITIES) " $region" else ""}|${if (market.isEmpty()) callsign.orEmpty() else ""}|$variant"
        }

    /**
     * Could a guide channel with identity [other] be this channel's schedule? False only on clear evidence otherwise:
     * two different catalogue channels, or the same network in two different places. Anything the catalogue doesn't
     * know is given the benefit of the doubt, because providers' ids for obscure channels are mostly right.
     */
    fun compatibleWith(other: Identity): Boolean {
        val mine = catalog
        val theirs = other.catalog
        if (mine == null || theirs == null) return true
        if (mine.id != theirs.id) return false
        if (!mine.hasLocalStations) return true
        if ((variant == "news") != (other.variant == "news")) return false
        if (callsign != null && other.callsign != null) return callsign == other.callsign
        if (region.isNotEmpty() && other.region.isNotEmpty() && region != other.region) return false
        val a = marketTokens
        val b = other.marketTokens
        if (a.isEmpty() || b.isEmpty()) return true
        return sharesPlaceWith(other)
    }

    /**
     * Is [other] the same station, on the evidence rather than the benefit of the doubt? Used when *searching* the guide
     * for a channel's schedule: the same callsign, or the same place, or (for a national feed) another national feed.
     */
    fun sameStationAs(other: Identity): Boolean {
        if (!compatibleWith(other)) return false
        val mine = catalog ?: return true
        if (!mine.hasLocalStations) return variant == other.variant
        if (variant != other.variant && (variant == "news" || other.variant == "news")) return false
        if (callsign != null && callsign == other.callsign) return variant == other.variant || variant.isEmpty() && other.variant.isEmpty()
        if (market.isEmpty()) return other.market.isEmpty() && other.callsign == null && variant == other.variant
        return sharesPlaceWith(other) && variant == other.variant
    }

    companion object {
        /** City names shared by several states; for these the state is part of the identity. */
        /** Words that appear in many place names and say nothing about which one. */
        private val GENERIC_PLACE = setOf("st", "saint", "fort", "ft", "new", "san", "santa", "los", "las", "el", "la", "north", "south", "east", "west", "mount", "mt", "palm", "city", "lake", "beach", "springs", "falls", "valley", "grand", "port", "bay", "de", "of", "and")

        val AMBIGUOUS_CITIES = setOf("columbus", "portland", "springfield", "charleston", "jackson", "richmond", "columbia", "wilmington", "augusta", "kansas city", "lexington", "greenville", "rochester", "albany", "birmingham", "peoria", "salem", "savannah", "lincoln")
    }
}

/**
 * Maps names onto the [ChannelCatalog]. The same logic reads a provider stream name ("CA ★ CTV OTTAWA HD") and a guide
 * channel id ("CTVOttawa-CJOH.ca"), so a stream's guide can be checked against what the guide channel claims to be.
 */
class CatalogMatcher(catalog: List<CatalogChannel> = ChannelCatalog.all) {
    private val byKey: Map<String, List<CatalogChannel>>
    private val localsByKey: Map<String, List<CatalogChannel>>
    private val localKeysLongestFirst: List<String>

    init {
        val keys = HashMap<String, MutableList<CatalogChannel>>()
        val locals = HashMap<String, MutableList<CatalogChannel>>()
        for (channel in catalog) {
            for (key in channel.aliasKeys) {
                keys.getOrPut(key) { ArrayList(1) } += channel
                if (channel.hasLocalStations) locals.getOrPut(key) { ArrayList(1) } += channel
            }
        }
        byKey = keys
        localsByKey = locals
        localKeysLongestFirst = locals.keys.sortedByDescending { it.length }
    }

    fun identify(name: StreamName, epgId: String? = null): Identity {
        val feedCountry = name.prefixCountry ?: epgCountry(epgId)
        val readings = listOf(name.tokens) + name.alternates
        val hints = name.hints.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
        val hinted = coastIn(hints)
        // The name as written first: "Sportsnet East" and "Comedy Central" are channels, not a channel plus a coast.
        for ((index, tokens) in readings.withIndex()) {
            val channel = exact(tokens, feedCountry, name.french) ?: continue
            // "MYTV 7 - WSYX COLUMBUS OH": the network is one half and the station the other.
            val others = readings.filterIndexed { i, _ -> i != index }.flatten()
            return shaped(channel, others, hints, feedCountry, hinted, tokens)
        }
        // Then without a trailing coast word: "ABC HD EAST" is the East feed of ABC.
        val stripped = readings.map(::withoutCoast)
        for ((tokens, coast) in stripped) {
            if (coast == null) continue
            val channel = exact(tokens, feedCountry, name.french) ?: continue
            return shaped(channel, emptyList(), hints, feedCountry, hinted.ifEmpty { coast.second }, tokens)
        }
        // A network followed by a place: "CTV Ottawa", "ABC 7 Chicago".
        for ((index, reading) in stripped.withIndex()) {
            val (tokens, coast) = reading
            val (channel, rest) = local(tokens, feedCountry, name.french) ?: continue
            if (rest.any { it in NOT_A_MARKET }) continue
            val others = readings.filterIndexed { i, _ -> i != index }.flatten()
            return shaped(channel, rest + others, hints, feedCountry, hinted.ifEmpty { coast?.second.orEmpty() }, tokens)
        }
        val (bare, coast) = stripped.first()
        return Identity(null, "", "", null, feedCountry, hinted.ifEmpty { coast?.second.orEmpty() }, key(bare))
    }

    /** An identity that never matches the catalogue: a free streaming channel is not the cable channel it is named after. */
    fun uncatalogued(name: StreamName, epgId: String? = null): Identity {
        val (bare, coast) = withoutCoast(name.tokens)
        return Identity(null, "", "", null, name.prefixCountry ?: epgCountry(epgId), coast?.second.orEmpty(), key(bare))
    }

    /** Identity of a guide channel from its id and display names. */
    fun identifyGuideChannel(id: String, displayNames: List<String>): Identity {
        val (stem, country) = splitGuideId(id)
        val callsigns = ArrayList<String>()
        val words = ArrayList<String>()
        val parts = stem.split(GUIDE_SEPARATORS).filter { it.isNotBlank() }
        for ((index, part) in parts.withIndex()) {
            val low = part.lowercase()
            when {
                low in GUIDE_NOISE -> Unit
                index > 0 && isCallsign(low, allowThreeLetters = true) -> callsigns += normalCallsign(low)
                else -> words += camelWords(part).flatMap { IdentityText.tokens(it) }
            }
        }
        val fromId = identify(StreamName(id, country, false, words, emptyList(), Quality.Sd, id, emptyList(), false), null)
        val callsign = fromId.callsign ?: callsigns.firstOrNull()
        var best = fromId.withCallsign(callsign)
        if (best.catalog == null) compactNetworkAndCallsign(IdentityText.compact(stem), country)?.let { best = it }
        if (best.catalog == null) {
            for (display in displayNames) {
                val named = identify(StreamName.parse(display), id)
                if (named.catalog != null) { best = named.withCallsign(best.callsign ?: named.callsign); break }
            }
            return best
        }
        // The id knows the channel; the provider's display name usually also knows the city ("ABC 25 (WJXX) JACKSONVILLE").
        if (best.catalog!!.hasLocalStations && best.market.isEmpty()) {
            for (display in displayNames) {
                val named = identify(StreamName.parse(display), id)
                if (named.catalog?.id == best.catalog!!.id && named.market.isNotEmpty() && (best.callsign == null || named.callsign == null || named.callsign == best.callsign)) {
                    best = Identity(best.catalog, named.market, named.region, best.callsign ?: named.callsign, best.feedCountry ?: named.feedCountry, best.variant.ifEmpty { named.variant }, best.nameKey)
                    break
                }
            }
        }
        return best
    }

    private fun Identity.withCallsign(callsign: String?) = if (callsign == this.callsign) this else Identity(catalog, market, region, callsign, feedCountry, variant, nameKey)

    private fun compactNetworkAndCallsign(compact: String, country: String?): Identity? {
        for (key in localKeysLongestFirst) {
            if (!compact.startsWith(key) || compact.length <= key.length) continue
            val rest = compact.substring(key.length)
            if (!isCallsign(rest, allowThreeLetters = true)) continue
            val channel = localsByKey.getValue(key).firstOrNull { compatibleCountry(it, country) } ?: continue
            return Identity(channel, "", "", normalCallsign(rest), country, "", compact)
        }
        return null
    }

    // region lookup

    private fun exact(tokens: List<String>, feedCountry: String?, french: Boolean): CatalogChannel? {
        if (tokens.isEmpty()) return null
        for (key in IdentityText.variants(tokens)) {
            val candidates = byKey[key]?.filter { compatibleCountry(it, feedCountry) } ?: continue
            if (candidates.isNotEmpty()) return best(candidates, feedCountry, french)
        }
        return null
    }

    private fun local(tokens: List<String>, feedCountry: String?, french: Boolean): Pair<CatalogChannel, List<String>>? {
        for (take in minOf(4, tokens.size) downTo 1) {
            val key = tokens.take(take).joinToString("")
            val candidates = localsByKey[key]?.filter { compatibleCountry(it, feedCountry) } ?: continue
            if (candidates.isNotEmpty()) return best(candidates, feedCountry, french) to tokens.drop(take)
        }
        return null
    }

    private fun best(candidates: List<CatalogChannel>, feedCountry: String?, french: Boolean): CatalogChannel =
        candidates.sortedWith(
            compareByDescending<CatalogChannel> { it.country == feedCountry }
                .thenByDescending { it.isFrench == french }
                .thenBy { it.tier.ordinal },
        ).first()

    /** Canadian and US lineups carry each other's channels; a feed from any third country only matches what lists it. */
    private fun compatibleCountry(channel: CatalogChannel, feedCountry: String?): Boolean = when {
        feedCountry == null || channel.country.isEmpty() -> true
        feedCountry in channel.feedCountries -> true
        feedCountry in NORTH_AMERICA && channel.country in NORTH_AMERICA -> true
        else -> false
    }

    // endregion

    // region stations, coasts, callsigns

    /**
     * The identity of a matched channel. A network with local stations is a *station* when the name says where
     * ("ABC 7 Chicago", "CBS 9 [Greenville NC] (WNCT)", "ABC 12 WJRT") and the national feed when it doesn't.
     */
    private fun shaped(channel: CatalogChannel, rest: List<String>, hints: List<String>, feedCountry: String?, coast: String, tokens: List<String>): Identity {
        if (!channel.hasLocalStations) return Identity(channel, "", "", null, feedCountry, coast, key(tokens))
        var callsign: String? = null
        var news = false
        val place = ArrayList<String>()
        fun read(word: String, fromHint: Boolean) {
            when {
                word.all(Char::isDigit) -> Unit
                word in VARIANT_WORDS || word in MARKET_NOISE -> Unit
                word == "news" -> news = true
                callsign == null && word !in CITY_LOOKALIKES && isCallsign(word, allowThreeLetters = fromHint) -> callsign = normalCallsign(word)
                else -> place += PLACE_SPELLING[word] ?: word
            }
        }
        rest.forEach { read(it, false) }
        hints.forEach { read(it, true) }
        var region = ""
        if (place.size >= 2 && place.last() in REGIONS) region = place.removeAt(place.lastIndex)
        // A US station's news stream is a channel of its own; Canadian stations air their news on the same channel.
        val variant = if (news && channel.country == "US") "news" else coast
        return Identity(channel, place.joinToString(" "), region, callsign, feedCountry, variant, key(tokens))
    }

    /** A name without a trailing coast word: ("abc", "east" -> ""), ("hbo", "west" -> "west"); null when it has none. */
    private fun withoutCoast(tokens: List<String>): Pair<List<String>, Pair<String, String>?> {
        val last = tokens.lastOrNull()
        if (tokens.size < 2 || last == null || last !in VARIANT_WORDS) return tokens to null
        return tokens.dropLast(1) to (last to normalCoast(last))
    }

    private fun coastIn(words: List<String>): String = words.firstOrNull { it in VARIANT_WORDS }?.let(::normalCoast).orEmpty()

    /** East is the main feed, so it is not a variant; "eastern"/"western" are the same words. */
    private fun normalCoast(word: String) = when (word) {
        "east", "eastern" -> ""
        "western" -> "west"
        else -> word
    }

    private fun key(tokens: List<String>) = tokens.joinToString("")

    private fun epgCountry(id: String?): String? {
        if (id.isNullOrBlank() || !id.contains('.')) return null
        return countryOfSuffix(id.substringAfterLast('.'))
    }

    private fun countryOfSuffix(suffix: String): String? {
        val code = suffix.lowercase().trimEnd { it.isDigit() }.substringBefore('_')
        return when {
            code == "uk" || code == "gb" -> "GB"
            code.length == 2 && code.all(Char::isLetter) -> code.uppercase()
            else -> null
        }
    }

    /** "CTVOttawa-CJOH.ca" -> ("CTVOttawa-CJOH", CA); "Bravo.(Pacific).us2" -> ("Bravo.(Pacific)", US); "KYTX-DT.us_locals1". */
    private fun splitGuideId(id: String): Pair<String, String?> {
        val match = GUIDE_SUFFIX.find(id) ?: return id to null
        return id.substring(0, match.range.first) to countryOfSuffix(match.groupValues[1])
    }

    /** "CTVOttawa" -> [CTV, Ottawa]; "GlobalToronto" -> [Global, Toronto]; "TSN1" stays whole. */
    private fun camelWords(text: String): List<String> =
        text.replace(CAMEL_LOWER_UPPER, "$1 $2").replace(CAMEL_ACRONYM, "$1 $2").split(' ').filter { it.isNotBlank() }

    // endregion

    private companion object {
        val NORTH_AMERICA = setOf("CA", "US")
        val VARIANT_WORDS = setOf("east", "west", "eastern", "western", "pacific", "central", "mountain")
        val MARKET_NOISE = setOf("hd", "fhd", "sd", "tv", "local", "station", "channel", "network", "affiliate", "the", "of", "dt")
        val GUIDE_NOISE = setOf("hd", "fhd", "sd", "dt", "tv", "us", "ca")
        val NOT_A_MARKET = setOf(
            "soul", "sports", "sport", "deportes", "weather", "business", "movies", "movie", "kids", "extra", "spark", "plus", "classics", "family",
            "hits", "drama", "comedy", "mystery", "nation", "now", "live", "zone", "life", "toons", "junior", "jr",
        )
        /** Four-letter words that look like callsigns but are places. */
        val CITY_LOOKALIKES = setOf("waco", "kent", "wake", "cary", "wall", "kona", "keys", "cape", "cook", "wise", "city", "chat", "chip", "kirk", "kyle", "kern", "knox", "walk", "ware", "wood", "wild", "wind", "wine", "wing", "kuna", "wasco", "kenai")
        val PLACE_SPELLING = mapOf("saint" to "st", "ft" to "fort", "mt" to "mount")
        val REGIONS = setOf(
            "al", "ak", "az", "ar", "ca", "co", "ct", "de", "dc", "fl", "ga", "hi", "id", "il", "in", "ia", "ks", "ky", "la", "me", "md", "ma", "mi", "mn", "ms", "mo",
            "mt", "ne", "nv", "nh", "nj", "nm", "ny", "nc", "nd", "oh", "ok", "or", "pa", "ri", "sc", "sd", "tn", "tx", "ut", "vt", "va", "wa", "wv", "wi", "wy",
            "on", "qc", "bc", "ab", "sk", "mb", "nb", "ns", "pe", "pei", "nl",
        )
        val GUIDE_SEPARATORS = Regex("[-_.()\\s]+")
        val GUIDE_SUFFIX = Regex("\\.([a-zA-Z]{2,3}\\d?(?:_[a-zA-Z0-9]+)?)$")
        private val CAMEL_LOWER_UPPER = Regex("([a-z])([A-Z])")
        private val CAMEL_ACRONYM = Regex("([A-Z]+)([A-Z][a-z])")
        private val CALLSIGN_4 = Regex("^(?:[kw][a-z]{2,3}|c[bfhijkt][a-z]{2})(?:dt\\d?|tv|hd)?\\d?$")
        private val CALLSIGN_3 = Regex("^(?:[kw][a-z]{2}|c[bfhijkt][a-z])$")
        private val CALLSIGN_SUFFIX = Regex("(dt\\d?|tv|hd)\\d?$")

        /** US callsigns start with K or W, Canadian with C; "WJRT", "CFJP", "WABCDT2". Three letters (WGN, KGO) only where the text says it is one. */
        fun isCallsign(word: String, allowThreeLetters: Boolean): Boolean {
            val lower = word.lowercase()
            if (lower.length < 3 || !lower.all { it.isLetterOrDigit() } || lower.all { it.isDigit() }) return false
            return CALLSIGN_4.matches(lower) && lower.length >= 4 || allowThreeLetters && CALLSIGN_3.matches(lower)
        }

        /** "wabcdt2" -> "WABC"; "kttv" stays whole because stripping its "tv" would leave two letters. */
        fun normalCallsign(word: String): String {
            val lower = word.lowercase()
            val base = lower.replace(CALLSIGN_SUFFIX, "")
            return (if (base.length >= 3) base else lower).uppercase()
        }
    }
}
