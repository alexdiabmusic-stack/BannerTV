package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.PlaybackUi
import java.net.URI
import java.util.Locale

enum class PhonePlaybackPhase { Connecting, Playing, Paused, Buffering, Reconnecting, Failed, Ended }

/** One presentation contract for the full and mini-player; no provider diagnostics reach UI copy. */
data class PhonePlaybackPresentation(
    val phase: PhonePlaybackPhase,
    val statusLabel: String,
    val failureTitle: String? = null,
    val failureMessage: String? = null,
    val isLiveEnded: Boolean = false,
) {
    val recoveryRequired: Boolean get() = phase == PhonePlaybackPhase.Failed || isLiveEnded
}

fun phonePlaybackPresentation(playback: PlaybackUi): PhonePlaybackPresentation {
    val errorMessage = playback.errorMessage
    if (errorMessage != null) {
        // Exact known controller messages only. Never echo an exception, endpoint, account or token.
        val (title, message) = when (errorMessage.trim()) {
            "This stream isn't available." -> "Stream unavailable" to "Your provider isn't serving this stream right now."
            "This stream isn't responding." -> "Stream not responding" to "The stream didn't respond after several connection attempts."
            else -> "Stream interrupted" to "Playback couldn't continue. Restart this stream or choose another channel."
        }
        return PhonePlaybackPresentation(PhonePlaybackPhase.Failed, title, title, message)
    }
    return when {
        playback.timeline.ended -> if (playback.isArchive) PhonePlaybackPresentation(PhonePlaybackPhase.Ended, "Replay finished")
            else PhonePlaybackPresentation(PhonePlaybackPhase.Ended, "Stream ended", "Stream ended",
                "Playback reached the end of this live stream. Restart to reconnect or choose another channel.", isLiveEnded = true)
        playback.isUserPaused -> PhonePlaybackPresentation(PhonePlaybackPhase.Paused, "Paused")
        playback.reconnectAttempt > 0 -> PhonePlaybackPresentation(PhonePlaybackPhase.Reconnecting, "Reconnecting…")
        playback.isLoading -> PhonePlaybackPresentation(PhonePlaybackPhase.Connecting, "Connecting…")
        playback.isBuffering -> PhonePlaybackPresentation(PhonePlaybackPhase.Buffering, "Buffering…")
        else -> PhonePlaybackPresentation(PhonePlaybackPhase.Playing, if (playback.isArchive) "Playing replay" else "Playing live")
    }
}

/** Another label for the same request is not another stream. Replay sources cannot be switched. */
fun phoneAlternateSources(current: Channel, sources: List<Channel>, isArchive: Boolean = false): List<Channel> {
    if (isArchive) return emptyList()
    val currentEndpoint = phoneStreamRequest(current)
    val seenKeys = hashSetOf(current.key)
    val seenEndpoints = hashSetOf<PhoneStreamRequest>().apply { currentEndpoint?.let(::add) }
    return sources.filter { source ->
        val endpoint = phoneStreamRequest(source)
        endpoint != null && source.key !in seenKeys && endpoint !in seenEndpoints &&
            seenKeys.add(source.key) && seenEndpoints.add(endpoint)
    }
}

fun phoneHasAlternateSource(current: Channel, sources: List<Channel>, isArchive: Boolean = false): Boolean =
    phoneAlternateSources(current, sources, isArchive).isNotEmpty()

private data class PhoneStreamRequest(val endpoint: String, val headers: List<Pair<String, String>>)

private fun phoneStreamRequest(channel: Channel): PhoneStreamRequest? = phoneStreamEndpoint(channel.streamUrl)?.let { endpoint ->
    // A shared endpoint may legitimately require different playlist-specific authorization headers.
    PhoneStreamRequest(endpoint, channel.headers.map { it.key.lowercase(Locale.ROOT) to it.value }.sortedBy { it.first })
}

private fun phoneStreamEndpoint(raw: String): String? = runCatching {
    val uri = URI(raw.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (scheme !in setOf("http", "https") || host.isNullOrBlank()) return@runCatching null
    val port = uri.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
    // Compare the request identity, including user info/query, without ever displaying or logging it.
    buildString {
        append(scheme).append("://")
        uri.rawUserInfo?.let { append(it).append('@') }
        append(host)
        port?.let { append(':').append(it) }
        append(uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/")
        uri.rawQuery?.let { append('?').append(it) }
    }
}.getOrNull()
