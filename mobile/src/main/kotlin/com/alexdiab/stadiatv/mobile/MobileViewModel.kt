package com.alexdiab.stadiatv.mobile

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.alexdiab.stadiatv.StadiaTvApplication
import com.alexdiab.stadiatv.domain.*
import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.live.Playlist
import com.alexdiab.stadiatv.live.Programme
import com.alexdiab.stadiatv.live.CatchupResolver
import com.alexdiab.stadiatv.playback.BufferProfile
import com.alexdiab.stadiatv.playback.PlaybackController
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.alexdiab.stadiatv.sports.NativeGameInsights
import com.alexdiab.stadiatv.sports.NativeGameCentres
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyFixtures
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyPool
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyPoolKey
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyPoolLoader
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyPoolSource
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyContexts
import com.alexdiab.stadiatv.data.fantasy.NativeFantasyEventIndex

enum class PhoneTab(val label: String) { Home("Home"), Following("Following"), Live("Live"), Discover("Discover"), Settings("Settings") }
enum class PhoneLiveSection(val label: String) { Channels("Channels"), Guide("Guide"), Sports("Sports") }

sealed interface PhonePage {
    data class Game(val match: Match) : PhonePage
    data object Search : PhonePage
    data object Teams : PhonePage
    data object Playlists : PhonePage
    data class PlaylistForm(val editing: Playlist? = null) : PhonePage
    data class LeagueHub(val league: League, val initialSection: String = "Scores", val nflWeek: com.alexdiab.stadiatv.sports.NflWeek? = null, val calendarDate: Long? = null) : PhonePage
    data class TeamDetail(val team: Team) : PhonePage
    data class PlayerDetail(val league: League, val athlete: Athlete) : PhonePage
    data object Fantasy : PhonePage
    data object SavedArticles : PhonePage
    data object History : PhonePage
    data object Multiview : PhonePage
    data class Reader(val article: Article) : PhonePage
    data object Podcasts : PhonePage
    data class Podcast(val show: PodcastShow) : PhonePage
    data object AudioPlayer : PhonePage
    data class Golf(val id: String) : PhonePage
    data object CloudBackup : PhonePage
    data object Addons : PhonePage
    data object Cinema : PhonePage
    data class CinemaDetail(val id: com.alexdiab.stadiatv.catalog.MediaId) : PhonePage
    data object ChannelGroups : PhonePage
    data class Stats(val leaguePath: String? = null) : PhonePage
    data object Premium : PhonePage
    data class FollowingSchedule(val entity: String, val sport: String? = null) : PhonePage
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MobileViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val container = (application as StadiaTvApplication).container
    val library = PhoneLibrary(application)
    val mediaLanguage = java.util.Locale.getDefault().toLanguageTag()
    val mediaHome = PhoneSnapshotCache<String, com.alexdiab.stadiatv.catalog.CinemaHome>(viewModelScope, capacity=1, timeoutMillis=45_000,
        isComplete={ it.shelves.none { shelf -> shelf.unavailable } }) { language -> withContext(Dispatchers.IO) { container.tmdb.home(language) } }
    val mediaArtwork = PhoneSnapshotCache<String, com.alexdiab.stadiatv.catalog.TmdbArtwork>(viewModelScope, capacity=1, ttlMillis=86_400_000) {
        withContext(Dispatchers.IO) { container.tmdb.artwork() }
    }
    val mediaDetails = PhoneSnapshotCache<com.alexdiab.stadiatv.catalog.MediaId, com.alexdiab.stadiatv.catalog.MediaDetail>(viewModelScope, capacity=12, timeoutMillis=25_000) { id ->
        withContext(Dispatchers.IO) { container.tmdb.detail(id,mediaLanguage) }
    }
    val entitlements = PhoneBilling(application, viewModelScope, container.http)
    val articleStore = PhoneArticleStore(application, container.http)
    val podcasts = PhonePodcastsStore.get(application)
    val cast = PhoneCast(application)
    val nativeFantasy = PhoneNativeFantasyStore(application)
    internal val fantasyGuideCache = PhoneFantasyGuideCache()
    private val nativeFantasyPoolLoader = NativeFantasyPoolLoader()
    val nativeFantasyPools = PhoneSnapshotCache<NativeFantasyPoolKey, NativeFantasyPool>(viewModelScope, capacity = 4, ttlMillis = 3_600_000, timeoutMillis = 90_000, isComplete = { it.complete }) { key ->
        withContext(Dispatchers.IO) {
            val league = requireNotNull(League.find(requireNotNull(NativeFantasyFixtures.leaguePath(key.sport))))
            val native = key.source == NativeFantasyPoolSource.Native
            nativeFantasyPoolLoader.load(key,
                teams = { if (native) container.sports.teams(league) else container.espn.teams(league) },
                roster = { team -> if (native) container.sports.roster(league, team.id) else container.espn.roster(league, team.id) })
        }
    }
    val followingTables = PhoneSnapshotCache<String, List<StandingGroup>>(viewModelScope) { path ->
        withContext(Dispatchers.IO) { container.sports.standings(requireNotNull(League.find(path))) }
    }
    val statsTeams = PhoneSnapshotCache<String, List<Team>>(viewModelScope) { path ->
        withContext(Dispatchers.IO) { container.sports.teams(requireNotNull(League.find(path))) }
    }
    val leagueSchedules = PhoneSnapshotCache<PhoneLeagueScheduleQuery, List<Match>>(viewModelScope, capacity = 6, ttlMillis = 120_000, timeoutMillis = 20_000) { query ->
        withContext(Dispatchers.IO) { container.sports.schedule(requireNotNull(League.find(query.leaguePath)), query.first, query.days)
            .filter { it.startMillis in query.first until query.endExclusive }.distinctBy { it.key }.sortedBy { it.startMillis } }
    }
    val leagueInjuries = PhoneSnapshotCache<String, List<Injury>>(viewModelScope, capacity = 3, ttlMillis = 900_000, timeoutMillis = 20_000) { path ->
        withContext(Dispatchers.IO) { container.sports.injuries(requireNotNull(League.find(path))) }
    }
    val teamRosters = PhoneSnapshotCache<Pair<String, String>, List<RosterGroup>>(viewModelScope, capacity = 6, ttlMillis = 3_600_000) { (path, id) ->
        withContext(Dispatchers.IO) { container.sports.roster(requireNotNull(League.find(path)), id) }
    }
    val playerProfiles = PhoneSnapshotCache<Pair<String, Athlete>, AthleteOverview>(viewModelScope, ttlMillis = 3_600_000) { (path, person) ->
        withContext(Dispatchers.IO) { container.sports.athlete(requireNotNull(League.find(path)), person) }
    }
    private val nativeFantasyClock = MutableStateFlow(System.currentTimeMillis())
    internal fun updateNativeFantasyClock(now: Long) { nativeFantasyClock.value = now }
    val nativeFantasyContexts = combine(nativeFantasy.state, leagueSchedules.state, container.matchFeed.sections, nativeFantasyClock) { library, schedules, feed, now ->
        val league = NativeFantasyContexts.selected(library)
        val query = phoneNativeFantasyScheduleQuery(league, now)
        NativeFantasyContexts.build(league, schedules[query]?.value.orEmpty() + feed.all, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NativeFantasyEventIndex())
    val followingNews = PhoneSnapshotCache<List<String>, List<Article>>(viewModelScope, timeoutMillis = 12_000) { paths ->
        coroutineScope {
            val batches = paths.take(3).map { path -> async(Dispatchers.IO) {
                try { kotlinx.coroutines.withTimeout(8_000) { container.espn.news(requireNotNull(League.find(path)), 5) } }
                catch (error: kotlinx.coroutines.TimeoutCancellationException) { emptyList() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
            } }.awaitAll()
            if (batches.all { it.isEmpty() }) throw IllegalStateException("No headlines available")
            val ids = HashSet<String>(); val titles = HashSet<String>()
            batches.flatten().sortedByDescending { it.publishedMillis ?: 0L }.filter { ids.add(it.id) && titles.add(it.headline.lowercase().trim()) }.take(8)
        }
    }
    fun castCurrent() {
        val current = channel
        val episode = PhoneAudioService.ui.value.episode
        if (current == null && episode == null) { message = "Start a channel or podcast, then choose Play current on TV."; return }
        if (current?.headers?.isNotEmpty() == true) { message = "This channel needs provider-specific HTTP headers. The default Cast receiver cannot forward those headers; use playback on this phone."; return }
        val url = replay?.url ?: current?.streamUrl ?: episode!!.media
        val path = Uri.parse(url).path.orEmpty().lowercase()
        val mime = if (current == null) episode!!.mime else when { path.endsWith(".m3u8") -> "application/x-mpegURL"; path.endsWith(".mp4") -> "video/mp4"; path.endsWith(".ts") -> "video/mp2t"; path.endsWith(".mpd") -> "application/dash+xml"; else -> { message = "This stream doesn't identify a Cast-supported media format. Try another source."; return } }
        cast.load(url, replay?.programme?.title ?: current?.canonicalName ?: current?.name ?: episode!!.title, current?.playlistName ?: episode!!.show, current?.logoUrl ?: episode?.image, mime, current != null && replay == null, if (replay != null) controller.ui.value.timeline.position else if (current != null) 0 else PhoneAudioService.ui.value.position) { success ->
            if (success) { controller.player.pause(); PhoneAudioService.pauseActive() } else message = "The receiver couldn't load this media. Check its codec, network access and provider restrictions. Playback on your phone is unchanged."
        }
    }
    private val audioConnection = lazy { PhoneAudioConnection(application) }
    val audio: PhoneAudioConnection get() = audioConnection.value
    fun playPodcast(episode: PhoneEpisode) {
        stopPlayer()
        viewModelScope.launch { try { audio.play(episode); open(PhonePage.AudioPlayer) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { message = "This podcast couldn't start. Try again or choose another episode." } }
    }
    fun playAudioSearch(query: String) {
        if (query.isBlank()) { open(PhonePage.Podcasts); return }
        viewModelScope.launch {
            try {
                val episode = withContext(Dispatchers.IO) { val show = podcasts.search(query.take(300)).firstOrNull(); show?.let { podcasts.episodes(it).firstOrNull { e -> !e.mime.startsWith("video") } } }
                if (episode != null) playPodcast(episode) else { open(PhonePage.Podcasts); message = "No playable audio episode matched that search." }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { message = "Podcast search is unavailable. Try your saved shows or downloads." }
        }
    }
    val pages = mutableStateListOf<PhonePage>()
    var tab by mutableStateOf(PhoneTab.entries.firstOrNull { it.name == saved.get<String>("tab") } ?: PhoneTab.Home)
        private set
    var liveSection by mutableStateOf(PhoneLiveSection.entries.firstOrNull { it.name == saved.get<String>("liveSection") } ?: PhoneLiveSection.Channels)
        private set
    var allLiveSports by mutableStateOf(saved.get<Boolean>("allLiveSports") ?: true)
        private set
    var channel by mutableStateOf<Channel?>(null)
        private set
    var playingMatch by mutableStateOf<Match?>(null)
        private set
    var replay by mutableStateOf<PhoneReplay?>(null)
        private set
    var fullPlayer by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    val controller = PlaybackController(application, profile())
    private val tried = HashSet<String>()
    private var linkedProgramme: String? = null
    private val details = PhoneGameSnapshotStore { setOfNotNull((pages.lastOrNull() as? PhonePage.Game)?.match?.key, playingMatch?.key) }
    val games = details.asStateFlow()
    private val nativeCentres = NativeGameCentres(container.http, container.nfl, container.hockeyDetails, container.baseballDetails, container.baseballExtras, container.basketballDetails, container.soccerDetails, mls = container.mlsDetails, laLiga = container.laLigaDetails, laLigaProvider = container.laLiga)
    internal val connection = phoneConnection(application).onEach { network ->
        if (network.known && network.online) {
            nativeCentres.onNetworkRestored()
            container.sports.onNetworkRestored()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), PhoneConnection())
    private val loadingGames = HashSet<String>()
    private val nativeInsights = NativeGameInsights(container.http)
    private val loadingShots = HashSet<String>()
    private val loadingNative = HashSet<String>()
    private val searchIndex = MutableStateFlow(PhoneSearchDirectory())
    val searchDirectory = searchIndex.asStateFlow()
    private var searchStarted = false
    private var searchSignature = ""
    var pendingMultiviewChannel: Channel? = null
        private set

    fun beginMultiview() {
        if (!entitlements.state.value.isPremium) { open(PhonePage.Premium); return }
        PhoneAudioService.pauseActive(); pendingMultiviewChannel = channel; stopPlayer(); open(PhonePage.Multiview)
    }
    fun consumeMultiviewChannel(): Channel? = pendingMultiviewChannel.also { pendingMultiviewChannel = null }

    fun prepareSearch(force: Boolean = false) {
        val signature = container.preferences.current.favoriteTeams.joinToString { it.key }
        if (searchIndex.value.loading || searchStarted && signature == searchSignature && !force) return
        searchStarted = true
        searchSignature = signature
        searchIndex.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try { coroutineScope {
                val gate = Semaphore(2)
                val tasks = League.ALL.map { league -> async {
                    gate.withPermit {
                        try { val teams = container.sports.teams(league); searchIndex.update { it.copy(teams = (it.teams + teams).distinctBy { team -> "${team.league.path}:${team.id}" }) } }
                        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                    }
                } }
                val rosters = container.preferences.current.favoriteTeams.take(6).mapNotNull { team -> team.league?.let { league -> async {
                    gate.withPermit {
                        try { val players = container.sports.roster(league, team.teamId).flatMap { it.athletes }.map { league to it }; searchIndex.update { it.copy(players = (it.players + players).distinctBy { player -> "${player.first.path}:${player.second.id}" }) } }
                        catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                    }
                } } }
                (tasks + rosters).awaitAll()
            } } finally { searchIndex.update { it.copy(loading = false) } }
        }
    }

    init {
        container.start()
        controller.onFailure = failure@{
            // A failed archive is not permission to jump to live TV or another provider's broadcast.
            if (replay != null) return@failure
            val current = channel
            val backup = current?.let { container.lineup.lineup.value.entryFor(it.key) }
                ?.streamsInOrder(container.lineup.prefs.value)?.firstOrNull { it.channel.key !in tried }?.channel
            if (backup != null) startChannel(backup, playingMatch, reset = false)
        }
        viewModelScope.launch { container.matchFeed.load() }
        viewModelScope.launch(Dispatchers.IO) { combine(container.matchFeed.sections, container.preferences.settings) { feed, settings -> feed to settings }.collect { (feed, settings) -> if (feed.all.isNotEmpty()) PhoneWidgets.publish(application, feed.all, settings, feed.updatedAt) } }
        viewModelScope.launch { combine(container.matchFeed.sections, container.preferences.settings, library.state) { feed, settings, _ -> feed to settings }.collect { (feed, settings) -> if (feed.all.isNotEmpty()) PhoneNotifications.sync(application, library, feed.all, settings) } }
    }

    fun selectTab(value: PhoneTab) { tab = value; saved["tab"] = value.name; pages.clear() }
    fun selectLiveSection(value: PhoneLiveSection) { liveSection = value; saved["liveSection"] = value.name }
    fun selectAllLiveSports(value: Boolean) { allLiveSports = value; saved["allLiveSports"] = value }
    fun openLive(section: PhoneLiveSection) {
        selectLiveSection(section)
        if (section == PhoneLiveSection.Sports) selectAllLiveSports(true)
        selectTab(PhoneTab.Live)
        fullPlayer = false
    }
    fun open(page: PhonePage) {
        if (pages.lastOrNull() != page) pages += page
        // A team/player opened from the companion must be visible; playback continues in the mini-player.
        fullPlayer = false
    }
    fun back() { if (pages.isNotEmpty()) pages.removeAt(pages.lastIndex) }

    fun play(value: Channel, match: Match? = null) { startChannel(value, match, reset = true); fullPlayer = true }
    private fun startChannel(value: Channel, match: Match?, reset: Boolean) {
        PhoneAudioService.pauseActive()
        if (reset) tried.clear()
        tried += value.key
        channel = value
        replay = null
        playingMatch = match
        linkedProgramme = container.guide.nowAndNext(value).first?.let { "${it.startMillis}:${it.endMillis}:${it.title}" }
        controller.load(value)
        container.watchHistory.record(value)
    }
    fun replayAvailability(value: Channel, programme: Programme) = CatchupResolver.resolve(value, programme, epgOffsetMinutes = container.guide.refFor(value)?.offsetMinutes ?: 0)
    fun playReplay(value: Channel, programme: Programme): Boolean {
        val resolved = replayAvailability(value, programme)
        val url = resolved.url ?: run { message = resolved.reason; return false }
        PhoneAudioService.pauseActive(); tried.clear(); tried += value.key
        channel = value; replay = PhoneReplay(programme, url); playingMatch = null; linkedProgramme = null
        controller.load(value.copy(streamUrl = url), isArchive = true)
        container.watchHistory.record(value) // Never persist the time-limited archive URL as a live channel.
        fullPlayer = true
        return true
    }
    fun returnToLive() { channel?.let { startChannel(it, null, reset = true) } }
    fun restart() { if (replay != null) controller.restart() else channel?.let { startChannel(it, playingMatch, reset = true) } }
    fun switchSource(value: Channel) {
        if (replay != null) { if (value.key != channel?.key) message = "Return to live before choosing a different source."; return }
        startChannel(value, playingMatch, reset = true)
    }
    fun playerSources(): List<Channel> = channel?.let { current ->
        if (replay != null) return@let listOf(current)
        container.lineup.lineup.value.entryFor(current.key)?.streamsInOrder(container.lineup.prefs.value)?.map { it.channel } ?: listOf(current)
    }.orEmpty()

    fun resolvePlayerMatch() {
        if (replay != null) return
        val programme = channel?.let { container.guide.nowAndNext(it).first }
        val signature = programme?.let { "${it.startMillis}:${it.endMillis}:${it.title}" }
        if (linkedProgramme != null && signature != linkedProgramme) playingMatch = null
        linkedProgramme = signature
        if (playingMatch != null) return
        val currentKey = channel?.key ?: return
        val candidates = container.matchFeed.sections.value.liveNow.filter { match ->
            container.streams.sourcesFor(match).confirmed.any { it.channel.key == currentKey && programme != null && it.programmeTitle.equals(programme.title, ignoreCase = true) }
        }
        if (candidates.size == 1) playingMatch = candidates.single()
    }

    fun loadGame(match: Match, force: Boolean = false, spatial: Boolean = false, insights: Boolean = false, tab: String = "Overview") {
        details.touch(match.key)
        val old = details.value[match.key]
        if (match.nativeReference == null && (force || old == null || System.currentTimeMillis() - old.checkedAt > 12_000) && loadingGames.add(match.key)) {
            viewModelScope.launch {
                details.update { it + (match.key to (old ?: PhoneGameSnapshot()).copy(refreshing = true)) }
                try {
                    val data = withContext(Dispatchers.IO) { container.sports.gameCentre(match) }
                    details.update { current -> current + (match.key to (current[match.key] ?: PhoneGameSnapshot()).copy(data = phoneRetainSummaryShots(data, current[match.key]?.data), checkedAt = System.currentTimeMillis(), refreshing = false, error = null)) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { details.update { it + (match.key to (it[match.key] ?: PhoneGameSnapshot()).copy(refreshing = false, error = "Game data couldn't refresh. Showing the last available information.")) } }
                finally { loadingGames.remove(match.key) }
            }
        }
        if ((force || System.currentTimeMillis() - (old?.nativeAttemptAt ?: 0) > when (match.league.path) { "football/nfl" -> 5000; "basketball/nba", "basketball/wnba" -> 7000; "soccer/eng.1", "soccer/usa.1", "soccer/esp.1" -> 8000; else -> 12_000 }) && loadingNative.add(match.key)) {
            details.update { it + (match.key to (it[match.key] ?: PhoneGameSnapshot()).copy(nativeAttemptAt = System.currentTimeMillis(), nativeRefreshing = true, refreshing = match.nativeReference != null)) }
            viewModelScope.launch {
            try {
                if (details.value[match.key]?.nativeData == null) {
                    withContext(Dispatchers.IO) { nativeCentres.cached(match) }?.let { cached -> details.update { current ->
                        val snapshot = current[match.key] ?: return@update current
                        if (snapshot.nativeData != null) current else current + (match.key to snapshot.copy(nativeData = cached))
                    } }
                }
                val native = withContext(Dispatchers.IO) { nativeCentres.load(match, details.value[match.key]?.nativeData, force, tab) }
                native?.let { data ->
                    details.update { current ->
                        val snapshot = current[match.key] ?: PhoneGameSnapshot()
                        val concurrent = snapshot.nativeData?.takeIf { it.providerStamp?.provider == "MLB" && it.providerStamp?.gameId == data.providerStamp?.gameId }
                        val merged = if (concurrent == null) data else data.copy(videos = if ("highlights" in data.baseballExtras.checkedAt) data.videos else data.videos.ifEmpty { concurrent.videos },
                            winProbability = if ("probability" in data.baseballExtras.checkedAt) data.winProbability else data.winProbability.ifEmpty { concurrent.winProbability }, winProbabilitySource = data.winProbabilitySource ?: concurrent.winProbabilitySource,
                            baseballProbability = if ("probability" in data.baseballExtras.checkedAt) data.baseballProbability else data.baseballProbability.ifEmpty { concurrent.baseballProbability },
                            baseballExtras = if (data.baseballExtras.checkedAt.isEmpty()) concurrent.baseballExtras else data.baseballExtras)
                        current + (match.key to snapshot.copy(nativeData = merged, error = if (match.nativeReference != null) null else snapshot.error,
                            nativeFailureCount = if (phoneNativeRefreshFailed(merged)) (snapshot.nativeFailureCount + 1).coerceAtMost(6) else 0))
                    }
                    loadBaseballExtras(match, insights, force)
                }
                if (native == null && match.nativeReference != null) details.update { val snapshot = it[match.key] ?: PhoneGameSnapshot()
                    it + (match.key to snapshot.copy(nativeFailureCount = (snapshot.nativeFailureCount + 1).coerceAtMost(6), error = "${match.nativeReference?.provider}'s native game feed couldn't refresh. Any saved native data is unchanged.")) }
            } finally { loadingNative.remove(match.key); details.update { val snapshot = it[match.key] ?: return@update it
                it + (match.key to snapshot.copy(nativeRefreshing = false, refreshing = if (match.nativeReference != null) false else snapshot.refreshing)) } }
            }
        }
        if (spatial && match.league.group != SportGroup.Basketball && loadingShots.add(match.key)) viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { nativeInsights.shots(match) }?.let { (source, shots) ->
                    details.update { current ->
                        val snapshot = current[match.key] ?: PhoneGameSnapshot()
                        current + (match.key to snapshot.copy(data = snapshot.data.copy(shots = shots, shotSource = source)))
                    }
                }
            } finally { loadingShots.remove(match.key) }
        }
        loadBaseballExtras(match, insights, force)
    }
    private val loadingBaseballExtras = HashSet<String>()
    private val loadingSoccerTables = HashSet<String>()
    fun loadSoccerTable(match: Match, force: Boolean = false, overview: Boolean = false) {
        if (match.league.path !in setOf("soccer/eng.1", "soccer/usa.1", "soccer/esp.1")) return
        if (match.league.path == "soccer/esp.1" && match.nativeReference?.provider != "LaLiga") return
        val old = details.value[match.key]
        val sameKind = old?.soccerTable?.provisional == (match.state == GameState.LIVE && match.nativeReference?.provider != "LaLiga")
        val interval = if (overview) 120_000 else if (match.state == GameState.LIVE) 20_000 else 300_000
        if (!force && sameKind && System.currentTimeMillis() - (old?.soccerTableAttemptAt ?: 0) < interval) return
        if (!force && old?.soccerTable == null && System.currentTimeMillis() - (old?.soccerTableAttemptAt ?: 0) < 60_000) return
        if (!loadingSoccerTables.add(match.key)) return
        details.update { it + (match.key to (it[match.key] ?: PhoneGameSnapshot()).copy(soccerTableAttemptAt = System.currentTimeMillis(), soccerTableLoading = true)) }
        viewModelScope.launch {
            try {
                val table = withContext(Dispatchers.IO) { nativeCentres.soccerTable(match) }
                details.update { it + (match.key to (it[match.key] ?: PhoneGameSnapshot()).copy(soccerTable = table, soccerTableError = null)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { details.update { it + (match.key to (it[match.key] ?: PhoneGameSnapshot()).copy(soccerTableError = "The native ${match.league.shortName} table couldn't refresh. Any saved table is unchanged.")) } }
            finally { loadingSoccerTables.remove(match.key); details.update { it + (match.key to (it[match.key] ?: PhoneGameSnapshot()).copy(soccerTableLoading = false)) } }
        }
    }
    fun retryBaseballExtras(match: Match, kind: String) {
        if (kind !in setOf("highlights", "probability")) return
        loadBaseballExtras(match, probability = kind == "probability", force = true, only = kind)
    }
    private fun loadBaseballExtras(match: Match, probability: Boolean, force: Boolean, only: String? = null) {
        if (match.league.path != "baseball/mlb") return
        val snapshot = details.value[match.key] ?: return
        val gameId = snapshot.nativeData?.providerStamp?.takeIf { it.provider == "MLB" }?.gameId ?: return
        val surfaces = only?.let(::listOf) ?: if (probability) listOf("highlights", "probability") else listOf("highlights")
        for (kind in surfaces) {
            val checked = if (kind == "highlights") snapshot.baseballContentAt else snapshot.baseballProbabilityAt
            if (!force && System.currentTimeMillis() - checked < if (kind == "highlights") 300_000 else 20_000) continue
            val key = "${match.key}:$kind"
            if (!loadingBaseballExtras.add(key)) continue
            details.update { values -> val old = values[match.key] ?: return@update values
                values + (match.key to if (kind == "highlights") old.copy(baseballContentAt = System.currentTimeMillis()) else old.copy(baseballProbabilityAt = System.currentTimeMillis())) }
            viewModelScope.launch {
                try {
                    val videos = if (kind == "highlights") withContext(Dispatchers.IO) { nativeCentres.baseballHighlights(gameId) } else null
                    val points = if (kind == "probability") withContext(Dispatchers.IO) { nativeCentres.baseballProbability(gameId) } else null
                    details.update { values ->
                        val old = values[match.key] ?: return@update values; val native = old.nativeData ?: return@update values
                        if (native.providerStamp?.gameId != gameId) return@update values
                        val surfaceAt = videos?.checkedAt ?: points?.checkedAt ?: return@update values
                        val saved = videos?.saved ?: points?.saved ?: false
                        val extraState = native.baseballExtras.copy(checkedAt = native.baseballExtras.checkedAt + (kind to surfaceAt),
                            saved = if (saved) native.baseballExtras.saved + kind else native.baseballExtras.saved - kind)
                        values + (match.key to old.copy(nativeData = native.copy(videos = videos?.value ?: native.videos,
                            winProbability = points?.value?.map { it.homeProbability } ?: native.winProbability, baseballProbability = points?.value ?: native.baseballProbability,
                            winProbabilitySource = if (points != null) "MLB · reported at-bats" else native.winProbabilitySource, baseballExtras = extraState),
                            baseballExtraErrors = if (saved) old.baseballExtraErrors + (kind to "Saved MLB $kind · ${snapshotTime(surfaceAt)}. Refresh unavailable.") else old.baseballExtraErrors - kind))
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { details.update { values -> val old = values[match.key] ?: return@update values; values + (match.key to old.copy(baseballExtraErrors = old.baseballExtraErrors + (kind to "MLB $kind couldn't refresh; other game data is unchanged."))) } }
                finally { loadingBaseballExtras.remove(key) }
            }
        }
    }
    fun stopPlayer() { controller.stop(); channel = null; replay = null; playingMatch = null; fullPlayer = false }
    private val loadingComments = HashSet<String>()
    fun olderCommentary(match: Match) {
        val current = details.value[match.key]?.nativeData ?: return
        val cursor = current.soccer?.nextCursor ?: return
        val game = current.providerStamp?.takeIf { it.provider in setOf("Premier League", "MLS") }?.gameId ?: return
        if (!loadingComments.add(match.key)) return
        viewModelScope.launch {
            try {
                val (comments, next) = withContext(Dispatchers.IO) { nativeCentres.olderSoccerComments(match, cursor, game) }
                details.update { snapshots ->
                    val snapshot = snapshots[match.key] ?: return@update snapshots
                    val native = snapshot.nativeData ?: return@update snapshots
                    val soccer = native.soccer ?: return@update snapshots
                    if (native.providerStamp?.gameId != game) return@update snapshots
                    val merged = (soccer.commentary + comments).groupBy { it.id }.values.map { versions -> versions.reduce { old, incoming -> if (incoming.version >= old.version) incoming else old } }
                    snapshots + (match.key to snapshot.copy(nativeData = native.copy(soccer = soccer.copy(commentary = merged.sortedByDescending { it.timestamp ?: 0 }.take(500), nextCursor = next?.takeUnless { it == cursor || soccer.commentaryPages >= 9 }, commentaryPages = (soccer.commentaryPages + 1).coerceAtMost(10)))))
                }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { message = "Older commentary couldn't load. The current commentary is unchanged." }
            finally { loadingComments.remove(match.key) }
        }
    }
    fun adjacentChannel(direction: Int): Channel? {
        val lineup = container.lineup.lineup.value
        val list = lineup.entries.map { it.pick(container.lineup.prefs.value).channel }
            .ifEmpty { container.playlists.state.value.channels }
        val entry = channel?.let { lineup.entryFor(it.key) }
        val index = if (entry != null) lineup.entries.indexOfFirst { it.key == entry.key } else list.indexOfFirst { it.key == channel?.key }
        return if (index >= 0) list.getOrNull(index + direction) else null
    }
    fun adjacent(direction: Int) { adjacentChannel(direction)?.let { play(it) } }
    fun refresh(force: Boolean = true) { viewModelScope.launch { container.matchFeed.load(force) } }
    fun profile(): BufferProfile = BufferProfile.entries.firstOrNull { it.name == container.preferences.current.playerBufferProfile } ?: BufferProfile.Balanced

    fun openLink(uri: Uri?) {
        if (uri?.scheme != "banner") return
        when (uri.host) {
            "home" -> selectTab(PhoneTab.Home)
            "following" -> selectTab(PhoneTab.Following)
            "live" -> selectTab(PhoneTab.Live)
            "discover" -> selectTab(PhoneTab.Discover)
            "settings" -> selectTab(PhoneTab.Settings)
            "podcasts" -> open(PhonePage.Podcasts)
            "audio" -> open(PhonePage.AudioPlayer)
            "fantasy" -> open(PhonePage.Fantasy)
            "backup" -> open(PhonePage.CloudBackup)
            "channel-groups" -> open(PhonePage.ChannelGroups)
            "stats" -> open(PhonePage.Stats(League.find(uri.getQueryParameter("league"))?.path))
            "league" -> League.find(uri.getQueryParameter("league"))?.let { open(PhonePage.LeagueHub(it)) }
            "golf" -> uri.getQueryParameter("id")?.takeIf { it.matches(Regex("R\\d{7}")) }?.let { open(PhonePage.Golf(it)) }
            "f1" -> uri.getQueryParameter("id")?.let { id -> viewModelScope.launch { try { container.f1.session(id)?.let { open(PhonePage.Game(container.f1.match(it))) } ?: run { message = "That F1 session isn't in the official calendar." } } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { message = "The F1 calendar couldn't load." } } }
            "game" -> {
                val id = uri.getQueryParameter("id") ?: return
                val league = League.find(uri.getQueryParameter("league")) ?: return
                viewModelScope.launch {
                    val date = uri.getQueryParameter("date")?.toLongOrNull()?.takeIf { it in 0..4_102_444_800_000 }
                    val match = container.matchFeed.sections.value.all.firstOrNull { it.id == id && it.league == league }
                        ?: try {
                            withContext(Dispatchers.IO) {
                                if (league.path == "football/nfl" && id.startsWith("nfl:")) container.nfl.calendarGame(id, date)
                                else if (league.path == "hockey/nhl" && id.startsWith("nhl:")) container.nhl.calendarGame(id)
                                else if (league.path == "baseball/mlb" && id.startsWith("mlb:")) container.mlb.calendarGame(id)
                                else if (league.path == "basketball/nba" && id.startsWith("nba:")) container.nba.calendarGame(id, date)
                                else if (league.path == "basketball/wnba" && id.startsWith("wnba:")) container.wnba.calendarGame(id, date)
                                else if (league.path == "soccer/eng.1" && id.startsWith("epl:")) container.epl.calendarGame(id)
                                else if (league.path == "soccer/usa.1" && id.startsWith("mls:")) container.mls.calendarGame(id)
                                else if (league.path == "soccer/esp.1" && id.startsWith("laliga:")) container.laLiga.calendarGame(id, date)
                                else container.sports.scoreboard(league, date).firstOrNull { it.id == id }
                                    ?: if (league.path in setOf("hockey/nhl", "baseball/mlb", "football/nfl", "basketball/nba", "basketball/wnba", "soccer/eng.1", "soccer/usa.1", "soccer/esp.1") && id.matches(Regex("[0-9]{1,9}"))) container.espn.scoreboard(league, date).firstOrNull { it.id == id } else null
                            }
                        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                    if (match != null) open(PhonePage.Game(match)) else message = "This game is no longer in the current schedule."
                }
            }
        }
    }

    override fun onCleared() { entitlements.close(); controller.release(); if (audioConnection.isInitialized()) audioConnection.value.release(); super.onCleared() }
}

data class PhoneReplay(val programme: Programme, val url: String)
data class PhoneGameSnapshot(val data: GameCentre = GameCentre.EMPTY, val checkedAt: Long = 0, val refreshing: Boolean = false, val error: String? = null, val nativeData: GameCentre? = null, val nativeAttemptAt: Long = 0,
    val nativeRefreshing: Boolean = false, val nativeFailureCount: Int = 0,
    val baseballContentAt: Long = 0, val baseballProbabilityAt: Long = 0, val baseballExtraErrors: Map<String, String> = emptyMap(),
    val soccerTable: SoccerTable? = null, val soccerTableAttemptAt: Long = 0, val soccerTableLoading: Boolean = false, val soccerTableError: String? = null)
data class PhoneSearchDirectory(val teams: List<Team> = emptyList(), val players: List<Pair<League, Athlete>> = emptyList(), val loading: Boolean = false)
