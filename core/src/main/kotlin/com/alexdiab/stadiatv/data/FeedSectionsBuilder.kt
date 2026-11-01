package com.alexdiab.stadiatv.data

import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.util.Dates

/**
 * Turns every game the app knows about into the lists the screens show, shaped by what the viewer follows: the featured
 * game and the "for you" lists come from their teams and leagues first, while the Sports hub still lists everything.
 * Pure, so the rules can be tested without a network or a screen.
 */
object FeedSectionsBuilder {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    fun build(
        all: List<Match>,
        settings: UserSettings,
        now: Long,
        demandBoost: (Match) -> Int,
        isLoading: Boolean,
        error: String?,
    ): FeedSections {
        // What counts as the viewer's: a followed league, or any game a starred team plays (even in a league they unfollowed).
        val scoped = all.filter(settings::inScope)
        val scores = HashMap<String, Int>(all.size)
        all.forEach { scores[it.key] = score(it, settings, now, demandBoost(it)) }
        fun score(match: Match) = scores[match.key] ?: 0

        val liveNow = all.filter { it.state == GameState.LIVE }.sortedByDescending(::score)
        val liveForYou = liveNow.filter(settings::inScope)
        val favoriteLive = liveNow.filter(settings::involvesFavorite)
        val favoriteToday = all.filter { Dates.isToday(it.startMillis) && settings.involvesFavorite(it) }.sortedBy { it.startMillis }
        val favoriteUpcoming = all.filter { it.state == GameState.PRE && it.startMillis >= now && settings.involvesFavorite(it) }.sortedBy { it.startMillis }
        val favoriteRecent = all.filter { it.state == GameState.FINAL && settings.involvesFavorite(it) }.sortedByDescending { it.startMillis }
        val soon = now + 6 * HOUR
        fun startsSoon(match: Match) = match.state == GameState.PRE && match.startMillis in (now + 1)..soon
        val startingSoon = all.filter(::startsSoon).sortedBy { it.startMillis }.take(8)
        val startingSoonForYou = scoped.filter(::startsSoon).sortedBy { it.startMillis }.take(8)
        val upcoming = scoped.filter { it.state == GameState.PRE && it.startMillis >= now }.sortedBy { it.startMillis }
        val recent = scoped.filter { it.state == GameState.FINAL }.sortedByDescending { it.startMillis }
        // "Next up" prefers the viewer's own games and only reaches further when they have none coming.
        val next = upcoming.ifEmpty { all.filter { it.state == GameState.PRE && it.startMillis >= now }.sortedBy { it.startMillis } }.take(3)
        val prime = pick((liveNow + favoriteUpcoming + upcoming).distinctBy { it.key }, settings, now, ::score)
        return FeedSections(
            all = all,
            liveNow = liveNow,
            liveForYou = liveForYou,
            favoriteLive = favoriteLive,
            favoriteToday = favoriteToday,
            favoriteUpcoming = favoriteUpcoming,
            favoriteRecent = favoriteRecent,
            nextAcrossSports = next,
            startingSoon = startingSoon,
            startingSoonForYou = startingSoonForYou,
            upcoming = upcoming,
            recent = recent,
            prime = prime,
            isLoading = isLoading,
            error = error,
        )
    }

    /**
     * The game to feature. A starred team that is playing now or within a day comes first, then the best game in the
     * leagues the viewer follows, and only when there is nothing of theirs does a game from elsewhere get the slot.
     * Within each of those, the usual measure applies: live, big fixtures, shown on a channel, sooner.
     */
    fun pick(candidates: List<Match>, settings: UserSettings, now: Long, score: (Match) -> Int): Match? =
        candidates.filter { it.state != GameState.FINAL }.maxWithOrNull(compareBy<Match>({ tier(it, settings, now) }, { score(it) }))

    fun tier(match: Match, settings: UserSettings, now: Long): Int = when {
        match.state == GameState.FINAL -> 0
        settings.involvesFavorite(match) && (match.state == GameState.LIVE || match.startMillis - now <= DAY) -> 3
        settings.inScope(match) -> 2
        else -> 1
    }

    private fun score(match: Match, settings: UserSettings, now: Long, boost: Int): Int {
        var score = demandScore(match, boost)
        if (match.state == GameState.LIVE) score += 100
        if (match.hasDisplayScore) score += 25
        if (settings.involvesFavorite(match)) score += 50
        if (match.broadcasts.isNotEmpty()) score += 20
        score -= maxOf(0L, (match.startMillis - now) / HOUR).toInt()
        return score
    }

    private fun demandScore(match: Match, boost: Int): Int {
        val text = "${match.name} ${match.shortName} ${match.statusDetail} ${match.league.name} ${match.league.shortName}".lowercase()
        var score = boost.takeIf { it > 0 } ?: 0
        if (text.contains("world cup") || text.contains("fifa")) score += 220
        if (text.contains("champions league") || text.contains("uefa")) score += 140
        if (text.contains("final") || text.contains("championship") || text.contains("title")) score += 130
        if (text.contains("semifinal") || text.contains("semi-final") || text.contains("playoff")) score += 80
        if (text.contains("derby") || text.contains("rivalry")) score += 30
        score += when (match.league.name) {
            "NFL" -> 70
            "NBA", "Premier League", "Champions League", "UEFA Champions League" -> 55
            "MLB", "NHL" -> 35
            "MLS", "La Liga" -> 25
            else -> 10
        }
        return score
    }
}
