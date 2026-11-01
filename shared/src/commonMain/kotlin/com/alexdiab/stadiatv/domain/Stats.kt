package com.alexdiab.stadiatv.domain

import androidx.compose.runtime.Immutable

@Immutable
data class Athlete(
    val id: String,
    val name: String,
    val jersey: String = "",
    val position: String = "",
    val headshotUrl: String? = null,
    val details: String = "",
    val positionName: String? = null, val age: Int? = null, val height: String? = null, val weight: String? = null,
    val college: String? = null, val experienceYears: Int? = null, val birthPlace: String? = null, val injured: Boolean = false,
)

@Immutable
data class StandingRow(
    val teamId: String,
    val teamName: String,
    val abbreviation: String,
    val logoUrl: String?,
    /** Headline figure shown in the compact row: record, points, or win percentage. */
    val record: String,
    /** Ordered stat columns keyed by lower-case stat name ("wins", "losses", "gamesbehind"...). */
    val values: Map<String, String> = emptyMap(),
)

@Immutable
data class StandingGroup(val name: String, val rows: List<StandingRow>, val source: String? = null)

@Immutable
data class RosterGroup(val title: String, val athletes: List<Athlete>)

@Immutable
data class LeaderRow(val athlete: Athlete, val team: String, val value: String, val rank: Int)

@Immutable
data class LeaderBoard(val title: String, val rows: List<LeaderRow>)

@Immutable
data class Injury(
    val athlete: Athlete,
    val team: String,
    val status: String,
    val detail: String,
    val date: String,
    val source: String? = null,
)

@Immutable
data class AthleteOverview(
    val athlete: Athlete,
    val bio: Map<String, String> = emptyMap(),
    val stats: Map<String, String> = emptyMap(),
    val news: List<Article> = emptyList(),
    val statlineLabel: String? = null, val headlineStats: Map<String, String> = emptyMap(),
)

/** A single entrant in a racing event (an F1 driver or a golfer on the leaderboard). */
@Immutable
data class Racer(
    val athlete: Athlete,
    val rank: String,
    val score: String,
    val manufacturer: String,
)

/** The capabilities a league's data source can serve, so the UI hides tabs that would be empty. */
enum class SportsCapability { Scores, Schedule, Standings, Teams, Rosters, Players, Leaders, Injuries, Field, GameDetails }
