package com.alexdiab.stadiatv.domain

import androidx.compose.runtime.Immutable

/** A sport grouping used to organise the league picker. Mirrors iOS `SportGroup`. */
enum class SportGroup(val label: String) {
    Football("Football"),
    Basketball("Basketball"),
    Baseball("Baseball"),
    Hockey("Hockey"),
    Soccer("Soccer"),
    Tennis("Tennis"),
    Golf("Golf"),
    Racing("Racing"),
}

/**
 * A single league / competition. `path` is the ESPN URL segment and doubles as the stable
 * identifier (matching the iOS `League.id`), so persisted selections stay compatible.
 */
@Immutable
data class League(
    val name: String,
    val shortName: String,
    val path: String,
    val group: SportGroup,
    /** Keywords used by stream matching (broadcast / league aliases). */
    val keywords: List<String> = emptyList(),
) {
    val id: String get() = path
    val sportSlug: String get() = path.substringBefore('/')
    val leagueSlug: String get() = path.substringAfter('/')

    companion object {
        // iOS implements the data provider but keeps CFL out of the picker until
        // playlist matching supports it. Provider availability and watchability are distinct.
        val CFL = League("CFL", "CFL", "football/cfl", SportGroup.Football, listOf("cfl", "canadian football", "grey cup"))
        /**
         * The leagues the app supports. Mirrors iOS `League.all`: every entry has a data provider,
         * so a league can never be selectable and then silently empty (see `SportsRepository`).
         */
        val ALL: List<League> = listOf(
            League("NFL", "NFL", "football/nfl", SportGroup.Football,
                listOf("nfl", "football", "sunday", "monday night", "thursday night")),
            League("NBA", "NBA", "basketball/nba", SportGroup.Basketball, listOf("nba", "basketball")),
            League("WNBA", "WNBA", "basketball/wnba", SportGroup.Basketball, listOf("wnba", "basketball")),
            League("MLB", "MLB", "baseball/mlb", SportGroup.Baseball, listOf("mlb", "baseball")),
            League("NHL", "NHL", "hockey/nhl", SportGroup.Hockey, listOf("nhl", "hockey")),
            League("Premier League", "EPL", "soccer/eng.1", SportGroup.Soccer,
                listOf("premier league", "epl", "english", "soccer", "football")),
            League("MLS", "MLS", "soccer/usa.1", SportGroup.Soccer,
                listOf("mls", "major league soccer", "soccer")),
            League("La Liga", "La Liga", "soccer/esp.1", SportGroup.Soccer,
                listOf("la liga", "laliga", "spanish", "soccer", "football")),
            League("UEFA Champions League", "UCL", "soccer/uefa.champions", SportGroup.Soccer,
                listOf("champions league", "ucl", "uefa", "soccer", "football")),
            League("Formula 1", "F1", "racing/f1", SportGroup.Racing,
                listOf("f1", "formula 1", "formula one", "grand prix")),
            League("PGA Tour", "PGA", "golf/pga", SportGroup.Golf, listOf("pga", "golf", "tour")),
        )

        private val byPath: Map<String, League> = (ALL + CFL).associateBy { it.path }

        fun find(path: String?): League? = path?.let(byPath::get)

        fun inGroup(group: SportGroup): List<League> = ALL.filter { it.group == group }

        /** Sport groups that currently have at least one league, in display order. */
        val groups: List<SportGroup> get() = SportGroup.entries.filter { g -> ALL.any { it.group == g } }
    }
}

enum class GameState(val label: String) {
    PRE("Upcoming"), LIVE("LIVE"), FINAL("Final")
}

@Immutable
data class TeamSide(
    val displayName: String,
    val shortName: String,
    val abbreviation: String,
    val logoUrl: String? = null,
    val score: String? = null,
    val record: String? = null,
    val isWinner: Boolean = false,
    /** Provider team id, used for favourite matching and roster lookups. */
    val teamId: String? = null,
    /** Primary team colour as `RRGGBB`, used to tint hero artwork. */
    val colorHex: String? = null,
)

/** Timing as reported by the provider, including clocks shown on scheduled or completed games. */
@Immutable
data class GameTiming(
    val clock: String?, val secondsRemaining: Int?, val running: Boolean,
    val period: Int?, val periodLabel: String, val phase: String? = null,
    val intermission: Boolean = false, val extraPeriod: Boolean = false,
)

/** Optional live detail a provider can attach to a match (clock, period, sport situation). */
@Immutable
data class LiveContext(
    val clock: String? = null,
    val period: String? = null,
    /** Short human lines such as "2 outs · 1-2 count" or "Power play · 1:12". */
    val situation: List<String> = emptyList(),
    val timing: GameTiming? = null,
) {
    companion object { val EMPTY = LiveContext() }
}

@Immutable
data class Match(
    val id: String,
    val league: League,
    val startMillis: Long,
    val name: String,
    val shortName: String,
    val state: GameState,
    val statusDetail: String,
    val home: TeamSide,
    val away: TeamSide,
    val broadcasts: List<String> = emptyList(),
    val venue: String? = null,
    val live: LiveContext = LiveContext.EMPTY,
    /** Explicit source identity; native IDs must never be sent to a different provider's endpoint. */
    val nativeReference: NativeGameReference? = null,
) {
    private fun usefulScore(value: String?): Boolean {
        val trimmed = value?.trim().orEmpty()
        return trimmed.isNotEmpty() && trimmed != "-" && trimmed != "--" && trimmed != "—"
    }

    val hasDisplayScore: Boolean get() = usefulScore(home.score) || usefulScore(away.score)

    /** Stable key for navigation and list items: the same game can't collide across leagues. */
    val key: String get() = "${league.path}|$id"
    /** Soccer lists the home club first; data fields remain provider-normalized home/away. */
    val homeFirst: Boolean get() = league.group == SportGroup.Soccer
    val displaySides: List<TeamSide> get() = if (homeFirst) listOf(home, away) else listOf(away, home)
    val displayScore: String get() = displaySides.joinToString("–") { it.score ?: "–" }
}

@Immutable
data class NativeGameReference(val provider: String, val gameId: String, val season: Int? = null, val seasonType: String? = null, val week: Int? = null)

@Immutable
data class Team(
    val id: String,
    val name: String,
    val shortName: String,
    val abbreviation: String,
    val logoUrl: String?,
    val league: League,
    val color: String? = null,
)

@Immutable
data class Article(
    val id: String,
    val headline: String,
    val description: String,
    val publishedMillis: Long?,
    val url: String?,
    val imageUrl: String?,
    val league: League?,
    val byline: String? = null,
    val source: String = "ESPN",
    val isPremium: Boolean = false,
    val categories: List<String> = emptyList(),
)
