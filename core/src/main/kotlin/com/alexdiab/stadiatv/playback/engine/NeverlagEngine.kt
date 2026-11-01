package com.alexdiab.stadiatv.playback.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Fire TV-sized translation of the profiles used by the iOS stream engine. */
data class StreamEngineConfiguration(
    val targetBufferSeconds: Double,
    val lowBufferSeconds: Double,
    val highBufferSeconds: Double,
    val buildRate: Float,
    val trickleRate: Float,
    val outageRate: Float,
    val shedRate: Float = 1.01f,
    val stallTimeoutMinimumSeconds: Double = 6.0,
    val stallTimeoutMaximumSeconds: Double = 12.0,
    val stallSafetyMarginSeconds: Double = 6.0,
    val startupTimeoutSeconds: Double = 10.0,
    val steppedBackoffAfterSeconds: Double = 60.0,
    val maximumConnectionsPerMinute: Int = 15,
)

/** Restores 188-byte MPEG-TS packet boundaries after arbitrary HTTP read boundaries. */
class TsPacketAligner {
    private var pending = ByteArray(0)

    fun reset() {
        pending = ByteArray(0)
    }

    fun append(input: ByteArray, length: Int = input.size): ByteArray {
        if (length <= 0) return ByteArray(0)
        val joined = ByteArray(pending.size + length)
        pending.copyInto(joined)
        input.copyInto(joined, pending.size, 0, length)

        var start = findSync(joined)
        if (start < 0) {
            pending = joined.takeLast(min(joined.size, MPEG_TS_PACKET_SIZE * 2)).toByteArray()
            return ByteArray(0)
        }
        val complete = (joined.size - start) / MPEG_TS_PACKET_SIZE
        if (complete == 0) {
            pending = joined.copyOfRange(start, joined.size)
            return ByteArray(0)
        }
        val end = start + complete * MPEG_TS_PACKET_SIZE
        val output = joined.copyOfRange(start, end)
        pending = joined.copyOfRange(end, joined.size)
        return output
    }

    private fun findSync(bytes: ByteArray): Int {
        for (index in bytes.indices) {
            if (bytes[index].toInt() and 0xff != MPEG_TS_SYNC) continue
            val second = index + MPEG_TS_PACKET_SIZE
            val third = second + MPEG_TS_PACKET_SIZE
            if (second >= bytes.size || bytes[second].toInt() and 0xff == MPEG_TS_SYNC) {
                if (third >= bytes.size || bytes[third].toInt() and 0xff == MPEG_TS_SYNC) return index
            }
        }
        return -1
    }
}

private class TsPacket(private val bytes: ByteArray, private val start: Int) {
    val pid: Int get() = ((u(1) and 0x1f) shl 8) or u(2)
    val payloadUnitStart: Boolean get() = u(1) and 0x40 != 0
    private val adaptationControl: Int get() = (u(3) ushr 4) and 0x03
    val hasPayload: Boolean get() = adaptationControl == 1 || adaptationControl == 3
    val hasAdaptation: Boolean get() = adaptationControl == 2 || adaptationControl == 3
    val randomAccess: Boolean get() = hasAdaptation && u(4) > 0 && u(5) and 0x40 != 0
    var continuityCounter: Int
        get() = u(3) and 0x0f
        set(value) { bytes[start + 3] = ((u(3) and 0xf0) or (value and 0x0f)).toByte() }

    val payloadOffset: Int
        get() {
            if (!hasPayload) return MPEG_TS_PACKET_SIZE
            val offset = if (hasAdaptation) 5 + u(4) else 4
            return offset.coerceAtMost(MPEG_TS_PACKET_SIZE)
        }

    fun pcrBase(): Long? {
        if (!hasAdaptation || u(4) < 7 || u(5) and 0x10 == 0) return null
        return (u(6).toLong() shl 25) or (u(7).toLong() shl 17) or (u(8).toLong() shl 9) or
            (u(9).toLong() shl 1) or (u(10).toLong() ushr 7)
    }

    fun setPcrBase(value: Long) {
        val wrapped = wrap33(value)
        bytes[start + 6] = (wrapped ushr 25).toByte()
        bytes[start + 7] = (wrapped ushr 17).toByte()
        bytes[start + 8] = (wrapped ushr 9).toByte()
        bytes[start + 9] = (wrapped ushr 1).toByte()
        bytes[start + 10] = (((wrapped and 1) shl 7) or (u(10).toLong() and 0x7f)).toByte()
    }

    fun pesTimestamps(): List<TimestampField> {
        val payload = payloadOffset
        if (!payloadUnitStart || payload + 14 >= MPEG_TS_PACKET_SIZE) return emptyList()
        if (u(payload) != 0 || u(payload + 1) != 0 || u(payload + 2) != 1) return emptyList()
        val flags = u(payload + 7)
        val fields = ArrayList<TimestampField>(2)
        if (flags and 0x80 != 0 && payload + 13 < MPEG_TS_PACKET_SIZE) fields += timestamp(payload + 9)
        if (flags and 0x40 != 0 && payload + 18 < MPEG_TS_PACKET_SIZE) fields += timestamp(payload + 14)
        return fields
    }

    fun containsRandomAccessNal(): Boolean {
        if (!payloadUnitStart) return false
        val from = payloadOffset
        var index = from
        while (index + 4 < MPEG_TS_PACKET_SIZE) {
            val threeByte = u(index) == 0 && u(index + 1) == 0 && u(index + 2) == 1
            val fourByte = index + 4 < MPEG_TS_PACKET_SIZE && u(index) == 0 && u(index + 1) == 0 && u(index + 2) == 0 && u(index + 3) == 1
            if (threeByte || fourByte) {
                val nal = u(index + if (threeByte) 3 else 4)
                val h264 = nal and 0x1f
                val hevc = (nal ushr 1) and 0x3f
                if (h264 == 5 || hevc in 19..21) return true
            }
            index++
        }
        return false
    }

    fun fingerprint(): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis as a signed Long.
        val end = min(MPEG_TS_PACKET_SIZE, payloadOffset + 256)
        for (index in payloadOffset until end) {
            hash = (hash xor u(index).toLong()) * 0x100000001b3L
        }
        return hash
    }

    private fun timestamp(offset: Int): TimestampField {
        val value = (((u(offset) ushr 1) and 0x07).toLong() shl 30) or
            (u(offset + 1).toLong() shl 22) or
            (((u(offset + 2) ushr 1) and 0x7f).toLong() shl 15) or
            (u(offset + 3).toLong() shl 7) or
            ((u(offset + 4) ushr 1) and 0x7f).toLong()
        return TimestampField(value) { newValue ->
            val value33 = wrap33(newValue)
            val prefix = u(offset) and 0xf0
            bytes[start + offset] = (prefix or (((value33 ushr 30).toInt() and 0x07) shl 1) or 1).toByte()
            bytes[start + offset + 1] = (value33 ushr 22).toByte()
            bytes[start + offset + 2] = ((((value33 ushr 15).toInt() and 0x7f) shl 1) or 1).toByte()
            bytes[start + offset + 3] = (value33 ushr 7).toByte()
            bytes[start + offset + 4] = ((((value33.toInt()) and 0x7f) shl 1) or 1).toByte()
        }
    }

    fun payloadCopy(): ByteArray = bytes.copyOfRange(start + payloadOffset, start + MPEG_TS_PACKET_SIZE)
    private fun u(relative: Int): Int = bytes[start + relative].toInt() and 0xff
}

private data class TimestampField(val value: Long, val write: (Long) -> Unit)

/** Learns the programme map so reconnect output can resume on a decodable video picture. */
private class ProgramMapTracker {
    var pmtPid: Int? = null
        private set
    var videoPid: Int? = null
        private set

    fun observe(packet: TsPacket) {
        if (!packet.payloadUnitStart || !packet.hasPayload) return
        val payload = packet.payloadCopy()
        if (payload.isEmpty()) return
        val sectionStart = 1 + (payload[0].toInt() and 0xff)
        if (sectionStart + 8 >= payload.size) return
        when (packet.pid) {
            0 -> parsePat(payload, sectionStart)
            pmtPid -> parsePmt(payload, sectionStart)
        }
    }

    private fun parsePat(bytes: ByteArray, start: Int) {
        if (bytes[start].toInt() and 0xff != 0x00) return
        val sectionLength = ((bytes[start + 1].toInt() and 0x0f) shl 8) or (bytes[start + 2].toInt() and 0xff)
        val end = min(bytes.size, start + 3 + sectionLength - 4)
        var index = start + 8
        while (index + 3 < end) {
            val program = ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)
            if (program != 0) {
                pmtPid = ((bytes[index + 2].toInt() and 0x1f) shl 8) or (bytes[index + 3].toInt() and 0xff)
                return
            }
            index += 4
        }
    }

    private fun parsePmt(bytes: ByteArray, start: Int) {
        if (bytes[start].toInt() and 0xff != 0x02) return
        val sectionLength = ((bytes[start + 1].toInt() and 0x0f) shl 8) or (bytes[start + 2].toInt() and 0xff)
        val end = min(bytes.size, start + 3 + sectionLength - 4)
        if (start + 12 > end) return
        val programInfoLength = ((bytes[start + 10].toInt() and 0x0f) shl 8) or (bytes[start + 11].toInt() and 0xff)
        var index = start + 12 + programInfoLength
        while (index + 4 < end) {
            val type = bytes[index].toInt() and 0xff
            val pid = ((bytes[index + 1].toInt() and 0x1f) shl 8) or (bytes[index + 2].toInt() and 0xff)
            if (type in VIDEO_STREAM_TYPES) videoPid = pid
            val infoLength = ((bytes[index + 3].toInt() and 0x0f) shl 8) or (bytes[index + 4].toInt() and 0xff)
            index += 5 + infoLength
        }
    }
}

/**
 * Keeps one transport timeline across HTTP connections. It rewrites continuity counters and
 * PTS/DTS/PCR after reconnect, drops undecodable pre-keyframe packets, and de-duplicates an
 * immediately re-sent keyframe. Media3 therefore sees one long MPEG-TS stream instead of a set
 * of provider sockets.
 */
class TsTimelineSplicer {
    private val program = ProgramMapTracker()
    private val continuity = HashMap<Int, Int>()
    private val recentKeyframes = ArrayDeque<Pair<Long?, Long>>()
    private val discontinuityPids = HashSet<Int>()
    private var awaitingKeyframe = false
    private var gatedBytes = 0
    private var needsTimestampAnchor = false
    private var timestampOffset = 0L
    private var lastOutputClock: Long? = null

    fun markReconnect() {
        awaitingKeyframe = program.videoPid != null
        gatedBytes = 0
        needsTimestampAnchor = lastOutputClock != null
        discontinuityPids.clear()
        discontinuityPids.addAll(continuity.keys)
    }

    fun process(aligned: ByteArray): ByteArray {
        if (aligned.isEmpty()) return aligned
        // Steady playback is the hot path. Rewrite in place and avoid allocating another transport buffer.
        if (!awaitingKeyframe && discontinuityPids.isEmpty()) {
            var offset = 0
            while (offset + MPEG_TS_PACKET_SIZE <= aligned.size) {
                val packet = TsPacket(aligned, offset)
                program.observe(packet)
                normalizeTimestamps(packet)
                normalizeContinuity(packet)
                rememberKeyframeIfNeeded(packet)
                offset += MPEG_TS_PACKET_SIZE
            }
            return aligned
        }
        // At most one adaptation-only discontinuity packet is inserted before each input packet.
        val output = ByteArray(aligned.size * 2)
        var outputSize = 0
        var offset = 0
        while (offset + MPEG_TS_PACKET_SIZE <= aligned.size) {
            val packet = TsPacket(aligned, offset)
            program.observe(packet)

            if (awaitingKeyframe) {
                gatedBytes += MPEG_TS_PACKET_SIZE
                val isPsi = packet.pid == 0 || packet.pid == program.pmtPid
                val isVideo = packet.pid == program.videoPid
                val isKeyframe = isVideo && (packet.randomAccess || packet.containsRandomAccessNal())
                if (isKeyframe) {
                    val rawPts = packet.pesTimestamps().firstOrNull()?.value
                    val identity = rawPts to packet.fingerprint()
                    if (identity !in recentKeyframes) {
                        awaitingKeyframe = false
                        rememberKeyframe(identity)
                    }
                }
                if (awaitingKeyframe && !isPsi && gatedBytes < MAX_GATED_BYTES) {
                    offset += MPEG_TS_PACKET_SIZE
                    continue
                }
                if (gatedBytes >= MAX_GATED_BYTES) awaitingKeyframe = false
            }

            if (packet.hasPayload && discontinuityPids.remove(packet.pid)) {
                discontinuityPacket(packet.pid, continuity.getValue(packet.pid)).copyInto(output, outputSize)
                outputSize += MPEG_TS_PACKET_SIZE
            }
            normalizeTimestamps(packet)
            normalizeContinuity(packet)
            rememberKeyframeIfNeeded(packet)
            aligned.copyInto(output, outputSize, offset, offset + MPEG_TS_PACKET_SIZE)
            outputSize += MPEG_TS_PACKET_SIZE
            offset += MPEG_TS_PACKET_SIZE
        }
        return output.copyOf(outputSize)
    }

    private fun normalizeTimestamps(packet: TsPacket) {
        val timestamps = packet.pesTimestamps()
        val pcr = packet.pcrBase()
        val anchor = timestamps.firstOrNull()?.value ?: pcr
        if (needsTimestampAnchor && anchor != null) {
            val target = wrap33((lastOutputClock ?: anchor) + NOMINAL_FRAME_TICKS)
            timestampOffset = signedDelta33(target, anchor)
            needsTimestampAnchor = false
        }
        for (field in timestamps) {
            val output = wrap33(field.value + timestampOffset)
            field.write(output)
            rememberClock(output)
        }
        if (pcr != null) {
            val output = wrap33(pcr + timestampOffset)
            packet.setPcrBase(output)
            rememberClock(output)
        }
    }

    private fun rememberClock(value: Long) {
        val last = lastOutputClock
        if (last == null || signedDelta33(value, last) > 0) lastOutputClock = value
    }

    private fun normalizeContinuity(packet: TsPacket) {
        if (!packet.hasPayload) return
        val last = continuity[packet.pid]
        val next = if (last == null) packet.continuityCounter else (last + 1) and 0x0f
        packet.continuityCounter = next
        continuity[packet.pid] = next
    }

    private fun rememberKeyframe(identity: Pair<Long?, Long>) {
        if (recentKeyframes.lastOrNull() == identity) return
        recentKeyframes += identity
        while (recentKeyframes.size > 12) recentKeyframes.removeFirst()
    }

    private fun rememberKeyframeIfNeeded(packet: TsPacket) {
        if (packet.pid == program.videoPid && (packet.randomAccess || packet.containsRandomAccessNal())) {
            rememberKeyframe(packet.pesTimestamps().firstOrNull()?.value to packet.fingerprint())
        }
    }

    /** Adaptation-only packets carry the MPEG-TS discontinuity flag without sacrificing media payload. */
    private fun discontinuityPacket(pid: Int, counter: Int): ByteArray = ByteArray(MPEG_TS_PACKET_SIZE) { 0xff.toByte() }.apply {
        this[0] = MPEG_TS_SYNC.toByte()
        this[1] = ((pid ushr 8) and 0x1f).toByte()
        this[2] = pid.toByte()
        this[3] = (0x20 or (counter and 0x0f)).toByte()
        this[4] = 183.toByte()
        this[5] = 0x80.toByte()
    }
}

enum class EnginePhase { OFF, STARTING, BUILDING, PLAYING, RECONNECTING, WAITING, FAILED }

data class StreamEngineSnapshot(
    val phase: EnginePhase = EnginePhase.OFF,
    val reconnectAttempt: Int = 0,
    val bytesReceived: Long = 0,
    val bitrateBitsPerSecond: Double = 0.0,
    val deliveryCadenceSeconds: Double = 0.0,
    val notice: String? = null,
)

/** Network-only health model. Decoder and rendering events deliberately do not feed this class. */
class StreamHealthMonitor {
    private var connectedAt = 0L
    private var lastByteAt = 0L
    private var sampleAt = 0L
    private var sampleBytes = 0L
    private val gaps = ArrayDeque<Double>()
    var bitrateBitsPerSecond: Double = 0.0
        private set
    var deliveryCadenceSeconds: Double = 0.0
        private set

    fun connectionOpened(nowMillis: Long) {
        connectedAt = nowMillis
        lastByteAt = nowMillis
        if (sampleAt == 0L) sampleAt = nowMillis
    }

    fun received(bytes: Int, nowMillis: Long) {
        if (connectedAt > 0 && nowMillis - connectedAt > 2_000 && lastByteAt > 0) {
            val gap = (nowMillis - lastByteAt) / 1_000.0
            if (gap >= 0.3) {
                gaps += gap
                while (gaps.size > 12) gaps.removeFirst()
                val sorted = gaps.sortedDescending()
                deliveryCadenceSeconds = if (sorted.size >= 3) sorted[1] else sorted[0]
            }
        }
        lastByteAt = nowMillis
        sampleBytes += bytes
        sample(nowMillis)
    }

    fun silenceSeconds(nowMillis: Long): Double = if (lastByteAt == 0L) 0.0 else (nowMillis - lastByteAt) / 1_000.0

    fun stallTimeout(bufferedSeconds: Double, configuration: StreamEngineConfiguration): Double =
        min(configuration.stallTimeoutMaximumSeconds, max(configuration.stallTimeoutMinimumSeconds,
            bufferedSeconds - configuration.stallSafetyMarginSeconds))

    private fun sample(nowMillis: Long) {
        val elapsed = (nowMillis - sampleAt) / 1_000.0
        if (elapsed < max(1.0, deliveryCadenceSeconds * 2)) return
        val bits = sampleBytes * 8.0 / elapsed
        val weight = 1 - (1 - 0.3).pow(elapsed)
        bitrateBitsPerSecond = if (bitrateBitsPerSecond == 0.0) bits else bitrateBitsPerSecond + weight * (bits - bitrateBitsPerSecond)
        sampleAt = nowMillis
        sampleBytes = 0
    }
}

data class RecoveryDecision(val delayMillis: Long, val terminal: Boolean = false, val reason: String? = null)

/** The one authority that schedules reconnects and caps connection churn. */
class RecoveryReferee(private val configuration: StreamEngineConfiguration) {
    private val connectionTimes = ArrayDeque<Long>()
    private var startedAt = 0L
    private var lastProgrammeAt = 0L
    private var consecutiveFailures = 0
    private var steppedAttempt = 0

    fun started(nowMillis: Long) {
        if (startedAt == 0L) startedAt = nowMillis
        recordConnection(nowMillis)
    }

    fun programmeArrived(nowMillis: Long) {
        lastProgrammeAt = nowMillis
        consecutiveFailures = 0
        steppedAttempt = 0
    }

    fun failed(nowMillis: Long, responseCode: Int? = null): RecoveryDecision {
        consecutiveFailures++
        val hasPlayed = lastProgrammeAt > 0
        if (!hasPlayed && responseCode != null && responseCode in 400..499 && responseCode != 429) {
            return RecoveryDecision(0, terminal = true, reason = "Provider rejected the stream ($responseCode)")
        }
        if (!hasPlayed && startedAt > 0 && nowMillis - startedAt >= (configuration.startupTimeoutSeconds * 1_000).toLong()) {
            return RecoveryDecision(0, terminal = true, reason = "No playable transport stream arrived")
        }

        val drought = if (lastProgrammeAt > 0) (nowMillis - lastProgrammeAt) / 1_000.0 else 0.0
        var delay = if (drought >= configuration.steppedBackoffAfterSeconds) {
            val stepped = longArrayOf(15_000, 30_000, 60_000)
            stepped[min(steppedAttempt++, stepped.lastIndex)]
        } else {
            val ordinary = longArrayOf(0, 500, 1_000, 2_000, 3_000, 4_000, 5_000)
            ordinary[min(consecutiveFailures - 1, ordinary.lastIndex)]
        }
        delay = max(delay, rateLimitDelay(nowMillis))
        return RecoveryDecision(delay)
    }

    fun connecting(nowMillis: Long) = recordConnection(nowMillis)

    private fun recordConnection(nowMillis: Long) {
        prune(nowMillis)
        connectionTimes += nowMillis
    }

    private fun rateLimitDelay(nowMillis: Long): Long {
        prune(nowMillis)
        if (connectionTimes.size < configuration.maximumConnectionsPerMinute) return 0
        return max(0, connectionTimes.first() + 60_000 - nowMillis)
    }

    private fun prune(nowMillis: Long) {
        while (connectionTimes.isNotEmpty() && nowMillis - connectionTimes.first() >= 60_000) connectionTimes.removeFirst()
    }
}

/** Small, pitch-corrected rate changes bank reserve without the iOS engine's aggressive 0.90x floor. */
class PlaybackPaceController {
    var rate: Float = 1f
        private set

    fun update(bufferedSeconds: Double, outage: Boolean, configuration: StreamEngineConfiguration, elapsedSeconds: Double): Float? {
        val target = when {
            outage && bufferedSeconds < configuration.targetBufferSeconds -> configuration.outageRate
            bufferedSeconds < configuration.lowBufferSeconds -> configuration.buildRate
            bufferedSeconds < configuration.targetBufferSeconds -> configuration.trickleRate
            bufferedSeconds > configuration.highBufferSeconds -> configuration.shedRate
            else -> 1f
        }
        val step = (0.005 * max(0.0, elapsedSeconds)).toFloat()
        val next = if (target > rate) min(target, rate + step) else max(target, rate - step)
        if (abs(next - rate) < 0.001f && next != target) return null
        if (next == rate) return null
        rate = (next * 1_000).toInt() / 1_000f
        return rate
    }

    fun reset(): Float? {
        if (rate == 1f) return null
        rate = 1f
        return rate
    }
}

private const val MPEG_TS_PACKET_SIZE = 188
private const val MPEG_TS_SYNC = 0x47
private const val MAX_GATED_BYTES = 2 * 1024 * 1024
private const val NOMINAL_FRAME_TICKS = 3_600L
private const val CLOCK_WRAP = 1L shl 33
private const val CLOCK_MASK = CLOCK_WRAP - 1
private const val CLOCK_HALF = CLOCK_WRAP / 2
private val VIDEO_STREAM_TYPES = setOf(0x01, 0x02, 0x10, 0x1b, 0x24, 0x27)

private fun wrap33(value: Long): Long = value and CLOCK_MASK
private fun signedDelta33(left: Long, right: Long): Long = ((left - right + CLOCK_HALF) and CLOCK_MASK) - CLOCK_HALF
