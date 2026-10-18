package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioPcmGainRegressionTest.LimitedWriteTrack::class])
class AudioPcmGainRegressionTest {
    @Test fun prebufferChunksEachReceiveTheGainOnlyOnce() = checkRendered(playing = false)

    @Test fun partialWritesDoNotScaleTheirUnwrittenTailAgain() =
        checkRendered(playing = true, maxWrite = 512)

    @Test fun loudspeakerCallIsUnaffectedByDuckingAndTheCarMuteKey() =
        checkRendered(playing = true, maxWrite = 512, audioType = "telephony", muted = true)

    @Test fun aMuteArrivingDuringAUnityPartialWriteAffectsTheUnwrittenRawTail() =
        checkRendered(playing = true, maxWrite = 512, ducking = false, muteAfterFirstWrite = true)

    private fun checkRendered(playing: Boolean, maxWrite: Int = Int.MAX_VALUE,
        audioType: String = "media", muted: Boolean = false, ducking: Boolean = true,
        muteAfterFirstWrite: Boolean = false) {
        LimitedWriteTrack.limit = maxWrite
        LimitedWriteTrack.output.reset()
        LimitedWriteTrack.afterFirstWrite = null
        val sink = AndroidMediaSink(navigationDuckingEnabled = ducking)
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AndroidMediaSink::class.java.getDeclaredMethod(
            "audioRenderer", AudioStreamId::class.java, AudioFormat::class.java,
        ).apply { isAccessible = true }.invoke(sink, AudioStreamId(100, audioType),
            AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, audioType))
        val track = AudioTrack.Builder().setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(16_384).build()
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, AudioChannel.MEDIA)
            type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
            type.getDeclaredField("startThresholdBytes").apply { isAccessible = true }.setInt(renderer, 16_384)
            type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(renderer, true)
            type.getDeclaredField("playbackStarted").apply { isAccessible = true }.setBoolean(renderer, playing)
            type.getDeclaredMethod("setAudioOffMuted", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(renderer, muted)
            if (muteAfterFirstWrite) LimitedWriteTrack.afterFirstWrite = {
                type.getDeclaredMethod("setAudioOffMuted", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(renderer, true)
            }
            val coordinator = sink.javaClass.getDeclaredField("audioFocusCoordinator")
                .apply { isAccessible = true }.get(sink) as AudioFocusCoordinator
            var now = 1_000_000_000L
            coordinator.nanoTime = { now }
            coordinator.navigationAudioDetected()
            repeat(20) { now += 20_000_000L; coordinator.tickNavigationDucking() }
            val pcm = ByteArray(6_144)
            for (i in pcm.indices step 2) { pcm[i] = 0; pcm[i + 1] = 0x40 }
            type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
            val rendered = LimitedWriteTrack.output.toByteArray()
            assertEquals(pcm.size, rendered.size)
            assertArrayEquals(pcm, rendered)
            for (offset in rendered.indices step 2) {
                val expected = when {
                    muteAfterFirstWrite && offset >= 512 -> 0
                    audioType == "telephony" || !ducking -> 16_384
                    else -> 4_115
                }
                val sample = ((rendered[offset].toInt() and 255) or
                    (rendered[offset + 1].toInt() shl 8)).toShort().toInt()
                assertEquals("Incorrect gain at sample ${offset / 2}", expected, sample)
            }
        } finally { sink.close(); track.release() }
    }

    @Implements(AudioTrack::class)
    class LimitedWriteTrack {
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            val count = minOf(size, limit)
            output.write(bytes, offset, count)
            afterFirstWrite?.let { afterFirstWrite = null; it() }
            return count
        }
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getPlaybackHeadPosition() = 0
        @Implementation fun play() = Unit
        companion object {
            var limit = Int.MAX_VALUE
            var afterFirstWrite: (() -> Unit)? = null
            val output = ByteArrayOutputStream()
        }
    }
}
