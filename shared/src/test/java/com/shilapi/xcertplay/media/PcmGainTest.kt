package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmGainTest {
    @Test fun diagnosticsMeasureFullScaleNegativeSamplesAndOnlyTheRequestedSlice() {
        val data = samples(-32768, 1000, -500)
        assertEquals(32768, PcmGain.peakS16(data, 0, data.size))
        assertEquals(1000, PcmGain.peakS16(data, 2, 4))
        assertEquals(0, PcmGain.peakS16(data, data.size, 1))
    }
    @Test fun unityLeavesTheBufferByteForByte() {
        val data = samples(1000, -1000, 32767, -32768)
        val before = data.copyOf()
        PcmGain.scaleS16(data, 0, data.size, 1f)
        PcmGain.scaleS16(data, 0, data.size, 1.5f)
        assertArrayEquals(before, data)
    }

    @Test fun scalesEachSampleIncludingNegatives() {
        val data = samples(1000, -1000, 200, -200)
        PcmGain.scaleS16(data, 0, data.size, 0.25f)
        assertArrayEquals(samples(250, -250, 50, -50), data)
    }

    @Test fun respectsOffsetLengthAndAnOddTail() {
        val data = samples(1000, 1000, 1000) + byteArrayOf(0x7f)
        PcmGain.scaleS16(data, 2, 4, 0.5f)
        assertArrayEquals(samples(1000, 500, 500) + byteArrayOf(0x7f), data)
    }

    @Test fun zeroSilencesAndOutOfRangeGainsAreClamped() {
        val data = samples(1000, -1000)
        PcmGain.scaleS16(data, 0, data.size, 0f)
        assertArrayEquals(samples(0, 0), data)
        val second = samples(1000, -1000)
        PcmGain.scaleS16(second, 0, second.size, -3f)
        assertArrayEquals(samples(0, 0), second)
    }

    @Test fun extremeSamplesScaleWithoutWrapping() {
        val data = samples(-32768, 32767)
        PcmGain.scaleS16(data, 0, data.size, 0.5f)
        val decoded = decode(data)
        assertEquals(-16384, decoded[0].toInt())
        assertEquals(16383, decoded[1].toInt())
    }

    private fun samples(vararg values: Int): ByteArray = ByteArray(values.size * 2).also { out ->
        values.forEachIndexed { index, value ->
            out[index * 2] = value.toByte()
            out[index * 2 + 1] = (value shr 8).toByte()
        }
    }

    private fun decode(data: ByteArray): List<Short> = (0 until data.size / 2).map { index ->
        ((data[index * 2].toInt() and 0xff) or (data[index * 2 + 1].toInt() shl 8)).toShort()
    }
}
