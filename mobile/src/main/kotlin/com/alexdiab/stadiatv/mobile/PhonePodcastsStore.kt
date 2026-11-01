package com.alexdiab.stadiatv.mobile

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.alexdiab.stadiatv.StadiaTvApplication
import com.alexdiab.stadiatv.data.FavoriteTeam
import com.alexdiab.stadiatv.data.news.TeamPodcastCandidate
import com.alexdiab.stadiatv.data.news.TeamPodcastResolver
import com.alexdiab.stadiatv.data.news.TeamPodcastSeed
import com.alexdiab.stadiatv.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PodcastShow(val feed: String, val title: String, val publisher: String = "", val image: String? = null, val sport: String = "", val tags: List<String> = emptyList(), val language: String = "en")
@Serializable
data class PhoneEpisode(val id: String, val feed: String, val show: String, val title: String, val description: String, val media: String, val image: String? = null, val duration: Long = 0, val published: Long? = null, val mime: String = "audio/mpeg")
@Serializable
data class PodcastDownload(val request: Long, val fileName: String, val episode: PhoneEpisode, val complete: Boolean = false, val error: Boolean = false)
@Serializable
data class PodcastLibrary(val subscriptions: List<PodcastShow> = emptyList(), val queue: List<PhoneEpisode> = emptyList(), val history: List<PhoneEpisode> = emptyList(), val bookmarks: Map<String, Long> = emptyMap(), val downloads: List<PodcastDownload> = emptyList(), val automaticallyAdvance: Boolean = true, val wifiDownloadsOnly: Boolean = true, val speed: Float = 1f, val importedBookmarks: Map<String, Long> = emptyMap())

class PhonePodcastsStore private constructor(private val context: Context) {
    private val http = (context.applicationContext as StadiaTvApplication).container.http
    private val prefs = context.getSharedPreferences("podcast-library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(runCatching { json.decodeFromString<PodcastLibrary>(prefs.getString("state", null) ?: "{}") }.getOrDefault(PodcastLibrary()))
    val state = mutable.asStateFlow()
    private val catalogState = MutableStateFlow<List<PodcastShow>>(emptyList())
    val catalog = catalogState.asStateFlow()
    private val catalogLock = Mutex()
    private var teamSeeds: List<TeamPodcastSeed> = emptyList()
    private val teamResolver = TeamPodcastResolver(http)
    private val known = ConcurrentHashMap<String, PhoneEpisode>()
    private val cache = ConcurrentHashMap<String, List<PhoneEpisode>>()
    private val downloads = context.getSystemService(DownloadManager::class.java)

    @Synchronized fun update(transform: (PodcastLibrary) -> PodcastLibrary) {
        mutable.value = transform(mutable.value)
        prefs.edit().putString("state", json.encodeToString(mutable.value)).apply()
    }

    suspend fun ensureCatalog(): List<PodcastShow> = catalogLock.withLock {
        if (catalogState.value.isNotEmpty()) return@withLock catalogState.value
        val shows = withContext(Dispatchers.IO) { listOf("sports_podcast_rss_catalog_v2.json", "sports_db_catalog.json").flatMap { name ->
            context.assets.open(name).bufferedReader().use { parseJson(it.readText()) }["feeds"].array.mapNotNull { raw ->
                val feed = raw["feed_url"].stringOrNull()?.takeIf(::safeWebUrl) ?: return@mapNotNull null
                PodcastShow(feed, raw["title"].string("Podcast"), raw["author"].string(), raw["image_url"].stringOrNull(), raw["sport"].string(), raw["tags"].array.map { it.string() }, raw["language"].string("en"))
            }
        }.distinctBy { it.feed } }
        teamSeeds = withContext(Dispatchers.IO) {
            context.assets.open("sports_team_podcast_coverage.json").bufferedReader().use { TeamPodcastResolver.seeds(parseJson(it.readText())) }
        }
        catalogState.value = shows
        shows
    }

    suspend fun teamPodcasts(team: FavoriteTeam, refresh: Boolean = false): com.alexdiab.stadiatv.data.news.TeamPodcastResult {
        val catalog = ensureCatalog()
        return teamResolver.resolve(team, teamSeeds, catalog.map { TeamPodcastCandidate(it.feed, it.title, it.publisher, it.image, it.sport, it.tags) }, refresh)
    }

    suspend fun search(query: String): List<PodcastShow> {
        val catalog = ensureCatalog(); val q = query.trim()
        val local = catalog.filter { searchRank(q, it.title, "${it.publisher} ${it.sport} ${it.tags.joinToString(" ")}") > 0 }
        if (q.length < 2) return local.take(50)
        val remote = try {
            val encoded = java.net.URLEncoder.encode(q, "UTF-8")
            http.getJson("https://itunes.apple.com/search?term=$encoded&media=podcast&entity=podcast&limit=40&country=US", ttlMillis = 3_600_000, retries = 0)["results"].array.mapNotNull { row ->
                val feed = row["feedUrl"].stringOrNull()?.takeIf(::safeWebUrl) ?: return@mapNotNull null
                PodcastShow(feed, row["collectionName"].string("Podcast"), row["artistName"].string(), row["artworkUrl600"].stringOrNull())
            }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
        return (local + remote).distinctBy { it.feed }.sortedByDescending { searchRank(q, it.title, it.publisher) }.take(60)
    }

    suspend fun episodes(show: PodcastShow, refresh: Boolean = false): List<PhoneEpisode> = withContext(Dispatchers.IO) {
        if (!refresh) cache[show.feed]?.let { return@withContext it }
        require(safeWebUrl(show.feed))
        val xml = http.getString(show.feed, headers = mapOf("Accept" to "application/rss+xml, application/atom+xml, application/xml", "User-Agent" to "BannerTV/Android"), ttlMillis = if (refresh) 0 else 300_000, retries = 0)
        val result = parseEpisodes(xml, show)
        result.forEach { known[it.id] = it }
        cache[show.feed] = result
        if (cache.size > 8) cache.keys.firstOrNull { it != show.feed }?.let { cache.remove(it) }
        if (known.size > 1500) { val keep = (cache.values.flatten() + state.value.queue + state.value.history + state.value.downloads.map { it.episode }).map { it.id }.toSet(); known.keys.removeAll { it !in keep } }
        result
    }

    fun episode(id: String): PhoneEpisode? = known[id] ?: (state.value.queue + state.value.history + state.value.downloads.map { it.episode }).firstOrNull { it.id == id }
    fun subscribe(show: PodcastShow) = update { it.copy(subscriptions = if (it.subscriptions.any { p -> p.feed == show.feed }) it.subscriptions.filterNot { p -> p.feed == show.feed } else listOf(show) + it.subscriptions) }
    fun enqueue(episode: PhoneEpisode) = update { it.copy(queue = (it.queue + episode).distinctBy { e -> e.id }.take(200)) }
    fun select(episode: PhoneEpisode) = update { it.copy(queue = if (it.queue.any { e -> e.id == episode.id }) it.queue else listOf(episode), history = (listOf(episode) + it.history.filterNot { e -> e.id == episode.id }).take(50)) }
    fun bookmark(id: String, position: Long) = update { it.copy(bookmarks = (it.bookmarks + (id to position.coerceAtLeast(0))).entries.toList().takeLast(400).associate { e -> e.toPair() }) }
    fun bookmarkPosition(id: String): Long = state.value.bookmarks[id] ?: state.value.importedBookmarks[PhoneCloudBackup.episodeHash(id)] ?: 0
    fun cachedEpisodes(feed: String) = cache[feed].orEmpty()

    fun download(episode: PhoneEpisode) {
        if (state.value.downloads.any { it.episode.id == episode.id }) return
        require(safeWebUrl(episode.media) && !episode.media.substringBefore('?').endsWith(".m3u8", true)) { "This episode needs streaming; its enclosure is not a downloadable media file." }
        val name = hash(episode.id) + if (episode.mime.startsWith("video")) ".mp4" else ".audio"
        val request = DownloadManager.Request(Uri.parse(episode.media)).setTitle(episode.title).setDescription(episode.show).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, name).setAllowedOverMetered(!state.value.wifiDownloadsOnly)
        if (state.value.wifiDownloadsOnly) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val id = downloads.enqueue(request)
        update { it.copy(downloads = it.downloads + PodcastDownload(id, name, episode)) }
    }

    fun refreshDownloads() {
        val current = state.value.downloads
        val changed = current.map { item ->
            runCatching { downloads.query(DownloadManager.Query().setFilterById(item.request)).use { cursor -> if (cursor.moveToFirst()) { val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); item.copy(complete = status == DownloadManager.STATUS_SUCCESSFUL && downloadFile(item).isFile, error = status == DownloadManager.STATUS_FAILED) } else item.copy(error = true) } }.getOrDefault(item)
        }
        if (changed != current) update { it.copy(downloads = changed) }
    }
    private fun downloadFile(item: PodcastDownload): File = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), item.fileName)
    fun mediaUri(episode: PhoneEpisode): Uri = state.value.downloads.firstOrNull { it.episode.id == episode.id && it.complete && downloadFile(it).isFile }?.let { Uri.fromFile(downloadFile(it)) } ?: Uri.parse(episode.media)
    fun removeDownload(id: String) { val item = state.value.downloads.firstOrNull { it.episode.id == id } ?: return; downloads.remove(item.request); downloadFile(item).delete(); update { it.copy(downloads = it.downloads.filterNot { d -> d.episode.id == id }) } }

    companion object {
        @Volatile private var instance: PhonePodcastsStore? = null
        fun get(context: Context): PhonePodcastsStore = instance ?: synchronized(this) { instance ?: PhonePodcastsStore(context.applicationContext).also { instance = it } }
        fun safeWebUrl(value: String): Boolean = runCatching { val uri = java.net.URI(value); uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() }.getOrDefault(false)
        private fun hash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        fun duration(value: String): Long { val parts = value.trim().split(':'); if (parts.size !in 1..3) return 0; val numbers = parts.map { it.toLongOrNull() ?: return 0 }; if (numbers.any { it < 0 }) return 0; return numbers.fold(0L) { total, n -> (total * 60 + n).coerceAtMost(7 * 24 * 3600L) } }
        private fun published(value: String): Long? {
            com.alexdiab.stadiatv.util.Dates.parseIso(value)?.let { return it }
            for (format in listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz")) runCatching { SimpleDateFormat(format, Locale.US).parse(value)?.time }.getOrNull()?.let { return it }
            return null
        }
        fun parseEpisodes(xml: String, show: PodcastShow): List<PhoneEpisode> {
            val document = Jsoup.parse(xml.take(8_000_000), "", Parser.xmlParser())
            return document.select("item,entry").mapNotNull { row ->
                val enclosure = row.selectFirst("enclosure[url]") ?: row.selectFirst("link[rel=enclosure]") ?: return@mapNotNull null
                val url = enclosure.attr("url").ifBlank { enclosure.attr("href") }.takeIf(::safeWebUrl) ?: return@mapNotNull null
                val mime = enclosure.attr("type").ifBlank { "audio/mpeg" }
                if (!mime.startsWith("audio") && !mime.startsWith("video") && mime != "application/octet-stream") return@mapNotNull null
                fun tag(name: String) = row.getElementsByTag(name).firstOrNull()?.text().orEmpty()
                val guid = tag("guid").ifBlank { tag("id").ifBlank { url } }
                val image = row.getElementsByTag("itunes:image").firstOrNull()?.attr("href")?.takeIf(::safeWebUrl) ?: show.image
                PhoneEpisode("${show.feed}|$guid", show.feed, show.title, tag("title").ifBlank { "Untitled episode" }, Jsoup.parse(tag("description").ifBlank { tag("itunes:summary").ifBlank { tag("content") } }).text(), url, image, duration(tag("itunes:duration")), published(tag("pubDate").ifBlank { tag("published") }), mime)
            }.distinctBy { it.id }.sortedByDescending { it.published ?: 0L }.take(200)
        }
    }
}
