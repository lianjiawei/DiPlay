package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test fun conversionUsesTheRealLengthAndDoesNotReadOrWriteThePooledTail() {
        val first = byteArrayOf(0x65, 1, 2)
        val picture = byteArrayOf(0, 0, 0, first.size.toByte()) + first
        val pooled = ByteArray(32) { 0x55 }.also { picture.copyInto(it) }

        assertTrue(ScreenCodec.lengthPrefixedToAnnexBInPlace(pooled, picture.size))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + first, pooled.copyOf(picture.size))
        assertTrue(pooled.copyOfRange(picture.size, pooled.size).all { it == 0x55.toByte() })
    }

    @Test fun malformedPooledPictureIsRejectedWithoutChangingItsBytes() {
        val picture = byteArrayOf(0, 0, 0, 9, 0x65, 1)
        val pooled = ByteArray(32) { 0x55 }.also { picture.copyInto(it) }
        val original = pooled.copyOf()

        assertFalse(ScreenCodec.lengthPrefixedToAnnexBInPlace(pooled, picture.size))
        assertArrayEquals(original, pooled)
    }

    @Test fun oneByteNalLengthIsValidatedEvenThoughItsPrefixLooksLikeAStartCode() {
        val valid = byteArrayOf(0, 0, 0, 1, 0x65)
        assertTrue(ScreenCodec.lengthPrefixedToAnnexBInPlace(valid, valid.size))
        assertFalse(ScreenCodec.lengthPrefixedToAnnexBInPlace(byteArrayOf(0, 0, 0, 1), 4))
    }

    @Test fun existingAnnexBPictureIsAcceptedWithoutInspectingThePooledTail() {
        val picture = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val pooled = ByteArray(32) { 0x55 }.also { picture.copyInto(it) }

        assertTrue(ScreenCodec.lengthPrefixedToAnnexBInPlace(pooled, picture.size))
        assertArrayEquals(picture, pooled.copyOf(picture.size))
        assertTrue(pooled.copyOfRange(picture.size, pooled.size).all { it == 0x55.toByte() })
    }

    @Test fun readsTheFrameTimeFromTheScreenHeader() {
        val header = ByteArray(128)
        // NTP 32.32 little-endian at byte 8: 3 s + 0x40000000/2^32 s = 3.25 s.
        val raw = (3L shl 32) or 0x4000_0000L
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()
        assertEquals(3_250_000_000L, ScreenCodec.senderNanos(header))
        // One 1/60 s step, as the iPhone stamps consecutive main-screen frames.
        val next = raw + 0x0444_4444L
        for (i in 0 until 8) header[8 + i] = (next ushr (8 * i)).toByte()
        assertEquals(16_666_666L, ScreenCodec.senderNanos(header) - 3_250_000_000L)
        assertEquals(0L, ScreenCodec.senderNanos(ByteArray(12)))
    }
}
