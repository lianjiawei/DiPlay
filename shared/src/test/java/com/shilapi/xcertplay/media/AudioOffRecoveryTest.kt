package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
class AudioOffRecoveryTest {
    @After fun cleanup() { CarAudioOff.detach() }

    @Test fun carMuteStillSilencesMusicWithNavigationDuckingOffAndIsReported() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        CarAudioOff.attach(app)
        assertTrue(CarAudioOff.muted)
        val diagnostics = mutableListOf<String>()
        val sink = AndroidMediaSink(context = app, navigationDuckingEnabled = false,
            onAudioDiagnostic = { diagnostics += it })
        sink.setNavigationDuckingEnabled(false)
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
            AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
            .invoke(sink, AudioStreamId(100, "media"),
                AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 100, "media"))
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, AudioChannel.MEDIA)
            type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
            type.getDeclaredField("startThresholdBytes").apply { isAccessible = true }.setInt(renderer, 4096)
            type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(renderer, true)
            val pcm = ByteArray(2048)
            for (i in pcm.indices step 2) pcm[i + 1] = 0x40
            type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
            assertTrue("Audio-off must actually silence the samples", pcm.all { it == 0.toByte() })
            assertTrue(diagnostics.any { it.contains("audio-off muted=true source=system") })
            assertTrue(diagnostics.any { it.contains("audioOff=true") })
            assertTrue(diagnostics.any { it.contains("outputPeak=0 effectiveGain=0.0 audioOff=true") })
        } finally { sink.close() }
    }

    @Test fun systemUnmuteRecoversTheInferredMuteWhenSessionReattaches() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        CarAudioOff.attach(app)
        assertTrue(CarAudioOff.muted)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        assertFalse(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        CarAudioOff.attach(app)
        assertFalse("Cached audio-off does not recover when system unmutes without its special broadcast", CarAudioOff.muted)
    }

    @Test fun standardMusicUnmuteBroadcastRecoversDuringTheSameSession() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(AudioManager::class.java)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        CarAudioOff.attach(app)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        app.sendBroadcast(Intent(CarAudioOff.STREAM_MUTE_CHANGED)
            .putExtra(CarAudioOff.STREAM_TYPE, AudioManager.STREAM_MUSIC))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarAudioOff.muted)
    }

    @Test fun explicitCarMuteSurvivesSystemUnmuteAndOnlyTheCarCanReleaseIt() {
        val app = RuntimeEnvironment.getApplication()
        CarAudioOff.attach(app)
        app.sendBroadcast(Intent(CarAudioOff.ACTION).putExtra("audio_off", true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(CarAudioOff.muted)
        assertEquals("car", CarAudioOff.muteSource)
        app.getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        CarAudioOff.refreshSystemMute()
        CarAudioOff.attach(app)
        assertTrue("A genuine car-key mute must survive reconciliation", CarAudioOff.muted)
        app.sendBroadcast(Intent(CarAudioOff.ACTION)) // Missing extras cannot unmute the car.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(CarAudioOff.muted)
        app.sendBroadcast(Intent(CarAudioOff.ACTION).putExtra("audio_off", false))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(CarAudioOff.muted)
    }
}
