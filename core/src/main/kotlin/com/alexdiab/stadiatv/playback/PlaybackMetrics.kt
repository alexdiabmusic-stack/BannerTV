package com.alexdiab.stadiatv.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Debug-build playback telemetry, one line per event in logcat under "PlaybackMetrics": time to first frame, every
 * rebuffer after playback began (and how long), dropped frames and the decoder in use. It is how smoothness is checked
 * on a device without guessing from screenshots.
 */
@UnstableApi
internal class PlaybackMetrics : AnalyticsListener {
    private var loadStarted = SystemClock.elapsedRealtime()
    private var playedOnce = false
    private var bufferingSince = 0L
    private var rebuffers = 0
    private var rebufferMillis = 0L
    private var dropped = 0

    fun reset() {
        loadStarted = SystemClock.elapsedRealtime()
        playedOnce = false
        bufferingSince = 0L
        rebuffers = 0
        rebufferMillis = 0L
        dropped = 0
    }

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
        Log.i(TAG, "first frame after ${SystemClock.elapsedRealtime() - loadStarted} ms")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> bufferingSince = now
            Player.STATE_READY -> {
                if (bufferingSince != 0L && playedOnce) {
                    val took = now - bufferingSince
                    rebuffers++
                    rebufferMillis += took
                    Log.i(TAG, "REBUFFER #$rebuffers lasted $took ms (total $rebufferMillis ms)")
                }
                bufferingSince = 0L
                playedOnce = true
            }
        }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        dropped += droppedFrames
        Log.i(TAG, "dropped $droppedFrames frames in $elapsedMs ms (total $dropped)")
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Log.i(TAG, "video decoder $decoderName ready in $initializationDurationMs ms")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
        Log.i(TAG, "video ${format.width}x${format.height} ${format.frameRate} fps ${format.bitrate / 1000} kbps ${format.codecs}")
    }

    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        Log.i(TAG, "video size ${videoSize.width}x${videoSize.height}")
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        Log.w(TAG, "player error ${error.errorCodeName}")
    }

    fun summary(): String = "rebuffers=$rebuffers rebufferMs=$rebufferMillis dropped=$dropped"

    private companion object { const val TAG = "PlaybackMetrics" }
}
