package com.alexdiab.stadiatv.data.odds

import com.alexdiab.stadiatv.foundation.*
import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.domain.TeamSide
import com.alexdiab.stadiatv.net.SportsHttp
import com.alexdiab.stadiatv.net.array
import com.alexdiab.stadiatv.net.get
import com.alexdiab.stadiatv.net.string
import com.alexdiab.stadiatv.net.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** One bookmaker's moneyline for a match, in American odds (+105, -126). */
@Immutable
data class MatchOdds(val bookmaker: String, val away: Int?, val home: Int?, val draw: Int?) {
    val hasGameLine: Boolean get() = away != null || home != null || draw != null
}

/** Formats an American price the way sportsbooks print it. */
fun formatAmericanOdds(price: Int): String = if (price > 0) "+$price" else price.toString()

/**
 * Moneyline odds from the MoneyLine API, the same source the iOS app uses. Disabled (returns null)
 * until a key is supplied through `local.properties`.
 */
class OddsService(
    private val http: SportsHttp,
    private val baseUrl: String,
    private val apiKey: String,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val headers = mapOf("x-api-key" to apiKey)

    suspend fun odds(match: Match): MatchOdds? {
        if (!isConfigured) return null
        val leagueId = leagueId(match.league) ?: return null
        val events = http.getJson("${baseUrl.trimEnd('/')}/odds?league=$leagueId&sourceType=sportsbook&market=moneyline&limit=50", headers, LIST_TTL)["data"].array
        val event = bestEvent(match, events) ?: return null
        val eventId = event["eventId"].stringOrNull()
        // The per-event endpoint is fresher; the browse result is good enough if it fails.
        val detail = eventId?.let { runCatching { http.getJson("${baseUrl.trimEnd('/')}/events/$it/odds?sourceType=sportsbook&market=moneyline", headers, EVENT_TTL)["data"] }.getOrNull() } ?: event
        return display(detail, match)
    }

    companion object {
        private const val LIST_TTL = 90_000L
        private const val EVENT_TTL = 60_000L
        private const val MIN_TOKEN = 3

        /** The leagues the API covers. */
        fun leagueId(league: League): String? = when (league.path) {
            "football/nfl" -> "nfl"
            "basketball/nba" -> "nba"
            "baseball/mlb" -> "mlb"
            "hockey/nhl" -> "nhl"
            else -> null
        }

        fun bestEvent(match: Match, events: List<JsonElement>): JsonElement? {
            val awayTokens = nameTokens(match.away)
            val homeTokens = nameTokens(match.home)
            return events
                .map { event -> event to score(event, awayTokens, homeTokens) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }
                ?.first
        }

        private fun score(event: JsonElement, awayTokens: Set<String>, homeTokens: Set<String>): Int {
            var score = 0
            for (bookmaker in event["bookmakers"].array) {
                for (market in bookmaker["markets"].array.filter { it["marketType"].string() == "moneyline" }) {
                    for (outcome in market["outcomes"].array) {
                        val name = normalize(outcome["name"].string())
                        if (awayTokens.any(name::contains)) score++
                        if (homeTokens.any(name::contains)) score++
                    }
                }
            }
            return score
        }

        fun display(event: JsonElement?, match: Match): MatchOdds? {
            val bookmaker = preferredBookmaker(event["bookmakers"].array) ?: return null
            val outcomes = bookmaker["markets"].array.firstOrNull { it["marketType"].string() == "moneyline" }["outcomes"].array
            val odds = MatchOdds(
                bookmaker = bookmaker["bookmakerName"].string("Sportsbook"),
                away = price(match.away, outcomes),
                home = price(match.home, outcomes),
                draw = outcomes.firstOrNull { normalize(it["name"].string()).contains("draw") }?.let(::priceOf),
            )
            return odds.takeIf { it.hasGameLine }
        }

        private fun preferredBookmaker(bookmakers: List<JsonElement>): JsonElement? {
            val withMoneyline = bookmakers.filter(::hasMoneyline)
            return withMoneyline.firstOrNull { it["bookmakerId"].string() == "draftkings" }
                ?: withMoneyline.firstOrNull { it["sourceType"].string() == "sportsbook" }
                ?: withMoneyline.firstOrNull()
        }

        private fun hasMoneyline(bookmaker: JsonElement): Boolean =
            bookmaker["markets"].array.any { market ->
                market["marketType"].string() == "moneyline" && market["outcomes"].array.any { priceOf(it) != null }
            }

        private fun price(team: TeamSide, outcomes: List<JsonElement>): Int? {
            val tokens = nameTokens(team)
            return outcomes.firstOrNull { outcome ->
                val name = normalize(outcome["name"].string())
                tokens.any(name::contains)
            }?.let(::priceOf)
        }

        private fun priceOf(outcome: JsonElement): Int? =
            (outcome["price"] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }

        private fun nameTokens(team: TeamSide): Set<String> =
            listOf(team.displayName, team.shortName, team.abbreviation).map(::normalize).flatMap { normalized ->
                listOf(normalized) + normalized.split(' ').filter { it.length >= 3 }
            }.filter { it.length >= MIN_TOKEN }.toSet() // two-letter codes ("NO", "LA") match inside unrelated names

        private fun normalize(value: String): String =
            decomposeText(value)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .joinToString(" ")
    }
}
