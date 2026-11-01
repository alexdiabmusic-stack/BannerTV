package com.alexdiab.stadiatv.playback.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverlagEngineTest {
    @Test
    fun `aligner finds sync and carries an incomplete packet to the next read`() {
        val first = packet(pid = 100, counter = 2, pts = 90_000)
        val second = packet(pid = 100, counter = 3, pts = 93_600)
        val wire = byteArrayOf(0x12, 0x34, 0x47, 0x55) + first + second
        val aligner = TsPacketAligner()

        val a = aligner.append(wire, 117)
        val b = aligner.append(wire.copyOfRange(117, wire.size))

        assertEquals(0, a.size)
        assertArrayEquals(first + second, b)
    }

    @Test
    fun `splicer continues continuity counters and timestamps across reconnect`() {
        val splicer = TsTimelineSplicer()
        val before = splicer.process(packet(pid = 256, counter = 7, pts = 900_000))
        splicer.markReconnect()
        val after = splicer.process(packet(pid = 256, counter = 1, pts = 45_000))

        assertEquals(7, before[3].toInt() and 0x0f)
        assertEquals(7, after[3].toInt() and 0x0f)
        assertEquals(0x80, after[5].toInt() and 0x80)
        assertEquals(8, after[188 + 3].toInt() and 0x0f)
        val firstPts = readPts(before)
        val joinedPts = readPts(after, 188)
        assertTrue(joinedPts > firstPts)
        assertEquals(3_600, joinedPts - firstPts)
    }

    @Test
    fun `referee uses immediate then bounded backoff`() {
        val referee = RecoveryReferee(configuration())
        referee.started(1_000)
        referee.programmeArrived(2_000)
        val delays = (0 until 8).map { index -> referee.failed(3_000L + index * 100).delayMillis }

        assertEquals(listOf(0L, 500L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 5_000L), delays)
    }

    @Test
    fun `referee rejects a dead forbidden startup route`() {
        val referee = RecoveryReferee(configuration())
        referee.started(1_000)

        val decision = referee.failed(1_250, responseCode = 403)

        assertTrue(decision.terminal)
        assertTrue(decision.reason.orEmpty().contains("403"))
    }

    @Test
    fun `pace changes slowly and remains conservative`() {
        val pace = PlaybackPaceController()
        val config = configuration()
        repeat(10) { pace.update(bufferedSeconds = 1.0, outage = true, configuration = config, elapsedSeconds = 1.0) }

        assertTrue(pace.rate < 1f)
        assertTrue(pace.rate >= config.outageRate)
        val reset = pace.reset()
        assertEquals(1f, reset)
    }

    @Test
    fun `health monitor learns burst delivery cadence without treating bytes as decoder health`() {
        val health = StreamHealthMonitor()
        health.connectionOpened(1_000)
        health.received(10_000, 4_000)
        health.received(10_000, 7_000)
        health.received(10_000, 10_000)

        assertTrue(health.deliveryCadenceSeconds >= 3.0)
        assertFalse(health.bitrateBitsPerSecond.isNaN())
    }

    private fun configuration() = StreamEngineConfiguration(
        targetBufferSeconds = 20.0,
        lowBufferSeconds = 8.0,
        highBufferSeconds = 35.0,
        buildRate = 0.99f,
        trickleRate = 0.995f,
        outageRate = 0.98f,
    )

    private fun packet(pid: Int, counter: Int, pts: Long): ByteArray {
        val bytes = ByteArray(188) { 0xff.toByte() }
        bytes[0] = 0x47
        bytes[1] = (0x40 or ((pid ushr 8) and 0x1f)).toByte()
        bytes[2] = pid.toByte()
        bytes[3] = (0x10 or (counter and 0x0f)).toByte()
        bytes[4] = 0x00
        bytes[5] = 0x00
        bytes[6] = 0x01
        bytes[7] = 0xe0.toByte()
        bytes[8] = 0x00
        bytes[9] = 0x00
        bytes[10] = 0x80.toByte()
        bytes[11] = 0x80.toByte()
        bytes[12] = 0x05
        writePts(bytes, 13, pts)
        return bytes
    }

    private fun writePts(bytes: ByteArray, offset: Int, pts: Long) {
        val value = pts and ((1L shl 33) - 1)
        bytes[offset] = (0x20 or (((value ushr 30).toInt() and 7) shl 1) or 1).toByte()
        bytes[offset + 1] = (value ushr 22).toByte()
        bytes[offset + 2] = ((((value ushr 15).toInt() and 0x7f) shl 1) or 1).toByte()
        bytes[offset + 3] = (value ushr 7).toByte()
        bytes[offset + 4] = (((value.toInt() and 0x7f) shl 1) or 1).toByte()
    }

    private fun readPts(bytes: ByteArray, packetOffset: Int = 0): Long {
        val offset = packetOffset + 13
        fun u(index: Int) = bytes[index].toInt() and 0xff
        return (((u(offset) ushr 1) and 7).toLong() shl 30) or
            (u(offset + 1).toLong() shl 22) or
            (((u(offset + 2) ushr 1) and 0x7f).toLong() shl 15) or
            (u(offset + 3).toLong() shl 7) or
            ((u(offset + 4) ushr 1) and 0x7f).toLong()
    }
}
