package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.BlockingQueue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioCongestionRecoveryTest.RateTrack::class])
class AudioCongestionRecoveryTest {
    private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    private lateinit var renderer: Any
    private lateinit var track: AudioTrack
    @Before fun setUp() {
        MediaClockTrim.learnedTrim = 0.0
        RateTrack.rates.clear()
        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(96_000).build()
        renderer = type.declaredConstructors.single { it.parameterCount == 14 }.apply { isAccessible = true }
            .newInstance(AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media"),
                false, false, 0, 0, AudioFocusCoordinator(null, false, false),
                0, 300, { _: String -> }, 0L, false, null, false, true)
        set("track", track)
        set("mappedChannel", AudioChannel.MEDIA)
        set("playbackStarted", true)
        set("startThresholdBytes", 57_600)
        set("bytesPerSecond", 48_000 * 4)
        set("clockTrim", MediaClockTrim(48_000, 14_400))
    }
    private fun set(name: String, value: Any) = type.getDeclaredField(name)
        .apply { isAccessible = true }.set(renderer, value)
    private fun writeAtTheObservedHardwareQueueLevel() {
        // 12,000 frames (250 ms) against a 300 ms target, matching the report.
        type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(renderer, ByteArray(48_000), 0, 48_000)
    }
    @Test fun fullInputQueueMustPreventFurtherSlowdown() {
        val queue = type.getDeclaredField("queue").apply { isAccessible = true }
            .get(renderer) as BlockingQueue<Any>
        val packetClass = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
        val packet = packetClass.getDeclaredConstructor(ByteArray::class.java, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(ByteArray(12), 0)
        repeat(192) { assertTrue(queue.offer(packet)) }
        assertEquals(0, queue.remainingCapacity())
        writeAtTheObservedHardwareQueueLevel()
        assertEquals(1, RateTrack.rates.size)
        assertTrue("A saturated receive queue still causes a further slowdown: ${RateTrack.rates}",
            RateTrack.rates.single() >= 48_000)
    }
    @Test fun lowHardwareQueueWithoutInputCongestionCanStillRequestSlowdown() {
        writeAtTheObservedHardwareQueueLevel()
        assertTrue(RateTrack.rates.single() < 48_000)
    }
    @After fun cleanup() {
        type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(renderer)
        track.release()
        MediaClockTrim.learnedTrim = 0.0
    }
    @Implements(AudioTrack::class)
    class RateTrack {
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int = size
        @Implementation fun setPlaybackRate(rate: Int): Int { rates += rate; return AudioTrack.SUCCESS }
        companion object { val rates = mutableListOf<Int>() }
    }
}
