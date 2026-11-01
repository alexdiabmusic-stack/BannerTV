package com.alexdiab.stadiatv.data.news

import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import io.ktor.http.*
import com.alexdiab.stadiatv.foundation.*
import com.alexdiab.stadiatv.foundation.SportsIOException as IOException

data class TeamPodcastSeed(val id: String, val league: String, val sport: String, val teamName: String,
    val aliases: List<String>, val storefront: String, val searchTerms: List<String>)
data class TeamPodcastCandidate(val feed: String, val title: String, val publisher: String = "", val image: String? = null,
    val sport: String = "", val tags: List<String> = emptyList())
data class TeamPodcastResult(val shows: List<TeamPodcastCandidate>, val checkedAt: Long?, val unavailable: Boolean,
    val retainedPrevious: Boolean = false)

/** Public Apple Search equivalent of iOS TeamPodcastResolver. No embedded PodcastIndex secret. */
class TeamPodcastResolver(private val http: SportsHttp, private val endpoint: String = "https://itunes.apple.com/search",
    private val now: () -> Long = ::nowMillis) {
    private data class Entry(val result: TeamPodcastResult, val expiresAt: Long)
    private val lock = Mutex()
    private val cache = linkedMapOf<String, Entry>()
    private var blockedUntil = 0L

    suspend fun resolve(team: FavoriteTeam, seeds: List<TeamPodcastSeed>, catalog: List<TeamPodcastCandidate>, refresh: Boolean = false): TeamPodcastResult = lock.withLock {
        require(team.name.isNotBlank() && team.name.length <= 256)
        val seed = seedFor(team, seeds) ?: fallbackSeed(team)
        val key = "${team.leaguePath}|${normalize(team.name)}"
        val previous = cache.remove(key)?.also { cache[key] = it }
        val local = rank(catalog, seed)
        if (now() < blockedUntil || !refresh && previous != null && previous.expiresAt > now()) {
            return@withLock previous?.result?.copy(shows = distinct(previous.result.shows + local), unavailable = previous.result.unavailable || now() < blockedUntil)
                ?: TeamPodcastResult(local, null, true)
        }
        val found = ArrayList<TeamPodcastCandidate>()
        var failed = false
        for (term in seed.searchTerms.distinct().take(5)) {
            try {
                val url = requestURL(endpoint, mapOf("term" to term, "media" to "podcast", "entity" to "podcast", "limit" to "50", "country" to seed.storefront))
                val raw = withTimeout(8_000) {
                    http.getJson(url.toString(), headers = mapOf("Accept" to "application/json", "User-Agent" to "BannerTV/1.0",
                        "Cache-Control" to "max-age=0"), ttlMillis = 0, retries = 0, maxResponseBytes = 2L * 1024 * 1024)
                }
                found += candidates(raw, seed)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failed = true
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: Exception) {
                failed = true
                if (error is HttpStatusException && error.code in setOf(401, 403, 429)) {
                    blockedUntil = now() + if (error.code == 429) (error.retryAfterSeconds ?: 300).coerceIn(60, 3600) * 1000 else 300_000
                    break // Respect denial/rate limits; do not try another country, path, proxy or query.
                }
            }
        }
        val ranked = rank(found, seed)
        val stale = if (failed) previous?.result?.shows.orEmpty() else emptyList()
        // Preserve previously verified title/publisher/artwork ahead of thinner bundled duplicates
        // during an outage; a failed search must not turn a rich show card into a placeholder.
        val result = TeamPodcastResult(distinct(ranked + stale + local),
            if (failed) previous?.result?.checkedAt else now(), failed, stale.isNotEmpty())
        // Only a complete successful nonempty resolution gets the iOS seven-day cache lifetime.
        cache[key] = Entry(result, now() + if (!failed && ranked.isNotEmpty()) 7 * 86_400_000L else 60_000)
        while (cache.size > 32) cache.remove(cache.keys.first())
        result
    }

    companion object {
        private val leagues = mapOf("hockey/nhl" to "NHL", "football/nfl" to "NFL", "basketball/nba" to "NBA",
            "baseball/mlb" to "MLB", "soccer/usa.1" to "MLS")
        fun normalize(value: String): String = decomposeCompatibilityText(value)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        private fun contains(text: String, phrase: String) = phrase.isNotBlank() && " $text ".contains(" $phrase ")
        fun feedKey(value: String): String? = runCatching {
            val clean = value.trim().takeIf { it.length <= 4096 && safeWebURL(it, httpsOnly = false) } ?: return null
            URLBuilder(clean).apply { host = host.lowercase(); fragment = "" }.buildString()
        }.getOrNull()
        private fun distinct(shows: List<TeamPodcastCandidate>) = shows.distinctBy { feedKey(it.feed) }.take(30)
        fun seedFor(team: FavoriteTeam, seeds: List<TeamPodcastSeed>): TeamPodcastSeed? {
            val league = leagues[team.leaguePath] ?: return null
            val name = normalize(team.name)
            return seeds.filter { it.league.equals(league, true) && (normalize(it.teamName) == name ||
                it.aliases.any { alias -> normalize(alias).let { it.length >= 4 && it == name } }) }.singleOrNull()
        }
        private fun fallbackSeed(team: FavoriteTeam): TeamPodcastSeed {
            val league = leagues[team.leaguePath] ?: team.league?.shortName.orEmpty()
            val nickname = team.name.substringAfterLast(' ')
            return TeamPodcastSeed(team.key, league, team.league?.group?.label.orEmpty(), team.name,
                listOf(team.name, nickname).distinct(), "US", listOf("${team.name} podcast", "Locked On ${team.name}", "${team.name} $league podcast"))
        }
        /** iOS weights/threshold; token boundaries and unique aliases prevent accidental substring inflation. */
        fun score(show: TeamPodcastCandidate, seed: TeamPodcastSeed): Int {
            val text = normalize("${show.title} ${show.publisher}")
            val full = normalize(seed.teamName)
            var score = if (contains(text, full)) 140 else 0
            for (alias in seed.aliases.map(::normalize).distinct().filter { it != full && it.length >= 4 })
                if (contains(text, alias)) score += 55
            if (contains(text, "locked on")) score += 12
            if (contains(text, normalize(seed.league))) score += 8
            return score
        }
        fun rank(shows: List<TeamPodcastCandidate>, seed: TeamPodcastSeed): List<TeamPodcastCandidate> = shows.mapNotNull { show ->
            val key = feedKey(show.feed) ?: return@mapNotNull null
            val directTag = show.tags.any { normalize(it) == normalize(seed.teamName) }
            val score = score(show, seed).coerceAtLeast(if (directTag) 140 else 0)
            if (score < 70) null else show.copy(feed = key) to score
        }.sortedWith(compareByDescending<Pair<TeamPodcastCandidate, Int>> { it.second }
            .thenBy { normalize(it.first.title) }.thenBy { it.first.feed }).distinctBy { it.first.feed }.take(30).map { it.first }

        fun seeds(raw: JsonElement): List<TeamPodcastSeed> {
            val rows = raw["teams"] as? JsonArray ?: throw IOException("Team podcast registry missing")
            if (rows.size > 512) throw IOException("Team podcast registry exceeds bounds")
            fun text(raw: JsonElement?, max: Int = 256) = raw.stringOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }
            return rows.map { row ->
                fun values(key: String, limit: Int) = (row[key] as? JsonArray)?.takeIf { it.size <= limit }?.map { text(it) ?: throw IOException("Team podcast $key invalid") }
                    ?: throw IOException("Team podcast $key missing")
                val country = text(row["country_storefront"], 2)?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }
                    ?: throw IOException("Team podcast country missing")
                TeamPodcastSeed(text(row["id"]) ?: throw IOException("Team podcast identity missing"),
                    text(row["league"], 32) ?: throw IOException("Team podcast league missing"),
                    text(row["sport"], 64).orEmpty(), text(row["team_name"]) ?: throw IOException("Team podcast name missing"),
                    values("aliases", 24), country, values("search_terms", 8).takeIf { it.isNotEmpty() } ?: throw IOException("Team podcast queries missing"))
            }.also { if (it.distinctBy { seed -> seed.id }.size != it.size) throw IOException("Team podcast identity duplicated") }
        }
        private fun candidates(raw: JsonElement, seed: TeamPodcastSeed): List<TeamPodcastCandidate> {
            val rows = raw["results"] as? JsonArray ?: throw IOException("Podcast search results missing")
            if (rows.size > 50) throw IOException("Podcast search results exceed bounds")
            return rows.mapNotNull { row ->
                val feed = feedKey(row["feedUrl"].string()) ?: return@mapNotNull null
                val title = row["collectionName"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 } ?: return@mapNotNull null
                val publisher = row["artistName"].string().take(512)
                // Search returning a feed is NOT evidence of team relevance. Add team tags only
                // after the title/author passes the iOS score, or a later catalog rank would trust them.
                val candidate = TeamPodcastCandidate(feed, title, publisher, feedKey(row["artworkUrl600"].string()), seed.sport)
                candidate.takeIf { score(it, seed) >= 70 }?.copy(tags = listOf(seed.league.lowercase(), seed.teamName.lowercase()))
            }
        }
    }
}
