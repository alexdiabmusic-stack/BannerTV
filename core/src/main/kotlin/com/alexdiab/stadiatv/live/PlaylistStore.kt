package com.alexdiab.stadiatv.live

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.alexdiab.stadiatv.net.AppJson
import com.alexdiab.stadiatv.net.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Immutable
data class PlaylistsState(
    val playlists: List<Playlist> = emptyList(),
    val defaultId: String? = null,
    /** Every channel, default playlist first. */
    val channels: List<Channel> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val errors: Map<String, String> = emptyMap(),
    /** Bumps whenever the lineup changes, so dependants (the stream linker) know to rebuild. */
    val revision: Int = 0,
    val hasLoadedCache: Boolean = false,
) {
    val isEmpty: Boolean get() = playlists.isEmpty()
    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }
}

/**
 * Owns playlists and the channels loaded from them.
 *
 * - Playlist JSON lives in SharedPreferences; Xtream credentials are Keystore-encrypted separately.
 * - Every download is streamed to a cache file and parsed from disk, so a 30,000-channel lineup is never
 *   held twice in memory, and launches show channels instantly from the cache while a refresh runs.
 * - Refreshes are serialised: two big lineups never peak in memory together on a Fire TV.
 */
class PlaylistStore(
    private val context: Context,
    http: OkHttpClient,
    private val credentials: CredentialStore,
    private val scope: CoroutineScope,
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "playlists").apply { mkdirs() }
    private val client: OkHttpClient = http.newBuilder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val refreshLock = Mutex()
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val byPlaylist = LinkedHashMap<String, List<Channel>>()

    private val _state = MutableStateFlow(PlaylistsState())
    val state: StateFlow<PlaylistsState> = _state.asStateFlow()

    init {
        val saved = loadPlaylists()
        _state.value = PlaylistsState(playlists = saved, defaultId = prefs.getString(KEY_DEFAULT, null)?.takeIf { id -> saved.any { it.id == id } } ?: saved.firstOrNull()?.id)
        scope.launch(Dispatchers.IO) {
            // Cached lineups first (instant), then refresh whatever is stale.
            saved.forEach { playlist -> com.alexdiab.stadiatv.util.PerfLog.measure("playlist cache load") { loadFromCache(playlist) } }
            _state.update { it.copy(hasLoadedCache = true) }
            saved.forEach { playlist -> if (isStale(playlist)) refresh(playlist.id) }
        }
    }

    // region Mutations

    fun addM3u(name: String, url: String, userAgent: String = ""): String {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "My Playlist" },
            kind = PlaylistKind.M3U,
            m3uUrl = url.trim(),
            userAgent = userAgent.trim(),
        )
        register(playlist)
        return playlist.id
    }

    fun addXtream(name: String, host: String, username: String, password: String, userAgent: String = ""): String {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Stream Login" },
            kind = PlaylistKind.XTREAM,
            host = trimHost(host),
            userAgent = userAgent.trim(),
        )
        credentials.save(playlist.id, XtreamCredentials(username.trim(), password))
        register(playlist)
        return playlist.id
    }

    fun replaceM3u(id: String, name: String, url: String, userAgent: String = "") {
        val current = _state.value.playlist(id) ?: return
        update(current.copy(name = name.trim().ifEmpty { current.name }, kind = PlaylistKind.M3U, m3uUrl = url.trim(), host = "", epgUrls = emptyList(), userAgent = userAgent.trim()))
        credentials.remove(id)
        refresh(id)
    }

    fun replaceXtream(id: String, name: String, host: String, username: String, password: String, userAgent: String = "") {
        val current = _state.value.playlist(id) ?: return
        update(current.copy(name = name.trim().ifEmpty { current.name }, kind = PlaylistKind.XTREAM, host = trimHost(host), m3uUrl = "", epgUrls = emptyList(), userAgent = userAgent.trim()))
        credentials.save(id, XtreamCredentials(username.trim(), password))
        refresh(id)
    }

    fun remove(id: String) {
        inFlight.remove(id)?.cancel()
        credentials.remove(id)
        cacheFiles(id).forEach { it.delete() }
        synchronized(byPlaylist) { byPlaylist.remove(id) }
        val remaining = _state.value.playlists.filterNot { it.id == id }
        val newDefault = _state.value.defaultId.takeIf { it != id && remaining.any { p -> p.id == it } } ?: remaining.firstOrNull()?.id
        prefs.edit().apply { if (newDefault == null) remove(KEY_DEFAULT) else putString(KEY_DEFAULT, newDefault) }.apply()
        _state.update { it.copy(playlists = remaining, defaultId = newDefault) }
        persist()
        publish()
    }

    fun setDefault(id: String) {
        if (_state.value.playlist(id) == null) return
        prefs.edit().putString(KEY_DEFAULT, id).apply()
        _state.update { it.copy(defaultId = id) }
        publish()
    }

    fun credentials(id: String): XtreamCredentials? = credentials.load(id)

    // endregion

    // region Refresh

    fun refreshAll() {
        _state.value.playlists.forEach { refresh(it.id) }
    }

    /** Starts (or joins) a refresh. Safe to call repeatedly: duplicate calls coalesce. */
    fun refresh(id: String) {
        if (inFlight[id]?.isActive == true) return
        val playlist = _state.value.playlist(id) ?: return
        val job = scope.launch(Dispatchers.IO) {
            _state.update { it.copy(loading = it.loading + id, errors = it.errors - id) }
            try {
                refreshLock.withLock { download(playlist) }
                val updated = _state.value.playlist(id) ?: return@launch
                loadFromCache(updated)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                _state.update { it.copy(errors = it.errors + (id to "${playlist.name}: ${readable(error)}")) }
            } finally {
                _state.update { it.copy(loading = it.loading - id) }
            }
        }
        inFlight[id] = job
        job.invokeOnCompletion { inFlight.remove(id, job) }
    }

    /** Downloads and parses without saving, to validate what the user typed. Returns the channel count. */
    suspend fun validateM3u(url: String, userAgent: String = ""): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            validateUrl(url.trim())
            val playlist = Playlist("validate", "Validation", PlaylistKind.M3U, m3uUrl = url.trim(), userAgent = userAgent.trim())
            val file = File(cacheDir, "validate.m3u")
            try {
                fetch(playlist.m3uUrl, playlist.userAgent, file)
                val parsed = file.bufferedReader().use { M3uParser.parse(it, playlist) }
                check(parsed.channels.isNotEmpty()) { "No channels were found at that address." }
                parsed.channels.size
            } finally { file.delete() }
        }.recoverCatching { throw IllegalStateException(readable(it)) }
    }

    suspend fun validateXtream(host: String, username: String, password: String, userAgent: String = ""): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanHost = trimHost(host)
            validateUrl(cleanHost)
            val creds = XtreamCredentials(username.trim(), password)
            check(creds.username.isNotEmpty() && creds.password.isNotEmpty()) { "Enter both a username and password." }
            val playlist = Playlist("validate", "Validation", PlaylistKind.XTREAM, host = cleanHost, userAgent = userAgent.trim())
            val file = File(cacheDir, "validate.json")
            try {
                fetch(XtreamLoader.apiUrl(cleanHost, creds, "get_live_streams"), playlist.userAgent, file)
                val streams = XtreamLoader.parseStreams(file, playlist, creds, emptyMap())
                check(streams.isNotEmpty()) { "The provider returned no channels for these credentials." }
                streams.size
            } finally { file.delete() }
        }.recoverCatching { throw IllegalStateException(readable(it)) }
    }

    private fun download(playlist: Playlist) {
        when (playlist.kind) {
            PlaylistKind.M3U -> {
                validateUrl(playlist.m3uUrl)
                fetch(playlist.m3uUrl, playlist.userAgent, File(cacheDir, "${playlist.id}.m3u"))
            }
            PlaylistKind.XTREAM -> {
                validateUrl(playlist.host)
                val creds = credentials.load(playlist.id) ?: throw IllegalStateException("The saved login is missing. Re-enter it in Settings.")
                runCatching { fetch(XtreamLoader.apiUrl(playlist.host, creds, "get_live_categories"), playlist.userAgent, File(cacheDir, "${playlist.id}.categories.json")) }
                fetch(XtreamLoader.apiUrl(playlist.host, creds, "get_live_streams"), playlist.userAgent, File(cacheDir, "${playlist.id}.streams.json"))
            }
        }
    }

    /** Streams a URL to [target] atomically, so a failed download never destroys a good cached copy. */
    private fun fetch(url: String, userAgent: String, target: File) {
        val request = Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { com.alexdiab.stadiatv.net.HttpGateway.USER_AGENT })
            .build()
        val partial = File(target.parentFile, target.name + ".part")
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                response.body.byteStream().use { input -> partial.outputStream().buffered().use { output -> input.copyTo(output) } }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) throw IOException("Couldn't save the downloaded playlist.")
        } finally {
            partial.delete()
        }
    }

    private fun loadFromCache(playlist: Playlist) {
        val scopeActive = { scope.isActive }
        val parsed: Pair<List<Channel>, List<String>> = runCatching {
            when (playlist.kind) {
                PlaylistKind.M3U -> {
                    val file = File(cacheDir, "${playlist.id}.m3u")
                    if (!file.exists()) return@runCatching null
                    val result = file.bufferedReader().use { M3uParser.parse(it, playlist, scopeActive) }
                    result.channels to result.epgUrls
                }
                PlaylistKind.XTREAM -> {
                    val streams = File(cacheDir, "${playlist.id}.streams.json")
                    val creds = credentials.load(playlist.id)
                    if (!streams.exists() || creds == null) return@runCatching null
                    val categories = XtreamLoader.parseCategories(File(cacheDir, "${playlist.id}.categories.json"))
                    XtreamLoader.parseStreams(streams, playlist, creds, categories, scopeActive) to listOf(XtreamLoader.epgUrl(playlist.host, creds))
                }
            }
        }.getOrNull() ?: return
        val (channels, epg) = parsed
        synchronized(byPlaylist) { byPlaylist[playlist.id] = channels }
        // Remember the playlist's own guide URLs for the TV Guide.
        val current = _state.value.playlist(playlist.id)
        if (current != null && current.epgUrls != epg) update(current.copy(epgUrls = epg), persistOnly = true)
        publish()
    }

    private fun isStale(playlist: Playlist): Boolean {
        val file = when (playlist.kind) {
            PlaylistKind.M3U -> File(cacheDir, "${playlist.id}.m3u")
            PlaylistKind.XTREAM -> File(cacheDir, "${playlist.id}.streams.json")
        }
        return !file.exists() || System.currentTimeMillis() - file.lastModified() > CACHE_TTL_MILLIS
    }

    // endregion

    // region Persistence

    private fun register(playlist: Playlist) {
        _state.update {
            val playlists = it.playlists + playlist
            it.copy(playlists = playlists, defaultId = it.defaultId ?: playlist.id)
        }
        if (_state.value.defaultId == playlist.id) prefs.edit().putString(KEY_DEFAULT, playlist.id).apply()
        persist()
        refresh(playlist.id)
    }

    private fun update(playlist: Playlist, persistOnly: Boolean = false) {
        _state.update { state -> state.copy(playlists = state.playlists.map { if (it.id == playlist.id) playlist else it }) }
        persist()
        if (!persistOnly) publish()
    }

    private fun publish() {
        val state = _state.value
        val ordered = buildList {
            val snapshot = synchronized(byPlaylist) { LinkedHashMap(byPlaylist) }
            state.defaultId?.let { snapshot[it]?.let(::addAll) }
            state.playlists.forEach { if (it.id != state.defaultId) snapshot[it.id]?.let(::addAll) }
        }
        val counts = synchronized(byPlaylist) { byPlaylist.mapValues { it.value.size } }
        _state.update { it.copy(channels = ordered, counts = counts, revision = it.revision + 1) }
    }

    private fun persist() {
        prefs.edit().putString(KEY_PLAYLISTS, AppJson.encodeToString(_state.value.playlists)).apply()
    }

    private fun loadPlaylists(): List<Playlist> {
        prefs.getString(KEY_PLAYLISTS, null)?.let { stored ->
            runCatching { return AppJson.decodeFromString<List<Playlist>>(stored) }
        }
        return migrateLegacy()
    }

    /** Brings over playlists saved by the original Android build, including its encrypted Xtream logins. */
    private fun migrateLegacy(): List<Playlist> {
        val legacy = context.getSharedPreferences("stadiatv.playlists.v1", Context.MODE_PRIVATE)
        val raw = legacy.getString("playlists", null) ?: return emptyList()
        val migrated = runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val value = array.optJSONObject(index) ?: return@mapNotNull null
                val id = value.optString("id").ifEmpty { UUID.randomUUID().toString() }
                val kind = if (value.optString("kind") == "XTREAM") PlaylistKind.XTREAM else PlaylistKind.M3U
                val epg = value.optJSONArray("epgUrls")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                if (kind == PlaylistKind.XTREAM) {
                    value.optString("credentials").takeIf { it.isNotEmpty() }?.let { credentials.decryptLegacy(it) }?.let { credentials.save(id, it) }
                }
                Playlist(id, value.optString("name", "Playlist"), kind, value.optString("url"), value.optString("host"), epg)
            }
        }.getOrDefault(emptyList())
        legacy.getString("defaultPlaylist", null)?.let { prefs.edit().putString(KEY_DEFAULT, it).apply() }
        if (migrated.isNotEmpty()) prefs.edit().putString(KEY_PLAYLISTS, AppJson.encodeToString(migrated)).apply()
        return migrated
    }

    private fun cacheFiles(id: String) = listOf("$id.m3u", "$id.streams.json", "$id.categories.json").map { File(cacheDir, it) }

    // endregion

    private fun trimHost(value: String): String = value.trim().trimEnd('/')

    private fun validateUrl(value: String) {
        require(value.startsWith("http://") || value.startsWith("https://")) { "Addresses must begin with http:// or https://" }
    }

    private fun readable(error: Throwable): String = when (error) {
        is HttpStatusException -> when (error.code) {
            401, 403 -> "The provider rejected these credentials (HTTP ${error.code})."
            404 -> "That address wasn't found (HTTP 404)."
            in 500..599 -> "The provider is having trouble right now (HTTP ${error.code})."
            else -> "The provider returned HTTP ${error.code}."
        }
        is UnknownHostException -> "Couldn't reach that server. Check the address and your connection."
        is SocketTimeoutException -> "The provider took too long to respond."
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "Couldn't load this playlist."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Couldn't load this playlist."
    }

    private companion object {
        const val FILE = "stadiatv.playlists.v2"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_DEFAULT = "default"
        const val CACHE_TTL_MILLIS = 45 * 60_000L
    }
}
