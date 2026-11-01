package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.domain.*

/** Shared soccer semantics from the iOS event rows, without reconstructing a score from incidents. */
object NativeSoccerPresentation {
    enum class Filter(val label: String) {
        All("All"), Goals("Goals"), Shots("Shots"), Cards("Cards"), Subs("Subs"), SetPieces("Set pieces");
        fun includes(play: Play): Boolean {
            val kind = play.soccerEvent?.kind ?: SoccerEventKind.Unknown
            return when (this) {
                All -> true
                Goals -> kind in goals + setOf(SoccerEventKind.MissedPenalty, SoccerEventKind.PenaltySaved, SoccerEventKind.PenaltyWon)
                Shots -> kind in goals + setOf(SoccerEventKind.ShotSaved, SoccerEventKind.ShotBlocked, SoccerEventKind.ShotOffTarget, SoccerEventKind.Woodwork)
                Cards -> kind in cards
                Subs -> kind == SoccerEventKind.Substitution
                SetPieces -> kind in setOf(SoccerEventKind.Corner, SoccerEventKind.Offside, SoccerEventKind.Foul, SoccerEventKind.PenaltyWon)
            }
        }
    }

    data class Section(val number: Int, val title: String, val events: List<Play>)
    private val goals = setOf(SoccerEventKind.Goal, SoccerEventKind.OwnGoal, SoccerEventKind.PenaltyGoal)
    private val cards = setOf(SoccerEventKind.YellowCard, SoccerEventKind.SecondYellow, SoccerEventKind.RedCard)

    /** GameCentre plays are newest first. Keep the provider's order within each half, including corrections. */
    fun sections(plays: List<Play>, filter: Filter = Filter.All): List<Section> {
        val grouped = plays.asReversed().filter(filter::includes).groupBy { it.periodNumber.takeIf { number -> number in 1..4 } ?: 0 }
        return listOf(1 to "FIRST HALF", 2 to "SECOND HALF", 3 to "EXTRA TIME · FIRST HALF", 4 to "EXTRA TIME · SECOND HALF", 0 to "OTHER")
            .mapNotNull { (number, title) -> grouped[number]?.takeIf { it.isNotEmpty() }?.let { Section(number, title, it) } }
    }

    /** The overview shows goals and cards, oldest first; it does not silently drop cards or cap a long match. */
    fun keyEvents(plays: List<Play>): List<Play> = plays.asReversed().filter { it.soccerEvent?.kind in goals + cards }

    fun keyStats(stats: List<StatComparison>): List<StatComparison> =
        listOf("Expected goals", "Possession", "Shots", "Shots on target", "Big chances created", "Corners")
            .mapNotNull { label -> stats.firstOrNull { it.label == label } }

    /** Explicit added time wins. Only a known period permits converting an absolute provider minute. */
    fun eventClock(raw: String?, period: Int): String {
        val value = raw?.trim()?.takeIf { it.matches(Regex("[0-9]{1,3}(?:\\+[0-9]{1,2})?")) } ?: return ""
        if ('+' in value) return "$value′"
        val minute = value.toInt()
        val end = when (period) { 1 -> 45; 2 -> 90; 3 -> 105; 4 -> 120; else -> null }
        return if (end != null && minute > end) "$end+${minute - end}′" else "$minute′"
    }
}
