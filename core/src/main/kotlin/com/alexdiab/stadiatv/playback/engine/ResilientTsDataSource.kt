package com.alexdiab.stadiatv.playback.engine

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException

interface StreamEngineListener {
    fun onSnapshot(snapshot: StreamEngineSnapshot)
}

/**
 * A reconnecting MPEG-TS source. EOF, a silent socket, or a transient HTTP failure replaces only
 * the upstream connection; Media3's extractor and decoder continue reading the same logical source.
 */
@UnstableApi
class ResilientTsDataSource private constructor(
    private val upstreamFactory: HttpDataSource.Factory,
    private val configuration: StreamEngineConfiguration,
    private val listener: StreamEngineListener,
) : BaseDataSource(true) {
    private val aligner = TsPacketAligner()
    private val splicer = TsTimelineSplicer()
    private val health = StreamHealthMonitor()
    private val referee = RecoveryReferee(configuration)
    private val readBuffer = ByteArray(32 * 1024)
    private var pending = ByteArray(0)
    private var pendingOffset = 0
    @Volatile private var upstream: HttpDataSource? = null
    private var originalSpec: DataSpec? = null
    private var opened = false
    private var receivedAny = false
    private var reconnectAttempt = 0
    private var totalBytes = 0L
    private var lastPublishedAt = 0L
    private var lastPublishedPhase: EnginePhase? = null
    private var lastPublishedNotice: String? = null
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        originalSpec = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        closed = false
        referee.started(now())
        publish(EnginePhase.STARTING, "Building a protected stream reserve")
        connect(initial = true)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (!closed) {
            if (pendingOffset < pending.size) {
                val count = minOf(length, pending.size - pendingOffset)
                pending.copyInto(buffer, offset, pendingOffset, pendingOffset + count)
                pendingOffset += count
                bytesTransferred(count)
                return count
            }
            pending = ByteArray(0)
            pendingOffset = 0
            try {
                val count = upstream?.read(readBuffer, 0, readBuffer.size) ?: C.RESULT_END_OF_INPUT
                if (count == C.RESULT_END_OF_INPUT) {
                    recover(IOException("Provider ended the live stream"))
                    continue
                }
                if (count <= 0) continue
                val at = now()
                health.received(count, at)
                totalBytes += count
                receivedAny = true
                referee.programmeArrived(at)
                reconnectAttempt = 0
                val aligned = aligner.append(readBuffer, count)
                pending = splicer.process(aligned)
                publish(EnginePhase.PLAYING, null)
            } catch (cancelled: InterruptedIOException) {
                if (closed || Thread.currentThread().isInterrupted) throw cancelled
                recover(cancelled)
            } catch (error: IOException) {
                if (closed) return C.RESULT_END_OF_INPUT
                recover(error)
            }
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = upstream?.uri ?: originalSpec?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        closed = true
        closeUpstream()
        pending = ByteArray(0)
        aligner.reset()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun recover(cause: IOException) {
        val at = now()
        val response = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val decision = referee.failed(at, response)
        if (decision.terminal) {
            publish(EnginePhase.FAILED, decision.reason)
            throw StreamEngineException(decision.reason ?: "The stream engine could not recover", cause)
        }
        reconnectAttempt++
        publish(if (decision.delayMillis > 0) EnginePhase.WAITING else EnginePhase.RECONNECTING,
            if (decision.delayMillis > 0) "Retrying in ${"%.1f".format(decision.delayMillis / 1_000.0)} seconds" else "Replacing the provider connection")
        closeUpstream()
        if (decision.delayMillis > 0) waitInterruptibly(decision.delayMillis)
        if (closed) return
        aligner.reset()
        splicer.markReconnect()
        referee.connecting(now())
        connect(initial = false)
    }

    private fun connect(initial: Boolean) {
        while (!closed) {
            val source = upstreamFactory.createDataSource()
            try {
                source.open(originalSpec ?: throw IOException("Missing stream request"))
                upstream = source
                health.connectionOpened(now())
                publish(if (initial && !receivedAny) EnginePhase.BUILDING else EnginePhase.RECONNECTING,
                    if (initial && !receivedAny) "Filling the stream reserve" else "Connection restored; joining the timeline")
                return
            } catch (error: IOException) {
                runCatching { source.close() }
                val response = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val decision = referee.failed(now(), response)
                if (decision.terminal) {
                    publish(EnginePhase.FAILED, decision.reason)
                    throw StreamEngineException(decision.reason ?: "The provider rejected the stream", error)
                }
                reconnectAttempt++
                publish(if (decision.delayMillis > 0) EnginePhase.WAITING else EnginePhase.RECONNECTING,
                    if (decision.delayMillis > 0) "Retrying in ${"%.1f".format(decision.delayMillis / 1_000.0)} seconds" else "Trying a fresh connection")
                if (decision.delayMillis > 0) waitInterruptibly(decision.delayMillis)
                referee.connecting(now())
            }
        }
        throw InterruptedIOException("Stream was closed")
    }

    private fun closeUpstream() {
        val source = upstream
        upstream = null
        runCatching { source?.close() }
    }

    private fun waitInterruptibly(delayMillis: Long) {
        var remaining = delayMillis
        while (remaining > 0 && !closed) {
            val slice = minOf(remaining, 250)
            try {
                Thread.sleep(slice)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Reconnect cancelled")
            }
            remaining -= slice
        }
    }

    private fun publish(phase: EnginePhase, notice: String?) {
        val at = now()
        if (phase == lastPublishedPhase && notice == lastPublishedNotice && at - lastPublishedAt < 1_000) return
        lastPublishedAt = at
        lastPublishedPhase = phase
        lastPublishedNotice = notice
        listener.onSnapshot(StreamEngineSnapshot(
            phase = phase,
            reconnectAttempt = reconnectAttempt,
            bytesReceived = totalBytes,
            bitrateBitsPerSecond = health.bitrateBitsPerSecond,
            deliveryCadenceSeconds = health.deliveryCadenceSeconds,
            notice = notice,
        ))
    }

    private fun now(): Long = System.currentTimeMillis()

    class Factory(
        headers: Map<String, String>,
        userAgent: String,
        private val configuration: StreamEngineConfiguration,
        private val listener: StreamEngineListener,
    ) : DataSource.Factory {
        private val upstreamFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs((configuration.stallTimeoutMinimumSeconds * 1_000).toInt())
            .setDefaultRequestProperties(headers)

        override fun createDataSource(): DataSource = ResilientTsDataSource(upstreamFactory, configuration, listener)
    }
}

class StreamEngineException(message: String, cause: Throwable? = null) : IOException(message, cause)
