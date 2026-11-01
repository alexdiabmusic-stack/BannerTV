package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.data.FeedSections
import com.alexdiab.stadiatv.domain.Match

/** Presentation-only hiding: source scores, notification identity and direct game routes stay intact. */
fun FeedSections.visibleGames(hidden: Set<String>): FeedSections {
    if (hidden.isEmpty()) return this
    fun List<Match>.visible() = filterNot { it.key in hidden }
    val filtered = copy(all = all.visible(), liveNow = liveNow.visible(), liveForYou = liveForYou.visible(), favoriteLive = favoriteLive.visible(), favoriteToday = favoriteToday.visible(), favoriteUpcoming = favoriteUpcoming.visible(), favoriteRecent = favoriteRecent.visible(), nextAcrossSports = nextAcrossSports.visible(), startingSoon = startingSoon.visible(), startingSoonForYou = startingSoonForYou.visible(), upcoming = upcoming.visible(), recent = recent.visible())
    return filtered.copy(prime = prime?.takeUnless { it.key in hidden } ?: filtered.favoriteLive.firstOrNull() ?: filtered.liveForYou.firstOrNull() ?: filtered.startingSoonForYou.firstOrNull() ?: filtered.upcoming.firstOrNull())
}
