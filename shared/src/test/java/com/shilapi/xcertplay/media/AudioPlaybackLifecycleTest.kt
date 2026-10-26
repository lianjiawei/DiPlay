package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [AudioPlaybackLifecycleTest.FocusOnPlayTrack::class])
class AudioPlaybackLifecycleTest {
    @Test fun guidanceIsMarkedBeforePlayCanDeliverFocusAndTeardownClearsItsTail() = checkStart(fail = false)

    @Test fun failedPlayDoesNotLeaveAnActiveMarkerOrFocusTail() = checkStart(fail = true)

    private fun checkStart(fail: Boolean) {
        val diagnostics = mutableListOf<String>()
        val sink = AndroidMediaSink(navigationDuckingEnabled = false, onAudioDiagnostic = diagnostics::add)
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AndroidMediaSink::class.java.getDeclaredMethod(
            "audioRenderer", AudioStreamId::class.java, AudioFormat::class.java,
        ).apply { isAccessible = true }.invoke(sink, AudioStreamId(100, "default"),
            AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 100, "default"))
        val coordinator = sink.javaClass.getDeclaredField("audioFocusCoordinator")
            .apply { isAccessible = true }.get(sink) as AudioFocusCoordinator
        var now = 1_000_000_000L
        coordinator.nanoTime = { now }
        val track = AudioTrack.Builder().setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, AudioChannel.NAVIGATION)
            type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
            FocusOnPlayTrack.onPlay = {
                assertTrue(NavigationPlayback.focusSnapshot().active)
                coordinator.onExternalFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                if (fail) throw IllegalStateException("vendor play failure")
            }
            val play = type.getDeclaredMethod("startPlayback", AudioTrack::class.java).apply { isAccessible = true }
            if (fail) {
                try {
                    play.invoke(renderer, track)
                    fail("Expected play failure")
                } catch (error: InvocationTargetException) {
                    assertTrue(error.cause is IllegalStateException)
                }
                assertFalse(NavigationPlayback.focusSnapshot().active)
            } else {
                play.invoke(renderer, track)
                now += 200_000_000L
                coordinator.tickNavigationDucking()
                assertEquals(1f, coordinator.musicPcmGain, 0.0001f)
            }
            type.getDeclaredMethod("close").apply { isAccessible = true }.invoke(renderer)
            assertFalse(NavigationPlayback.snapshot().active)
            assertEquals(!fail, NavigationPlayback.focusSnapshot().active)
            assertTrue(diagnostics.any { it.contains("event=play-request") && it.contains("navFocus=true") })
            assertTrue(diagnostics.any { it.contains("event=close-request") })
            sink.close()
            assertFalse(NavigationPlayback.focusSnapshot().active)
        } finally {
            FocusOnPlayTrack.onPlay = null
            sink.close()
            track.release()
        }
    }

    @Implements(AudioTrack::class)
    class FocusOnPlayTrack {
        @Implementation fun play() { onPlay?.invoke() }
        @Implementation fun getUnderrunCount() = 0
        @Implementation fun getPlaybackHeadPosition() = 0
        companion object { var onPlay: (() -> Unit)? = null }
    }
}
