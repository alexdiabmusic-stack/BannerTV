package com.alexdiab.stadiatv.mobile

import com.alexdiab.stadiatv.live.Channel
import com.alexdiab.stadiatv.playback.PlaybackTimeline
import com.alexdiab.stadiatv.playback.PlaybackUi
import org.junit.Assert.*
import org.junit.Test

class PhonePlaybackPresentationTest {
    private fun channel(id: String, url: String = "https://provider.example/$id") =
        Channel(id, "Channel $id", url, null, "Sports", "playlist", "Playlist")

    @Test fun failureWinsOverStalePlayingPausedBufferingAndEndedState() {
        val result = phonePlaybackPresentation(PlaybackUi(firstFrameRendered = true, isUserPaused = true,
            isBuffering = true, reconnectAttempt = 3, timeline = PlaybackTimeline(ended = true),
            errorMessage = "This stream isn't available."))
        assertEquals(PhonePlaybackPhase.Failed, result.phase)
        assertEquals("Stream unavailable", result.statusLabel)
        assertTrue(result.recoveryRequired)
    }

    @Test fun knownControllerReasonsRemainUsefulAndDistinct() {
        val unavailable = phonePlaybackPresentation(PlaybackUi(errorMessage = "This stream isn't available."))
        val unresponsive = phonePlaybackPresentation(PlaybackUi(errorMessage = "This stream isn't responding."))
        assertNotEquals(unavailable.failureTitle, unresponsive.failureTitle)
        assertTrue(unavailable.failureMessage!!.contains("provider"))
        assertTrue(unresponsive.failureMessage!!.contains("connection attempts"))
    }

    @Test fun unknownOrCredentialBearingFailuresAreNeverEchoed() {
        for (raw in listOf("HTTP 403 https://user:private-password@provider.example/stream?token=private-token",
            "This stream isn't available. https://provider.example/private-password", "private-token", "", "\n\t")) {
            val result = phonePlaybackPresentation(PlaybackUi(errorMessage = raw))
            assertEquals("Stream interrupted", result.statusLabel)
            assertEquals("Playback couldn't continue. Restart this stream or choose another channel.", result.failureMessage)
            assertFalse(result.toString().contains("private-password"))
            assertFalse(result.toString().contains("private-token"))
        }
    }

    @Test fun loadingAndReconnectNeverClaimPlayingLive() {
        assertEquals(PhonePlaybackPhase.Connecting, phonePlaybackPresentation(PlaybackUi(isLoading = true)).phase)
        assertEquals(PhonePlaybackPhase.Reconnecting, phonePlaybackPresentation(PlaybackUi(isLoading = true, reconnectAttempt = 2)).phase)
        assertEquals(PhonePlaybackPhase.Buffering, phonePlaybackPresentation(PlaybackUi(isBuffering = true)).phase)
        assertEquals(PhonePlaybackPhase.Paused, phonePlaybackPresentation(PlaybackUi(isUserPaused = true, isBuffering = true)).phase)
    }

    @Test fun replayAndEndedCopyDoNotPromiseALiveProgramme() {
        assertEquals("Playing replay", phonePlaybackPresentation(PlaybackUi(isArchive = true)).statusLabel)
        assertEquals("Replay finished", phonePlaybackPresentation(PlaybackUi(isArchive = true, timeline = PlaybackTimeline(ended = true))).statusLabel)
        assertEquals("Stream ended", phonePlaybackPresentation(PlaybackUi(timeline = PlaybackTimeline(ended = true))).statusLabel)
        assertEquals("Playing live", phonePlaybackPresentation(PlaybackUi(firstFrameRendered = true)).statusLabel)
    }

    @Test fun endedLiveRequiresReconnectWhileAnEndedArchiveKeepsReplayAction() {
        val live = phonePlaybackPresentation(PlaybackUi(firstFrameRendered = true, timeline = PlaybackTimeline(ended = true)))
        val replay = phonePlaybackPresentation(PlaybackUi(isArchive = true, timeline = PlaybackTimeline(ended = true)))
        assertTrue(live.isLiveEnded)
        assertTrue(live.recoveryRequired)
        assertEquals("Stream ended", live.failureTitle)
        assertTrue(live.failureMessage!!.contains("Restart to reconnect"))
        assertFalse(replay.isLiveEnded)
        assertFalse(replay.recoveryRequired)
        assertNull(replay.failureTitle)
        assertEquals(PhonePlaybackPhase.Ended, replay.phase)
    }

    @Test fun currentOnlyDuplicateKeysAndSameEndpointAreNotAlternates() {
        val current = channel("one", "https://provider.example/live")
        assertFalse(phoneHasAlternateSource(current, emptyList()))
        assertFalse(phoneHasAlternateSource(current, listOf(current)))
        assertFalse(phoneHasAlternateSource(current, listOf(current.copy(streamUrl = "https://provider.example/other"))))
        assertFalse(phoneHasAlternateSource(current, listOf(channel("two", " HTTPS://PROVIDER.EXAMPLE:443/live#ignored "))))
    }

    @Test fun unsupportedMalformedAndEmptySourceUrlsAreNotOffered() {
        val current = channel("one")
        val invalid = listOf("", " ", "file:///tmp/stream", "https:///missing-host", "https://bad host/live", "relative/path")
            .mapIndexed { index, url -> channel("invalid$index", url) }
        assertTrue(phoneAlternateSources(current, invalid).isEmpty())
    }

    @Test fun distinctUsableAlternatesKeepTheirPreferenceOrderAndDeduplicateEndpoint() {
        val current = channel("one")
        val two = channel("two")
        val three = channel("three")
        val sources = listOf(current, two, channel("copy", two.streamUrl), three, three)
        assertEquals(listOf(two, three), phoneAlternateSources(current, sources))
        assertTrue(phoneHasAlternateSource(current, sources))
        assertFalse(phoneHasAlternateSource(current, sources, isArchive = true))
    }

    @Test fun sharedEndpointWithDifferentRequiredHeadersPreservesARealPlaylistAlternative() {
        val current = channel("one").copy(headers = mapOf("Authorization" to "Bearer fixture-one"))
        val duplicate = current.copy(id = "duplicate", headers = mapOf("authorization" to "Bearer fixture-one"))
        val alternative = current.copy(id = "alternative", headers = mapOf("Authorization" to "Bearer fixture-two"))
        assertEquals(listOf(alternative), phoneAlternateSources(current, listOf(duplicate, alternative)))
    }
}
