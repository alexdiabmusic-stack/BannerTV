package com.alexdiab.stadiatv.ui.livetv

import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.data.UserSettings
import com.alexdiab.stadiatv.lineup.ChannelCatalog
import com.alexdiab.stadiatv.lineup.Genre
import com.alexdiab.stadiatv.lineup.GuideRef
import com.alexdiab.stadiatv.lineup.Lineup
import com.alexdiab.stadiatv.lineup.LineupEntry
import com.alexdiab.stadiatv.lineup.LineupPrefs
import com.alexdiab.stadiatv.lineup.LineupStore
import com.alexdiab.stadiatv.lineup.Pick
import com.alexdiab.stadiatv.lineup.Tier
import com.alexdiab.stadiatv.live.Programme
import java.util.Locale
import java.util.TimeZone

/** The chips along the top of the guide. */
enum class GuideFilter(val label: String) {
    Favorites("Favorites"), All("All"), Sports("Sports"), Canada("Canada"), Usa("USA"), French("French"), News("News"), Movies("Movies"), Kids("Kids"),
}

/** Sports sub-filters: a league, found in what is on now or next. "My teams" uses the viewer's followed teams instead. */
enum class SportsFilter(val label: String, val pattern: Regex?) {
    Any("All sports", null),
    Hockey("Hockey", Regex("\\b(nhl|hockey|ahl|khl|stanley cup|ohl|whl|qmjhl)\\b")),
    Basketball("Basketball", Regex("\\b(nba|wnba|basketball|ncaa basketball|march madness)\\b")),
    Football("Football", Regex("\\b(nfl|cfl|ncaa football|college football|american football|football night|monday night football)\\b")),
    Baseball("Baseball", Regex("\\b(mlb|baseball|blue jays|world series)\\b")),
    Soccer("Soccer", Regex("\\b(soccer|mls|premier league|la liga|bundesliga|serie a|ligue 1|uefa|champions league|fifa|concacaf|epl|efl|world cup)\\b")),
    Tennis("Tennis", Regex("\\b(tennis|atp|wta|wimbledon|us open|roland garros|australian open)\\b")),
    Golf("Golf", Regex("\\b(golf|pga|lpga|masters|ryder cup)\\b")),
    Racing("Racing", Regex("\\b(f1|formula 1|formula one|nascar|indycar|motogp|racing|grand prix|supercross)\\b")),
    Combat("Fighting", Regex("\\b(ufc|boxing|mma|wwe|wrestling|aew|pfl|bellator)\\b")),
    MyTeams("My teams", null),
}

/** One local-station area the viewer can choose as home. */
@Immutable
class MarketOption(val key: String, val label: String, val country: String)

@Immutable
class GuideRow(
    val entry: LineupEntry,
    val pick: Pick,
    val ref: GuideRef?,
    val now: Programme?,
    val next: Programme?,
    /** "News", "Hockey ..." : what the programme is, falling back to what the channel is. */
    val label: String,
    val isFavorite: Boolean,
    val isHidden: Boolean,
) {
    val key: String get() = entry.key
}

@Immutable
data class GuideOptions(
    val sports: SportsFilter = SportsFilter.Any,
    /** Show local stations from every area, not just the viewer's. */
    val allAreas: Boolean = false,
    val showHidden: Boolean = false,
    /** Edit mode: the order and visibility of channels. */
    val managing: Boolean = false,
)

@Immutable
class GuideUi(
    val rows: List<GuideRow> = emptyList(),
    val counts: Map<GuideFilter, Int> = emptyMap(),
    val homeMarket: String = "",
    val markets: List<MarketOption> = emptyList(),
    /** Countries in the lineup with their channel counts, for the "channels from" picker. */
    val countries: List<Pair<String, Int>> = emptyList(),
    val hiddenCount: Int = 0,
    val freeStreamingCount: Int = 0,
    val isEmpty: Boolean = true,
)

/** Everything that decides which channels the guide lists and in what order. Pure, so it is tested. */
object GuideLogic {
    fun build(
        lineup: Lineup,
        settings: UserSettings,
        prefs: LineupPrefs,
        filter: GuideFilter,
        options: GuideOptions,
        query: String,
        nowAndNext: (GuideRef, Long) -> Pair<Programme?, Programme?>,
        now: Long,
        timeZone: String = TimeZone.getDefault().id,
    ): GuideUi {
        val countries = LineupStore.countriesOf(settings)
        val home = settings.homeMarket.ifEmpty { marketForTimeZone(timeZone) }
        val needle = query.trim().lowercase(Locale.ROOT)
        val teams = settings.favoriteTeams.map { it.name.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }

        val markets = LinkedHashMap<String, MarketOption>()
        val countryCounts = HashMap<String, Int>()
        var hiddenCount = 0
        var freeCount = 0
        val rows = ArrayList<GuideRow>()
        val counts = HashMap<GuideFilter, Int>()

        for (entry in lineup.entries) {
            if (entry.isFast) {
                freeCount++
                if (!settings.showFreeStreaming && needle.isEmpty()) continue
            }
            if (entry.isLocalStation && entry.market.isNotEmpty()) {
                markets.getOrPut(entry.market) { MarketOption(entry.market, titleCase(entry.market), entry.country) }
            }
            if (entry.country.isNotEmpty()) countryCounts[entry.country] = (countryCounts[entry.country] ?: 0) + 1
            val inScope = entry.country in countries || entry.country.isEmpty() && entry.catalog != null
            if (!inScope && needle.isEmpty()) continue
            val hidden = entry.key in settings.hiddenEntryKeys
            if (hidden) hiddenCount++
            if (hidden && !options.showHidden) continue
            if (settings.hideKidsChannels && Genre.Kids in entry.genres && filter != GuideFilter.Kids) continue
            if (entry.language == "es" && !settings.showSpanishChannels && needle.isEmpty()) continue
            if (needle.isNotEmpty() && !matchesQuery(entry, needle)) continue
            if (needle.isEmpty() && !localToHome(entry, home, options.allAreas)) continue

            val row = row(entry, settings, prefs, nowAndNext, now)

            for (f in GuideFilter.entries) if (matches(f, row, teams, SportsFilter.Any)) counts[f] = (counts[f] ?: 0) + 1
            if (matches(filter, row, teams, options.sports)) rows += row
        }

        rows.sortWith(comparator(settings, prefs, home))
        val order = settings.entryOrder
        val ordered = if (order.isEmpty()) rows else {
            val index = order.withIndex().associate { it.value to it.index }
            rows.sortedBy { index[it.key] ?: Int.MAX_VALUE } // stable: the rest keep the default order
        }
        return GuideUi(
            rows = ordered,
            counts = counts,
            homeMarket = home,
            markets = markets.values.sortedWith(compareBy({ it.country != prefs.homeCountry }, { it.label })),
            countries = countryCounts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            hiddenCount = hiddenCount,
            freeStreamingCount = freeCount,
            isEmpty = lineup.entries.isEmpty(),
        )
    }

    /** One channel as the guide shows it: the stream that plays, what is on and next, and whether the viewer starred or hid it. */
    fun row(
        entry: LineupEntry,
        settings: UserSettings,
        prefs: LineupPrefs,
        nowAndNext: (GuideRef, Long) -> Pair<Programme?, Programme?>,
        now: Long,
    ): GuideRow {
        val pick = entry.pick(prefs)
        val ref = pick.guideId?.let { GuideRef(it, pick.guideOffsetMinutes, pick.guideSource) }
        val (current, next) = if (ref != null) nowAndNext(ref, now) else (null to null)
        val favorite = entry.key in settings.favoriteEntryKeys || entry.feeds.any { feed -> feed.streams.any { it.channel.key in settings.favoriteChannelIds } }
        return GuideRow(entry, pick, ref, current, next, labelFor(entry, current), favorite, entry.key in settings.hiddenEntryKeys)
    }

    /** Whether a row belongs under a guide chip; the same rule the guide's own chips use. */
    fun belongs(filter: GuideFilter, row: GuideRow, settings: UserSettings): Boolean =
        matches(filter, row, settings.favoriteTeams.map { it.name.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }, SportsFilter.Any)

    private fun matches(filter: GuideFilter, row: GuideRow, teams: List<String>, sports: SportsFilter): Boolean {
        val entry = row.entry
        return when (filter) {
            GuideFilter.Favorites -> row.isFavorite
            GuideFilter.All -> true
            GuideFilter.Sports -> isSport(row) && matchesSports(row, teams, sports)
            GuideFilter.Canada -> entry.country == "CA"
            GuideFilter.Usa -> entry.country == "US"
            GuideFilter.French -> entry.language == "fr"
            GuideFilter.News -> Genre.News in entry.genres || Genre.Weather in entry.genres || Genre.Business in entry.genres
            GuideFilter.Movies -> Genre.Movies in entry.genres
            GuideFilter.Kids -> Genre.Kids in entry.genres
        }
    }

    /** Sports channels always; broadcast channels and others only while what is on looks like a game. */
    private fun isSport(row: GuideRow): Boolean {
        val entry = row.entry
        if (Genre.Sports in entry.genres || Genre.Regional in entry.genres) return true
        val catalog = entry.catalog
        if (catalog != null && !catalog.sportsCapable) return false
        val current = row.now ?: return false
        return looksLikeSport(current)
    }

    private fun looksLikeSport(programme: Programme): Boolean {
        val category = programme.category?.lowercase(Locale.ROOT).orEmpty()
        if ("sport" in category) return true
        val text = (programme.title + " " + programme.subtitle.orEmpty()).lowercase(Locale.ROOT)
        return SportsFilter.entries.any { it.pattern?.containsMatchIn(text) == true }
    }

    private val LEAGUE_FILTERS = SportsFilter.entries.filter { it.pattern != null }

    private fun matchesSports(row: GuideRow, teams: List<String>, sports: SportsFilter): Boolean {
        if (sports == SportsFilter.Any) return true
        // What is on now decides the league; only a channel with nothing on is judged by what is next.
        val programme = row.now ?: row.next
        val headline = programme?.let { (it.title + " " + it.subtitle.orEmpty()).lowercase(Locale.ROOT) }.orEmpty()
        val detail = (headline + " " + programme?.description.orEmpty().lowercase(Locale.ROOT) + " " + row.entry.name.lowercase(Locale.ROOT))
        if (sports == SportsFilter.MyTeams) return teams.any { it in detail }
        // A listing that names its league in the title is that league's, whatever its description mentions ("...then NFL Sunday").
        val named = LEAGUE_FILTERS.any { it.pattern!!.containsMatchIn(headline) }
        return sports.pattern?.containsMatchIn(if (named) headline else detail) == true
    }

    private fun matchesQuery(entry: LineupEntry, needle: String): Boolean {
        if (entry.name.lowercase(Locale.ROOT).contains(needle)) return true
        return entry.feeds.any { feed -> feed.streams.any { it.channel.name.lowercase(Locale.ROOT).contains(needle) } }
    }

    /** A local station shows only in the viewer's area, unless they ask for every area or search. */
    private fun localToHome(entry: LineupEntry, home: String, allAreas: Boolean): Boolean {
        val catalog = entry.catalog ?: return true
        if (!catalog.hasLocalStations || allAreas) return true
        if (entry.market.isEmpty()) return entry.callsign() == null // the network's own feed; a bare callsign is some unknown town's station
        return entry.market == home || home.isNotEmpty() && sameArea(entry.market, home)
    }

    private fun LineupEntry.callsign(): String? = feeds.firstNotNullOfOrNull { it.identity.callsign }

    private fun sameArea(a: String, b: String): Boolean {
        val x = a.split(' ').filter { it.length > 2 }.toSet()
        val y = b.split(' ').filter { it.length > 2 }.toSet()
        return x.isNotEmpty() && y.isNotEmpty() && x.any { it in y } && !(a.startsWith("new ") != b.startsWith("new "))
    }

    private fun comparator(settings: UserSettings, prefs: LineupPrefs, home: String): Comparator<GuideRow> {
        val order = ChannelCatalog.order
        fun group(row: GuideRow): Int {
            val entry = row.entry
            return when {
                entry.isLocalStation && (entry.market == home) -> 0
                entry.country == prefs.homeCountry && entry.tier == Tier.Essential -> 1
                entry.tier == Tier.Essential -> 2
                entry.country == prefs.homeCountry && entry.tier == Tier.Recommended -> 3
                entry.tier == Tier.Recommended -> 4
                entry.catalog != null -> 5
                else -> 6
            }
        }
        return compareBy<GuideRow> { !it.isFavorite }
            .thenBy { group(it) }
            .thenBy { order[it.entry.catalog?.id] ?: Int.MAX_VALUE }
            .thenBy { it.entry.name.lowercase(Locale.ROOT) }
    }

    private fun labelFor(entry: LineupEntry, current: Programme?): String {
        val category = current?.category?.trim().orEmpty()
        val place = if (entry.isLocalStation) titleCase(entry.market) else ""
        val what = category.ifEmpty { entry.genres.firstOrNull { it != Genre.Broadcast }?.label ?: entry.genres.firstOrNull()?.label.orEmpty() }
        return listOf(what, place).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun titleCase(text: String) = text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    /** A reasonable first guess at the viewer's local area from the device's time zone; they can change it. */
    fun marketForTimeZone(zone: String): String = when (zone) {
        "America/Toronto", "America/Montreal", "America/Nipigon", "America/Thunder_Bay" -> "toronto"
        "America/Vancouver" -> "vancouver"
        "America/Edmonton" -> "edmonton"
        "America/Winnipeg" -> "winnipeg"
        "America/Regina" -> "regina"
        "America/Halifax" -> "halifax"
        "America/St_Johns" -> "st johns"
        "America/New_York", "America/Detroit" -> "new york"
        "America/Chicago" -> "chicago"
        "America/Denver", "America/Boise" -> "denver"
        "America/Phoenix" -> "phoenix"
        "America/Los_Angeles" -> "los angeles"
        "America/Anchorage" -> "anchorage"
        "Pacific/Honolulu" -> "honolulu"
        else -> ""
    }
}
