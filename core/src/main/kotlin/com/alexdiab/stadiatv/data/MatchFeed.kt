package com.alexdiab.stadiatv.data

import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.domain.GameState
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.domain.Match
import com.alexdiab.stadiatv.sports.FeaturedDemand
import com.alexdiab.stadiatv.sports.SportsRepository
import com.alexdiab.stadiatv.util.Dates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Everything a Home/Following/Live screen needs, derived once per feed update. */
@Immutable
data class FeedSections(
    val all: List<Match> = emptyList(),
    /** Every game in progress, in any league (the Sports hub). */
    val liveNow: List<Match> = emptyList(),
    /** The part of [liveNow] that is the viewer's: their leagues and their teams (Home). */
    val liveForYou: List<Match> = emptyList(),
    val favoriteLive: List<Match> = emptyList(),
    val favoriteToday: List<Match> = emptyList(),
    val favoriteUpcoming: List<Match> = emptyList(),
    val favoriteRecent: List<Match> = emptyList(),
    val nextAcrossSports: List<Match> = emptyList(),
    val startingSoon: List<Match> = emptyList(),
    val startingSoonForYou: List<Match> = emptyList(),
    val upcoming: List<Match> = emptyList(),
    val recent: List<Match> = emptyList(),
    val prime: Match? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** The last refresh got nothing back, so what is shown is what was fetched earlier: say so, never present it as current. */
    val refreshFailed: Boolean = false,
    /** When the games on screen were last fetched, or 0 before the first load. */
    val updatedAt: Long = 0L,
) {
    val isEmpty: Boolean get() = liveNow.isEmpty() && upcoming.isEmpty() && favoriteUpcoming.isEmpty() && recent.isEmpty()
}

/**
 * The app-wide sports feed. A faithful port of the iOS `HomeViewModel`:
 *
 * - Three loads start together (today's scoreboards, a 30-day schedule for followed leagues, a
 *   365-day schedule for favourite teams' leagues) and each folds into one running cache as it
 *   lands, so the screen fills in progressively rather than waiting on the slowest league.
 * - The cache is never wiped between refreshes, so games don't vanish mid-session.
 * - Rebuilds are throttled so eleven leagues landing together cost one recomposition, not eleven.
 */
class MatchFeed(
    private val sports: SportsRepository,
    private val preferences: PreferencesStore,
    private val demand: FeaturedDemand,
    private val scope: CoroutineScope,
) {
    private val _sections = MutableStateFlow(FeedSections())
    val sections: StateFlow<FeedSections> = _sections.asStateFlow()

    private val loadLock = Mutex()
    private val byLeague = HashMap<String, List<Match>>()
    private var lastSignature = ""
    private var lastLoadedAt = 0L
    private var lastFlush = 0L

    /** Keep accepted games/timestamps, but do not leave a cold offline Home spinner running indefinitely. */
    fun markOffline() { _sections.update { it.copy(isLoading = false, error = "Offline · showing any previously loaded games.", refreshFailed = true) } }

    /** Loads the feed. Cheap when data is fresh and nothing changed (unless [force]). */
    suspend fun load(force: Boolean = false, waitForCurrent: Boolean = false) {
        // Preference-keyed callers must not lose their reload if an older signature is loading.
        // Timer/refresh callers still coalesce instead of creating a queue of duplicate loads.
        if (waitForCurrent) loadLock.lock() else if (!loadLock.tryLock()) return
        try {
            val settings = preferences.current
            val signature = settings.feedSignature
            val hasSnapshot = !_sections.value.isEmpty
            val ttl = if (_sections.value.liveNow.isEmpty()) CACHE_MILLIS else CACHE_LIVE_MILLIS
            if (!force && hasSnapshot && signature == lastSignature && System.currentTimeMillis() - lastLoadedAt < ttl) return
            _sections.update { it.copy(isLoading = true, error = null) }
            val followed = settings.followedLeagues
            val discovery = (followed + League.ALL).distinctBy { it.path }
            val favoriteLeagues = League.ALL.filter { league -> settings.favoriteTeams.any { it.leaguePath == league.path } }
            val failures = CopyOnWriteArrayList<String>()
            val gotAnything = AtomicBoolean(false)

            coroutineScope {
                val today = async {
                    sports.liveScores(discovery).collect { result ->
                        val error = result.error
                        if (error != null) failures += "${result.league.shortName}: ${error.message ?: "unavailable"}"
                        else gotAnything.set(true)
                        if (result.matches.isNotEmpty()) absorb(result.league, result.matches, throttled = true)
                    }
                }
                // Keep a fast month lane for favourites too: don't wait for all season chunks.
                // Its identical first 30-day ESPN chunk coalesces in HttpGateway with the season request.
                val week = async { loadSchedules((followed + favoriteLeagues).distinctBy { it.path }, 30, 3, failures, gotAnything) }
                val season = async { loadSchedules(favoriteLeagues, 365, 6, failures, gotAnything) }
                awaitAll(today, week, season)
            }
            val hasData = synchronized(byLeague) { byLeague.isNotEmpty() }
            rebuild(
                isLoading = false,
                error = if (!gotAnything.get() && !hasData) summarize(failures) else null,
                refreshFailed = !gotAnything.get() && hasData,
            )
            if (hasData || gotAnything.get()) {
                lastSignature = signature
                lastLoadedAt = System.currentTimeMillis()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } finally {
            _sections.update { it.copy(isLoading = false) }
            loadLock.unlock()
        }
    }

    /** Keeps scores fresh while a screen is visible: 30s with live games, otherwise 60s. */
    suspend fun autoRefresh() {
        while (true) {
            delay(if (_sections.value.liveNow.isEmpty()) 60_000 else 30_000)
            load()
        }
    }

    private suspend fun loadSchedules(
        leagues: List<League>,
        days: Int,
        concurrency: Int,
        failures: MutableList<String>,
        gotAnything: AtomicBoolean,
    ) = coroutineScope {
        val gate = Semaphore(concurrency)
        leagues.map { league ->
            async {
                gate.withPermit {
                    try {
                        val matches = sports.schedule(league, System.currentTimeMillis(), days)
                        gotAnything.set(true)
                        absorb(league, matches, throttled = false)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        failures += "${league.name}: ${error.message ?: "unavailable"}"
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun absorb(league: League, matches: List<Match>, throttled: Boolean) {
        synchronized(byLeague) {
            byLeague[league.path] = merge((byLeague[league.path].orEmpty()) + matches)
        }
        val now = System.currentTimeMillis()
        if (!throttled || now - lastFlush >= FLUSH_MILLIS) {
            lastFlush = now
            rebuild(isLoading = true, error = null)
        }
    }

    private suspend fun rebuild(isLoading: Boolean, error: String?, refreshFailed: Boolean = false) {
        val snapshot = synchronized(byLeague) { HashMap(byLeague) }
        val settings = preferences.current
        val next = withContext(Dispatchers.Default) { buildSections(snapshot, settings, isLoading, error) }
            .copy(refreshFailed = refreshFailed, updatedAt = lastLoadedAt)
        _sections.value = next
    }

    private fun summarize(failures: List<String>): String {
        val parts = failures.distinct().take(3)
        return if (parts.isEmpty()) "Couldn't load sports data. Check your connection and try again."
        else "Couldn't load sports data: ${parts.joinToString("; ")}"
    }

    // region Section building

    private fun buildSections(
        byLeague: Map<String, List<Match>>,
        settings: UserSettings,
        isLoading: Boolean,
        error: String?,
    ): FeedSections = FeedSectionsBuilder.build(
        all = merge(byLeague.values.flatten()),
        settings = settings,
        now = System.currentTimeMillis(),
        demandBoost = demand::boost,
        isLoading = isLoading,
        error = error,
    )

    private fun merge(matches: List<Match>): List<Match> = MatchFeedMerger.merge(matches, System.currentTimeMillis())

    // endregion

    private companion object {
        const val CACHE_MILLIS = 120_000L
        const val CACHE_LIVE_MILLIS = 25_000L
        const val FLUSH_MILLIS = 250L
    }
}
