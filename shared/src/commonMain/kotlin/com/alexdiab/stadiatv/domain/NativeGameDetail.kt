package com.alexdiab.stadiatv.domain

import androidx.compose.runtime.Immutable

@Immutable
data class ProviderStamp(val provider: String, val gameId: String, val requestedAt: Long, val revision: String? = null, val sequence: Int = -1, val final: Boolean = false)

@Immutable
data class SportPlayer(val id: String, val name: String, val number: String = "", val position: String = "", val profileUrl: String? = null)

@Immutable
data class PitchLocation(val x: Float, val z: Float, val top: Float, val bottom: Float)

@Immutable
data class BaseballPitch(val id: String, val number: Int?, val description: String, val type: String, val speed: String?, val spin: String?, val location: PitchLocation?, val ball: Boolean, val strike: Boolean, val inPlay: Boolean)

@Immutable
data class BaseballEvent(val id: String, val index: Int, val type: String, val description: String, val pitch: BaseballPitch?,
    val balls: Int?, val strikes: Int?, val outs: Int?, val hitDistance: Double?, val exitVelocity: Double?)

@Immutable
data class BaseballRunner(val id: String, val player: SportPlayer?, val start: String?, val end: String?, val isOut: Boolean,
    val outBase: String?, val responsiblePitcher: SportPlayer?, val reason: String?, val earned: Boolean?, val scored: Boolean)

@Immutable
data class BaseballAtBat(val id: String, val index: Int, val inning: Int, val half: String, val batter: SportPlayer?, val pitcher: SportPlayer?, val title: String, val description: String, val complete: Boolean, val scoring: Boolean, val awayScore: String?, val homeScore: String?, val pitches: List<BaseballPitch>, val eventCount: Int, val endTime: Long?,
    val events: List<BaseballEvent> = emptyList(), val runners: List<BaseballRunner> = emptyList(), val rbi: Int? = null)

@Immutable
data class BaseballDetail(val inning: Int?, val half: String, val balls: Int?, val strikes: Int?, val outs: Int?, val bases: List<SportPlayer?>, val batter: SportPlayer?, val pitcher: SportPlayer?, val atBats: List<BaseballAtBat>, val scheduledInnings: Int? = null,
    val surfaceUpdatedAt: Map<String, Long> = emptyMap(), val errors: Map<String, String> = emptyMap(), val savedSnapshot: Boolean = false,
    val probableAway: SportPlayer? = null, val probableHome: SportPlayer? = null) {
    val betweenInnings: Boolean get() = half.lowercase() in setOf("middle", "end") || outs == 3
    val baseDescription: String get() = if (bases.all { it != null }) "Bases loaded" else bases.mapIndexedNotNull { index, player -> player?.let { "${listOf("First", "Second", "Third")[index]}: ${it.name}" } }.joinToString(" · ").ifBlank { "Bases empty" }
}

@Immutable
data class GameVideo(val id: String, val title: String, val url: String, val image: String? = null)

@Immutable
data class BaseballProbability(val atBatIndex: Int, val homeProbability: Float)

@Immutable
data class BaseballExtraState(val checkedAt: Map<String, Long> = emptyMap(), val saved: Set<String> = emptySet())

@Immutable
data class FootballField(val possessionTeamId: String?, val yardLine: String, val yardsFromOwnGoal: Float?, val yardsToFirst: Int?, val down: Int?, val goalToGo: Boolean = false, val possessionAbbreviation: String? = null)

@Immutable
data class FootballDetail(val field: FootballField?, val season: Int?, val seasonType: String?, val week: Int?, val awayTimeouts: Int? = null, val homeTimeouts: Int? = null, val playsLoaded: Boolean = false, val currentDriveId: String? = null,
    val summaryUpdatedAt: Long = 0, val detailsUpdatedAt: Long = 0, val savedSnapshot: Boolean = false, val errors: Map<String, String> = emptyMap())

@Immutable
data class FootballParticipant(val id: String?, val name: String, val nativeTeamId: String?, val statType: Int?, val yards: Int?)

@Immutable
data class FootballEvent(val driveSequence: Int?, val down: Int?, val distance: Int?, val goalToGo: Boolean, val field: String?, val yards: Int?, val turnover: Boolean, val penalty: Boolean, val firstDown: Boolean, val participants: List<FootballParticipant>, val rawType: String) {
    val downDistance: String? get() = down?.takeIf { it in 1..4 }?.let { "${listOf("1st", "2nd", "3rd", "4th")[it - 1]} & ${if (goalToGo) "Goal" else distance?.toString() ?: "–"}" }
}

@Immutable
data class FootballDrive(val sequence: Int, val startQuarter: Int?, val endQuarter: Int?, val startClock: String?, val endClock: String?, val scoring: Boolean, val playCount: Int?)

enum class FootballPlayFilter(val label: String) {
    All("All"), Scoring("Scoring"), Turnovers("Turnovers"), Penalties("Penalties");
    fun includes(play: Play) = when (this) { All -> true; Scoring -> play.isScoring; Turnovers -> play.footballEvent?.turnover == true; Penalties -> play.footballEvent?.penalty == true }
}

@Immutable
data class SoccerLineupPlayer(val player: SportPlayer, val starter: Boolean, val captain: Boolean = false, val row: Int? = null, val column: Int? = null,
    val membershipKnown: Boolean = true, val pitchPosition: SoccerPitchPosition? = null, val rating: Double? = null, val ratingSource: String? = null)

@Immutable
data class SoccerPitchPosition(val x: Float, val y: Float)

@Immutable
data class SoccerShot(val id: String, val teamId: String, val player: SportPlayer?, val minute: Int?, val addedMinute: Int?,
    val x: Double?, val y: Double?, val expectedGoals: Double?, val outcome: String, val bodyPart: String?, val situation: String?)

@Immutable
data class SoccerPressureSample(val minute: Double, val value: Double)

@Immutable
data class SoccerLineup(val home: Boolean, val formation: String?, val players: List<SoccerLineupPlayer>, val manager: String? = null) {
    /** Geometry is exposed only when the provider supplies structured rows, never guessed from a formation string. */
    val hasPitch: Boolean get() = players.any { it.membershipKnown && it.starter && it.row != null && it.column != null }
}

@Immutable
data class SoccerComment(val id: String, val clock: String, val text: String, val type: String, val timestamp: Long?, val version: Int = 0)

enum class SoccerEventKind {
    Goal, OwnGoal, PenaltyGoal, MissedPenalty, PenaltySaved, PenaltyWon,
    YellowCard, SecondYellow, RedCard, Substitution, ShotSaved, ShotBlocked,
    ShotOffTarget, Woodwork, Corner, Offside, Foul, Var, PeriodStart, HalfTime, PeriodEnd, Unknown
}

/** Provider identities are kept separate from display text; assists and on/off players are independent actions. */
@Immutable
data class SoccerEvent(val kind: SoccerEventKind, val primary: SportPlayer? = null, val secondary: SportPlayer? = null,
    val minute: Int? = null, val timestamp: Long? = null, val rawPeriod: String = "", val secondAssist: SportPlayer? = null)

@Immutable
data class SoccerPlayerMatchStats(val player: SportPlayer, val home: Boolean, val stats: Map<String, String>)

@Immutable
data class SoccerOfficial(val name: String, val role: String)

@Immutable
data class ShootoutKick(val id: String, val home: Boolean, val player: String, val scored: Boolean?, val order: Int)

@Immutable
data class SoccerDetail(val lineups: List<SoccerLineup> = emptyList(), val commentary: List<SoccerComment> = emptyList(), val nextCursor: String? = null, val shootout: List<ShootoutKick> = emptyList(), val errors: Map<String, String> = emptyMap(),
    val surfaceUpdatedAt: Map<String, Long> = emptyMap(), val lastFullRefresh: Long = 0,
    val playerNames: Map<String, String> = emptyMap(), val savedSnapshot: Boolean = false,
    val officials: List<SoccerOfficial> = emptyList(), val commentaryPages: Int = 0,
    val playerStats: List<SoccerPlayerMatchStats> = emptyList(), val overviewNotPublished: Boolean = false,
    val shots: List<SoccerShot> = emptyList(), val pressure: List<SoccerPressureSample> = emptyList(),
    /** Score provenance/freshness is independent of the coordinator request and detail resource times. */
    val scoreSource: String? = null, val scoreUpdatedAt: Long? = null,
    val surfaceSources: Map<String, String> = emptyMap(), val recoverableCoreFailure: Boolean = false)

@Immutable
data class SoccerTableRow(val id: String, val name: String, val position: Int?, val played: Int?, val won: Int?, val drawn: Int?, val lost: Int?, val goalsFor: Int?, val goalsAgainst: Int?, val points: Int?, val startingPosition: Int?, val deduction: Int? = null)

@Immutable
data class SoccerTable(val season: Int, val provisional: Boolean, val checkedAt: Long, val rows: List<SoccerTableRow>, val matchweek: Int? = null,
    val groups: List<SoccerTableGroup> = emptyList())

@Immutable
data class SoccerTableGroup(val label: String, val rows: List<SoccerTableRow>)

@Immutable
data class HockeyDetail(
    val strength: String?, val awaySkaters: Int?, val homeSkaters: Int?, val awayGoaliePulled: Boolean?, val homeGoaliePulled: Boolean?,
    val scoringSummary: List<Play> = emptyList(), val eventsLoaded: Boolean = false, val errors: Map<String, String> = emptyMap(),
    val summaryLoaded: Boolean = false,
    val players: List<HockeyPlayerStats> = emptyList(), val leaders: List<HockeyLeader> = emptyList(), val recapUrl: String? = null,
    val boxLoaded: Boolean = false, val landingLoaded: Boolean = false,
    /** Original successful fetch time of each independent NHL surface, not the latest retry time. */
    val surfaceUpdatedAt: Map<String, Long> = emptyMap(), val savedSnapshot: Boolean = false,
)

@Immutable
data class HockeyStat(val key: String, val label: String, val value: String)
@Immutable
data class HockeyPlayerStats(val player: Athlete, val team: String, val group: String, val stats: List<HockeyStat>)
@Immutable
data class HockeyLeader(val player: Athlete, val team: String, val line: String, val role: String? = null)

/** NHL identities are never passed to the ESPN athlete route. */
@Immutable
data class HockeyPlayer(val id: String, val name: String, val headshot: String? = null) {
    val profileUrl: String get() = "https://www.nhl.com/player/$id"
}

@Immutable
data class HockeyEvent(
    val kind: String, val title: String, val subtitle: String?, val periodLabel: String,
    val primary: HockeyPlayer? = null, val assists: List<HockeyPlayer> = emptyList(),
    val strength: String? = null, val shootoutRound: Int? = null, val seasonGoals: Int? = null,
    val assistSeasonTotals: Map<String, Int> = emptyMap(), val location: HockeyLocation? = null,
)

@Immutable
data class HockeyLocation(val x: Float, val y: Float, val normalizedOrientation: Boolean)

enum class HockeyPlayFilter(val label: String) {
    All("All"), Goals("Goals"), Penalties("Penalties"), Shots("Shots"), Hits("Hits");
    fun includes(play: Play): Boolean = when (this) {
        All -> true
        Goals -> play.hockeyEvent?.kind == "goal"
        Penalties -> play.hockeyEvent?.kind in setOf("penalty", "delayed-penalty")
        Shots -> play.hockeyEvent?.kind in setOf("shot-on-goal", "missed-shot", "blocked-shot", "failed-shot-attempt")
        Hits -> play.hockeyEvent?.kind == "hit"
    }
}

/** New events wait while reading history. Corrections and removals still apply to visible identities. */
@Immutable
data class PlayTimelineWindow(val initialized: Boolean = false, val displayed: List<Play> = emptyList(), val deferredIDs: Set<String> = emptySet()) {
    fun update(incoming: List<Play>, atLiveEdge: Boolean): PlayTimelineWindow {
        if (!initialized || atLiveEdge) return PlayTimelineWindow(true, incoming)
        val visible = displayed.map { it.id }.toSet()
        return PlayTimelineWindow(true, incoming.filter { it.id in visible }, incoming.map { it.id }.toSet() - visible)
    }
}

typealias HockeyTimelineWindow = PlayTimelineWindow

@Immutable
data class BasketballDetail(val awayTimeouts: Int?, val homeTimeouts: Int?, val awayBonus: Boolean?, val homeBonus: Boolean?, val onCourt: List<SportPlayer>,
    val period: Int = 0, val regulationPeriods: Int = 4, val boxLoaded: Boolean = false, val playsLoaded: Boolean = false,
    val surfaceUpdatedAt: Map<String, Long> = emptyMap(), val errors: Map<String, String> = emptyMap(), val savedSnapshot: Boolean = false,
    val gameLeaders: List<BasketballLeader> = emptyList(), val onCourtValid: Boolean = true)

@Immutable
data class BasketballLeader(val player: Athlete, val team: String, val statLine: String)

enum class BasketballPlayPriority { Compact, Medium, High }
@Immutable
data class BasketballPlayDetail(val title: String, val subtitle: String?, val priority: BasketballPlayPriority, val periodBoundary: Boolean)
