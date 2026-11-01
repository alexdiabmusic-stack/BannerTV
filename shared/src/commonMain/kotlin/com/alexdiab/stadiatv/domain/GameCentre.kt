package com.alexdiab.stadiatv.domain

import androidx.compose.runtime.Immutable

/** Everything the Match screen shows beyond the scoreboard. Sections a provider can't supply are simply empty. */
@Immutable
data class GameCentre(
    val lineScore: LineScore? = null,
    val teamStats: List<StatComparison> = emptyList(),
    val leaders: List<LeaderComparison> = emptyList(),
    /** Newest first. */
    val plays: List<Play> = emptyList(),
    val boxScore: List<TeamBox> = emptyList(),
    val headline: String? = null,
    val gameInfo: List<Pair<String, String>> = emptyList(),
    val drives: List<GameDrive> = emptyList(),
    val winProbability: List<Float> = emptyList(),
    val shots: List<GameShot> = emptyList(),
    val shotSource: String? = null,
    val liveMatch: Match? = null,
    val providerStamp: ProviderStamp? = null,
    val baseball: BaseballDetail? = null,
    val football: FootballDetail? = null,
    val soccer: SoccerDetail? = null,
    val hockey: HockeyDetail? = null,
    val basketball: BasketballDetail? = null,
    val videos: List<GameVideo> = emptyList(),
    val winProbabilitySource: String? = null,
    val teamStatsSource: String? = null,
    /** Non-null also distinguishes an authoritative empty event snapshot from an unavailable feed. */
    val playsSource: String? = null,
    val baseballProbability: List<BaseballProbability> = emptyList(),
    val baseballExtras: BaseballExtraState = BaseballExtraState(),
) {
    val isEmpty: Boolean get() = lineScore == null && teamStats.isEmpty() && leaders.isEmpty() && plays.isEmpty() && boxScore.isEmpty() && drives.isEmpty() && shots.isEmpty() && winProbability.isEmpty() && baseball == null && football == null && soccer == null && hockey == null && basketball == null && videos.isEmpty()

    companion object { val EMPTY = GameCentre() }
}

@Immutable
data class LineScore(
    val periods: List<String>,
    val away: List<String>,
    val home: List<String>,
    val awayTotal: String,
    val homeTotal: String,
    val awayLabel: String,
    val homeLabel: String,
    val awayHits: String? = null, val homeHits: String? = null, val awayErrors: String? = null, val homeErrors: String? = null,
)

/** One row of a head-to-head team stat table ("Shots on goal", 24 vs 31). */
@Immutable
data class StatComparison(val label: String, val away: String, val home: String,
    val id: String? = null, val lowerIsBetter: Boolean = false, val awaySecondary: String? = null, val homeSecondary: String? = null) {
    /** Share of the bar given to the away side, when both values are numeric. */
    val awayShare: Float?
        get() {
            val a = away.toNumber() ?: return null
            val h = home.toNumber() ?: return null
            val total = a + h
            return if (total <= 0.0) 0.5f else (a / total).toFloat().coerceIn(0f, 1f)
        }

    private fun String.toNumber(): Double? = replace("%", "").replace(",", "").trim().toDoubleOrNull()
}

@Immutable
data class LeaderEntry(val name: String, val value: String, val detail: String, val headshotUrl: String?)

@Immutable
data class LeaderComparison(val category: String, val away: LeaderEntry?, val home: LeaderEntry?)

@Immutable
data class Play(
    val id: String,
    val period: String,
    val clock: String,
    val text: String,
    val teamId: String?,
    val awayScore: String?,
    val homeScore: String?,
    val isScoring: Boolean,
    val type: String = "",
    val periodNumber: Int = 0,
    val videoUrl: String? = null,
    val shot: GameShot? = null,
    val sortOrder: Double = 0.0,
    val hockeyEvent: HockeyEvent? = null,
    val footballEvent: FootballEvent? = null,
    val basketballEvent: BasketballPlayDetail? = null,
    val soccerEvent: SoccerEvent? = null,
)

@Immutable
data class BoxRow(val name: String, val detail: String, val stats: List<String>, val athleteId: String? = null, val headshot: String? = null, val starter: Boolean = false, val didNotPlay: Boolean = false, val profileUrl: String? = null)

@Immutable
data class GameDrive(val id: String, val team: String, val result: String, val description: String, val plays: Int, val yards: String, val duration: String, val footballDrive: FootballDrive? = null)

/** Coordinates in provider units: NBA basket-origin feet; NHL rink-centre feet. Never synthesized from distance. */
@Immutable
data class GameShot(val id: String, val x: Float, val y: Float, val made: Boolean, val home: Boolean, val period: Int, val player: String, val description: String,
    val value: Int? = null, val distanceFeet: Float? = null)

@Immutable
data class BoxGroup(val title: String, val labels: List<String>, val rows: List<BoxRow>, val totals: List<String>)

@Immutable
data class TeamBox(val teamName: String, val abbreviation: String, val groups: List<BoxGroup>)
