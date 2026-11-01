package com.alexdiab.stadiatv.catalog

import com.alexdiab.stadiatv.addons.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class MediaSource(val provider: String, val stream: Stream)
data class MediaSources(val sources: List<MediaSource>, val subtitles: List<Subtitle>, val enabledProviders: Int, val failedProviders: Int)

/** A metadata identity is resolved only through enabled user-configured add-ons. */
class MediaPlaybackResolver(private val library: AddonLibrary) {
    @Throws(Exception::class)
    suspend fun resolve(detail: MediaDetail, video: MediaVideo): MediaSources = supervisorScope {
        require(video.id == detail.card.id && video.valid)
        val id = detail.addonVideoId(video.season,video.episode) ?: return@supervisorScope MediaSources(emptyList(),emptyList(),0,0)
        val type = if (video.id.kind == MediaKind.Movie) "movie" else "series"
        val addons = library.list().filter { it.enabled }
        val gate = Semaphore(4)
        data class Result(val sources: List<MediaSource> = emptyList(), val subtitles: List<Subtitle> = emptyList(), val failed: Boolean = false)
        val completed = mutableMapOf<Int,Result>()
        val lock = Mutex()
        // The overall deadline includes queued providers. A slow subtitle response cannot discard a working stream.
        withTimeoutOrNull(25_000) {
        addons.mapIndexed { index, addon -> launch { gate.withPermit {
            val result = try {
                    val c = library.connection(addon.key)
                    var failed = false
                    val streams = try { if (c.manifest.supports("stream",type,id))
                        withTimeoutOrNull(12_000) { library.client.streams(c.address,c.manifest,type,id) } ?: run { failed=true; emptyList() } else emptyList() }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { failed=true; emptyList() }
                    val captions = try { if (c.manifest.supports("subtitles",type,id))
                        withTimeoutOrNull(4_000) { library.client.subtitles(c.address,c.manifest,type,id) } ?: run { failed=true; emptyList() } else emptyList() }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { failed=true; emptyList() }
                    Result(streams.take(50).map { MediaSource(addon.name,it) },captions.take(50),failed)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { Result(failed=true) }
            lock.withLock { completed[index]=result }
        } } }.joinAll()
        }
        val results=lock.withLock { addons.indices.map { completed[it] ?: Result(failed=true) } }
        MediaSources(results.flatMap { it.sources }.take(150),results.flatMap { it.subtitles }.distinctBy { it.url }.take(100),addons.size,results.count { it.failed })
    }
}
