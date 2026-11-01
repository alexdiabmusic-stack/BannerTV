package com.alexdiab.stadiatv.live

import com.alexdiab.stadiatv.net.HttpGateway
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Provider-owned XMLTV download cache. URLs (which can contain credentials) never become file names. */
internal class GuideSourceFiles(
    private val directory: File,
    private val client: OkHttpClient,
    private val maxBytes: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun read(url: String, force: Boolean = false, onSavedFallback: () -> Unit = {}, isActive: () -> Boolean = { true }): File {
        fun checkActive() { if (!isActive()) throw CancellationException("Guide refresh superseded") }
        checkActive()
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.xml")
        val ttl = if (url.contains("SPORTS")) 4 * 3_600_000L else 6 * 3_600_000L
        val age = now() - file.lastModified()
        if (!force && file.isFile && age in 0 until ttl) return file
        val partial = File(directory, "$key.part")
        try {
            // Keep compressed bytes on disk; XmltvParser detects gzip from its header, not the extension.
            val request = Request.Builder().url(url).header("User-Agent", HttpGateway.USER_AGENT)
                .header("Accept-Encoding", "gzip")
                .apply { if (force) cacheControl(CacheControl.FORCE_NETWORK) }.build()
            client.newCall(request).execute().use { response ->
                checkActive()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                if (response.body.contentLength() > maxBytes) throw IOException("TV guide source is too large for this device.")
                var written = 0L
                response.body.byteStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > maxBytes) throw IOException("TV guide source is too large for this device.")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            checkActive()
            // Replacement keeps the previous complete file until the new download has closed successfully.
            if (!partial.renameTo(file)) throw IOException("Couldn't save the TV guide.")
            file.setLastModified(now())
            return file
        } catch (cancelled: CancellationException) {
            partial.delete()
            throw cancelled
        } catch (failure: Exception) {
            partial.delete()
            if (file.isFile) { onSavedFallback(); return file } // Do not mislabel retained bytes as a fresh download.
            throw failure
        }
    }
}
