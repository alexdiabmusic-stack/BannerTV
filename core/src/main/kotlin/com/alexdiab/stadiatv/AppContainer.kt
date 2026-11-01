package com.alexdiab.stadiatv

import android.app.Application
import com.alexdiab.stadiatv.core.BuildConfig
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.alexdiab.stadiatv.data.MatchFeed
import com.alexdiab.stadiatv.data.NewsRepository
import com.alexdiab.stadiatv.data.PreferencesStore
import com.alexdiab.stadiatv.data.fantasy.EspnFantasyApi
import com.alexdiab.stadiatv.data.fantasy.FantasyStore
import com.alexdiab.stadiatv.data.odds.OddsService
import com.alexdiab.stadiatv.net.HttpGateway
import com.alexdiab.stadiatv.playback.PlayerPreferences
import com.alexdiab.stadiatv.sports.EspnApi
import com.alexdiab.stadiatv.sports.FeaturedDemand
import com.alexdiab.stadiatv.sports.SportsRepository
import com.alexdiab.stadiatv.live.CredentialStore
import com.alexdiab.stadiatv.lineup.CatalogMatcher
import com.alexdiab.stadiatv.lineup.LineupBuilder
import com.alexdiab.stadiatv.lineup.LineupStore
import com.alexdiab.stadiatv.live.GuideStore
import com.alexdiab.stadiatv.live.guideInputsReady
import com.alexdiab.stadiatv.live.PlaylistStore
import com.alexdiab.stadiatv.live.StreamAvailabilityStore
import com.alexdiab.stadiatv.live.WatchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean
import okio.Path.Companion.toOkioPath

/**
 * Hand-rolled service locator. Everything the UI needs is created once, lazily where it is heavy,
 * and shared, so screens stay thin and a channel zap or tab change never rebuilds a repository.
 */
class AppContainer(private val application: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingGuideForce = AtomicBoolean(false)

    val http: HttpGateway by lazy { HttpGateway(HttpGateway.createClient(application)) }
    val preferences: PreferencesStore by lazy { PreferencesStore(application) }
    private val sharedSports by lazy { com.alexdiab.stadiatv.sports.SharedSports(
        http, com.alexdiab.stadiatv.foundation.SportsFile(application.cacheDir.path),
        com.alexdiab.stadiatv.sports.f1.androidF1Transport(http)) }
    val espn get() = sharedSports.espn
    val f1 get() = sharedSports.f1
    val pga get() = sharedSports.pga
    val nfl get() = sharedSports.nfl
    val nflProvider get() = sharedSports.nflProvider
    val hockeyDetails get() = sharedSports.hockeyDetails
    val nhl get() = sharedSports.nhl
    val baseballDetails get() = sharedSports.baseballDetails
    val mlb get() = sharedSports.mlb
    val baseballExtras get() = sharedSports.baseballExtras
    val nbaClient get() = sharedSports.nbaClient
    val wnbaClient get() = sharedSports.wnbaClient
    val basketballDetails get() = sharedSports.basketballDetails
    val nba get() = sharedSports.nba
    val wnba get() = sharedSports.wnba
    val eplClient get() = sharedSports.eplClient
    val soccerDetails get() = sharedSports.soccerDetails
    val epl get() = sharedSports.epl
    val mlsClient get() = sharedSports.mlsClient
    val mlsDetails get() = sharedSports.mlsDetails
    val mls get() = sharedSports.mls
    val laLigaClient get() = sharedSports.laLigaClient
    val fotMobClient get() = sharedSports.fotMobClient
    val laLiga get() = sharedSports.laLiga
    val laLigaDetails get() = sharedSports.laLigaDetails
    val sports get() = sharedSports.sports
    val news: NewsRepository by lazy { NewsRepository(espn, http, com.alexdiab.stadiatv.data.news.RssDecoder { com.alexdiab.stadiatv.data.news.RssParser.parse(it) }) }
    val odds: OddsService by lazy { OddsService(http, BuildConfig.ODDS_API_BASE_URL, BuildConfig.ODDS_API_KEY) }
    val fantasy: FantasyStore by lazy { FantasyStore(application, EspnFantasyApi(http), credentials, appScope) }
    val featuredDemand: FeaturedDemand by lazy { FeaturedDemand(application) }
    val matchFeed: MatchFeed by lazy { MatchFeed(sports, preferences, featuredDemand, appScope) }

    val credentials: CredentialStore by lazy { CredentialStore(application) }
    private val metadataHttp by lazy { HttpGateway(http.client.newBuilder().cache(null).cookieJar(okhttp3.CookieJar.NO_COOKIES).followRedirects(false).build()) }
    val metadataConnection by lazy { com.alexdiab.stadiatv.data.MetadataConnection(credentials.metadataVault(),metadataHttp,appScope) }
    val tmdb get() = metadataConnection.repository
    val mediaSources by lazy { com.alexdiab.stadiatv.catalog.MediaPlaybackResolver(addons) }
    val addons by lazy { com.alexdiab.stadiatv.addons.AddonLibrary(
        com.alexdiab.stadiatv.addons.StremioClient(com.alexdiab.stadiatv.net.AndroidAddonHttp()), credentials.addonVault()) }
    val playlists: PlaylistStore by lazy { PlaylistStore(application, http.client, credentials, appScope) }
    private val lineupBuilder: LineupBuilder by lazy { LineupBuilder(CatalogMatcher()) }
    val guide: GuideStore by lazy { GuideStore(application, http.client, appScope, lineupBuilder) }
    val lineup: LineupStore by lazy { LineupStore(playlists, guide, preferences, lineupBuilder, appScope) }
    val streams: StreamAvailabilityStore by lazy { StreamAvailabilityStore(appScope) }
    val watchHistory: WatchHistoryStore by lazy { WatchHistoryStore(application) }
    val playerPrefs: PlayerPreferences by lazy { PlayerPreferences(application) }

    private var started = false

    /**
     * Wires the long-lived pipelines together once: lineup -> guide -> stream linking. Each stage is debounced and
     * runs at low priority, so launch is spent on the sports feed the Home screen shows first.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        appScope.launch {
            combine(lineup.streams, lineup.countries, playlists.state) { streams, _, playlistState ->
                guideInputsReady(streams, playlistState)
            }.debounce(1_500).collect { ready ->
                if (ready) refreshGuide()
            }
        }
        appScope.launch {
            combine(
                matchFeed.sections.map { it.all }.distinctUntilChanged(),
                playlists.state.map { it.revision }.distinctUntilChanged(),
                lineup.lineup.map { it.revision }.distinctUntilChanged(),
                guide.state.map { it.revision }.distinctUntilChanged(),
            ) { matches, _, lineupRevision, guideRevision -> matches to (lineupRevision * 31 + guideRevision) }
                .debounce(800)
                .collect { (matches, revision) ->
                    streams.scan(matches, playlists.state.value.channels, guide.linkerProgrammes(), guide::guideIdFor, revision)
                }
        }
    }

    /** Reloads the TV guide for the current playlists; `force` ignores how recent the last load was. */
    fun refreshGuide(force: Boolean = false) {
        if (force) pendingGuideForce.set(true)
        val streams = lineup.streams.value
        val playlistState = playlists.state.value
        if (guideInputsReady(streams, playlistState)) {
            guide.refresh(playlistState.playlists, streams, lineup.countries.value, pendingGuideForce.getAndSet(false))
        }
    }

    /**
     * Fire TV sticks have 1-2 GB of RAM shared with the video decoder, so the image cache is small and
     * logos (the bulk of what we draw) are decoded at the size they are shown, never full resolution.
     */
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(application)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http.client })); add(SvgDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(IMAGE_MEMORY_BYTES).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(application.cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_BYTES)
                    .build()
            }
            .crossfade(220)
            .build()
    }

    fun trimMemory() {
        imageLoader.memoryCache?.clear()
        http.invalidate()
    }

    private companion object {
        const val IMAGE_MEMORY_BYTES = 28L * 1024 * 1024
        const val IMAGE_DISK_BYTES = 128L * 1024 * 1024
    }
}

class StadiaTvApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        container.trimMemory()
    }

    // The RUNNING_* levels are deprecated from API 34, but Fire OS 7 and 9 still deliver them, and they are
    // the only early warning a foreground app gets.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) container.imageLoader.memoryCache?.clear()
    }
}
