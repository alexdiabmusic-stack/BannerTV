package com.alexdiab.stadiatv.live

import android.content.Context
import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.lineup.GuideDirectory
import com.alexdiab.stadiatv.lineup.GuideListing
import com.alexdiab.stadiatv.lineup.GuideRef
import com.alexdiab.stadiatv.lineup.Identity
import com.alexdiab.stadiatv.lineup.Lineup
import com.alexdiab.stadiatv.lineup.LineupBuilder
import com.alexdiab.stadiatv.lineup.LineupPrefs
import com.alexdiab.stadiatv.lineup.ResolvedStream
import com.alexdiab.stadiatv.lineup.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@Immutable
data class GuideState(
    val revision: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val programmeCount: Int = 0,
    val channelsWithGuide: Int = 0,
    /** What a running refresh is doing, for the settings row. */
    val progress: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * The TV guide: the schedules of every guide channel the lineup uses, and the directory of what each guide source lists
 * (so a stream can be matched to its schedule by what it is, not only by the id the provider gave it).
 *
 * The only sources are the playlist's own XMLTV URLs. Mixing in a public guide can silently put another station's
 * schedule on a provider stream, which makes both the grid and sports-event matching untrustworthy. A provider's guide
 * can be hundreds of megabytes, so it is streamed from disk, filtered while it is read to the channels the lineup could
 * use, and stored as compact arrays; the result is cached so the next launch has a guide instantly.
 */
class GuideStore(
    context: Context,
    http: OkHttpClient,
    private val scope: CoroutineScope,
    private val builder: LineupBuilder = LineupBuilder(),
) {
    private val cacheDir = File(context.cacheDir, "xmltv").apply { mkdirs() }
    private val cacheFile = File(context.filesDir, "guide-cache.bin")
    private val client = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private var job: Job? = null
    private var builtSignature: String? = null
    private var builtAt = 0L

    @Volatile private var snapshot = GuideSnapshot.EMPTY
    @Volatile private var refs: Map<String, GuideRef> = emptyMap()
    @Volatile private var sporty: Set<String> = emptySet()

    private val _state = MutableStateFlow(GuideState())
    val state: StateFlow<GuideState> = _state.asStateFlow()

    private val _directory = MutableStateFlow<GuideDirectory?>(null)
    /** What the guide sources list; null until a cache or a first load provides it. */
    val directory: StateFlow<GuideDirectory?> = _directory.asStateFlow()

    private val identities = HashMap<String, Identity>()

    /** The guide is read on a thread that yields to the screen: parsing a huge file must never make the UI stutter. */
    private val loaderDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "guide-loader")
    }.asCoroutineDispatcher()

    init {
        scope.launch(Dispatchers.IO) {
            // Bootstrap and refresh both publish snapshot/directory/identity state. A slow old
            // cache read must never overwrite a newer refresh or mutate the identity map in parallel.
            lock.withLock { if (builtAt == 0L) loadCache() }
        }
    }

    // region reading

    /** Tells the guide which schedule belongs to which stream, from the lineup built over the current directory. */
    fun use(lineup: Lineup) {
        refs = lineup.guideByChannelKey
        _state.update { it.copy(channelsWithGuide = lineup.entries.count { e -> !e.isFast && e.hasGuide }) }
    }

    fun refFor(channel: Channel): GuideRef? = refs[channel.key]

    fun guideIdFor(channel: Channel): String? = refs[channel.key]?.id ?: channel.tvgId

    fun programmesFor(channel: Channel): List<Programme> {
        val ref = refs[channel.key]
        return if (ref != null) programmesOf(ref) else channel.tvgId?.let { snapshot.programmes(it) }.orEmpty()
    }

    fun programmesOf(ref: GuideRef): List<Programme> = snapshot.programmes(ref.id, ref.offsetMinutes)

    fun nowAndNext(channel: Channel, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val ref = refs[channel.key]
        val snap = snapshot
        return if (ref != null) snap.nowAndNext(ref.id, now, ref.offsetMinutes)
        else channel.tvgId?.let { snap.nowAndNext(it, now) } ?: (null to null)
    }

    fun nowAndNext(ref: GuideRef, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> =
        snapshot.nowAndNext(ref.id, now, ref.offsetMinutes)

    /**
     * What the stream linker looks at: programmes of the sports channels over the next two days (and the last few
     * hours). Everything else in the guide can never name a fixture, and is not worth turning into objects.
     */
    fun linkerProgrammes(now: Long = System.currentTimeMillis()): List<Programme> {
        val snap = snapshot
        val from = (now - 3 * 3600_000L) / 60_000L
        val to = (now + 48 * 3600_000L) / 60_000L
        val out = ArrayList<Programme>()
        for (id in sporty) {
            val schedule = snap.schedules[id] ?: continue
            for (i in schedule.firstStartingAfter(from - 1) until schedule.size) {
                if (schedule.starts[i] > to) break
                out += snap.programme(id, schedule, i)
            }
        }
        return out
    }

    // endregion

    // region refreshing

    /**
     * Rebuilds the guide when the lineup or sources changed or the cache has aged. Cheap to call often.
     * @param countries the countries whose channels the guide should hold (programmes for other countries are skipped).
     */
    fun refresh(playlists: List<Playlist>, streams: List<ResolvedStream>, countries: Set<String>, force: Boolean = false) {
        if (streams.isEmpty()) return
        val signature = signature(playlists, streams, countries)
        if (!force && signature == builtSignature && System.currentTimeMillis() - builtAt < REFRESH_AGE_MILLIS) return
        job?.cancel()
        job = scope.launch(loaderDispatcher) {
            lock.withLock {
                if (!force && signature == builtSignature && System.currentTimeMillis() - builtAt < REFRESH_AGE_MILLIS) return@withLock
                _state.update { it.copy(isLoading = true, error = null, progress = "Starting…") }
                try {
                    val requestJob = currentCoroutineContext()[Job]!!
                    var savedSource = false
                    val built = build(playlists, streams, countries, force, isActive = { requestJob.isActive }, onSavedFallback = { savedSource = true })
                    currentCoroutineContext().ensureActive() // A cancelled parse must not publish partial data.
                    if (built.sourcesLoaded == 0 && signature == builtSignature && snapshot.programmeCount > 0) {
                        _state.update { it.copy(isLoading = false, progress = null, error = "Provider guide couldn't refresh. Keeping the saved listings.") }
                        return@withLock
                    }
                    snapshot = built.snapshot
                    sporty = built.sporty
                    builtSignature = signature
                    builtAt = System.currentTimeMillis()
                    _directory.value = built.directory
                    _state.update {
                        GuideState(
                            revision = it.revision + 1,
                            isLoading = false,
                            error = if (built.sourcesLoaded == 0) "No TV guide source could be loaded." else if (savedSource) "Using a saved provider guide because the download couldn't refresh." else null,
                            programmeCount = built.snapshot.programmeCount,
                            channelsWithGuide = it.channelsWithGuide,
                            updatedAt = builtAt,
                        )
                    }
                    runCatching {
                        GuideCache.write(cacheFile, GuideCacheContent(builtAt, signature, built.listings, built.sporty, built.snapshot))
                    }.onFailure {
                        _state.update { state -> state.copy(error = state.error ?: "The guide is available, but its offline copy couldn't be saved.") }
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    _state.update { it.copy(isLoading = false, progress = null, error = error.message ?: "Couldn't load the TV guide.") }
                }
            }
        }
    }

    private fun loadCache() {
        val cached = com.alexdiab.stadiatv.util.PerfLog.measure("guide cache read") { GuideCache.read(cacheFile) } ?: return
        snapshot = cached.snapshot
        sporty = cached.sporty
        builtSignature = cached.signature
        builtAt = cached.createdAt
        _directory.value = com.alexdiab.stadiatv.util.PerfLog.measure("guide directory from cache") { GuideDirectory.build(builder.guideMatcher, cached.listings, identities) }
        _state.update {
            it.copy(revision = it.revision + 1, programmeCount = cached.snapshot.programmeCount, updatedAt = cached.createdAt)
        }
    }

    private fun build(playlists: List<Playlist>, streams: List<ResolvedStream>, countries: Set<String>, forceDownload: Boolean, isActive: () -> Boolean, onSavedFallback: () -> Unit): GuideLoader.Result {
        val own = LinkedHashSet<String>()
        playlists.forEach { playlist -> playlist.epgUrls.filter { it.startsWith("http") }.forEach(own::add) }
        val sources = own.map { GuideLoader.Source(it) { sourceFiles.read(it, forceDownload, onSavedFallback, isActive).inputStream() } }
        return GuideLoader(builder, identities).load(
            sources, streams, countries, System.currentTimeMillis(),
            isActive = isActive,
            onProgress = { text -> _state.update { it.copy(progress = text) } },
        )
    }

    // endregion

    // region downloading

    private val sourceFiles = GuideSourceFiles(cacheDir, client, MAX_SOURCE_BYTES)

    // endregion

    private fun signature(playlists: List<Playlist>, streams: List<ResolvedStream>, countries: Set<String>): String {
        var hash = streams.size
        for (stream in streams) hash = 31 * hash + (stream.channel.tvgId?.hashCode() ?: 0) + stream.channel.name.hashCode() + (stream.channel.catchup?.hashCode() ?: 0)
        return "$hash|${countries.sorted().joinToString(",")}|${playlists.joinToString(",") { it.epgUrls.joinToString("+") }}"
    }

    private companion object {
        const val REFRESH_AGE_MILLIS = 5 * 3600_000L
        /** Compressed bytes on disk. Provider guides are commonly around 45 MB but can be substantially larger. */
        const val MAX_SOURCE_BYTES = 140L * 1024 * 1024
    }
}
