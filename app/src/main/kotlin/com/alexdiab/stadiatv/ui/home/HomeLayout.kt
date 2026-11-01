package com.alexdiab.stadiatv.ui.home

import java.util.Calendar

/** The part of the day Home is written for: what a viewer wants first changes with it. */
enum class Daypart(val title: String) {
    Morning("Your Sports This Morning"),
    Afternoon("Your Sports Today"),
    Evening("Your Sports Tonight"),
    LateNight("Your Sports Tonight");

    companion object {
        fun at(hour: Int): Daypart = when (hour) {
            in 5..11 -> Morning
            in 12..16 -> Afternoon
            in 17..23 -> Evening
            else -> LateNight
        }

        fun of(millis: Long): Daypart = at(Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY))
    }
}

/** A band of the Home screen. The featured game and the header are not here: they always lead. */
enum class HomePart { LiveAround, Continue, Teams, Picks, Channels, Results }

object HomeLayout {
    /**
     * The bands in the order to show them. Live games come first whenever there are any, because a game in progress is
     * the one thing that cannot wait; with none, the morning leads with what happened overnight and the evening with
     * what is about to start. Continue Watching stays near the top: resuming is the commonest thing to do.
     */
    fun plan(daypart: Daypart, hasLive: Boolean): List<HomePart> = when {
        hasLive -> listOf(HomePart.LiveAround, HomePart.Continue, HomePart.Teams, HomePart.Channels, HomePart.Picks, HomePart.Results)
        daypart == Daypart.Morning || daypart == Daypart.LateNight ->
            listOf(HomePart.Results, HomePart.Continue, HomePart.Teams, HomePart.Picks, HomePart.Channels)
        daypart == Daypart.Afternoon -> listOf(HomePart.Picks, HomePart.Continue, HomePart.Teams, HomePart.Channels, HomePart.Results)
        else -> listOf(HomePart.Picks, HomePart.Teams, HomePart.Continue, HomePart.Channels, HomePart.Results)
    }

    fun picksTitle(daypart: Daypart): String = when (daypart) {
        Daypart.Morning, Daypart.Afternoon -> "Today's Top Picks"
        Daypart.Evening, Daypart.LateNight -> "Tonight's Top Picks"
    }

    fun resultsTitle(daypart: Daypart): String = when (daypart) {
        Daypart.Morning, Daypart.LateNight -> "Last Night's Results"
        else -> "Latest Results"
    }

    fun liveCountLabel(count: Int): String = if (count == 1) "1 live game" else "$count live games"
}
