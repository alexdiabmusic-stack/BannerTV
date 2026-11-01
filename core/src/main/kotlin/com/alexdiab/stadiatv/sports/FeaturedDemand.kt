package com.alexdiab.stadiatv.sports

import android.content.Context
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Demand boosts from the bundled featured-event workbook (the same 263-row calendar the iOS app
 * ships). A featured fixture outranks ordinary games when choosing the home hero.
 */
class FeaturedDemand(context: Context?) {
    private class Pick(val date: String, val sport: String, val league: String, val title: String, val demand: Int)

    private val picksByDate: Map<String, List<Pick>> = context?.let(::load).orEmpty()
    private val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/Toronto") }

    fun boost(match: Match): Int {
        val picks = synchronized(dateKey) { picksByDate[dateKey.format(match.startMillis)] }
        if (picks != null) {
            picks.firstOrNull { matches(it, match) }?.let { return 1_000 + it.demand }
            picks.firstOrNull { sameLeague(it, match.league) }?.let { return it.demand }
        }
        return 0
    }

    private fun matches(pick: Pick, match: Match): Boolean {
        if (!sameLeague(pick, match.league)) return false
        val eventText = normalize("${pick.title} ${pick.league} ${pick.sport}")
        val matchText = normalize("${match.name} ${match.shortName} ${match.home.displayName} ${match.away.displayName}")
        val matchName = normalize(match.name)
        if ((matchName.isNotEmpty() && eventText.contains(matchName)) || (eventText.isNotEmpty() && matchText.contains(eventText))) return true
        val title = significant(pick.title)
        val homeAbbr = normalize(match.home.abbreviation)
        val awayAbbr = normalize(match.away.abbreviation)
        val hasHome = significant(match.home.displayName).any(title::contains) || (homeAbbr.isNotEmpty() && homeAbbr in title)
        val hasAway = significant(match.away.displayName).any(title::contains) || (awayAbbr.isNotEmpty() && awayAbbr in title)
        return hasHome && hasAway
    }

    private fun sameLeague(pick: Pick, league: League): Boolean {
        val pickTokens = significant("${pick.league} ${pick.sport}")
        val leagueTokens = significant("${league.name} ${league.shortName} ${league.path} ${league.keywords.joinToString(" ")}")
        return pickTokens.any(leagueTokens::contains)
    }

    companion object {
        private val MARKS = Regex("\\p{M}")
        private val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]+")
        private val STOP_WORDS = setOf("at", "vs", "the", "fc", "cf", "sc", "club", "city", "united", "coverage", "featured")

        private fun load(context: Context): Map<String, List<Pick>> = runCatching {
            val picks = mutableMapOf<String, MutableList<Pick>>()
            context.assets.open("featured_events.tsv").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val fields = line.split('\t')
                    if (fields.size != 15) return@forEach
                    val demand = fields[5].toDoubleOrNull()?.let { Math.round(it).toInt() } ?: return@forEach
                    picks.getOrPut(fields[0]) { mutableListOf() } += Pick(fields[0], fields[2], fields[3], fields[4], demand)
                }
            }
            picks
        }.getOrDefault(emptyMap())

        private fun significant(value: String): Set<String> =
            normalize(value).split(' ').filter { it.length >= 3 && it !in STOP_WORDS }.toSet()

        fun normalize(value: String?): String {
            val decomposed = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFD).replace(MARKS, "")
            return decomposed.lowercase(Locale.US).replace(NOT_ALNUM, " ").trim()
        }

        /** Deterministic fallback for dates outside the bundled workbook and for JVM tests. */
        fun heuristicBoost(match: Match): Int {
            val title = normalize("${match.name} ${match.shortName}")
            val demand = when (match.league.path) {
                "football/nfl" -> 92
                "soccer/uefa.champions", "soccer/fifa.world" -> 91
                "basketball/nba", "soccer/eng.1", "racing/f1" -> 86
                "hockey/nhl", "baseball/mlb" -> 78
                else -> 62
            }
            val marquee = listOf("final", "championship", "grand prix", "world cup", "playoff", "semifinal").any(title::contains)
            return if (marquee) 1_000 + demand else demand
        }
    }
}
