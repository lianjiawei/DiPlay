package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaInterruptionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var listener: AudioManager.OnAudioFocusChangeListener
    @Before fun setUp() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller = mock(CarPlayController::class.java)
        sink = AndroidMediaSink()
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        CarPlayBackgroundSession.store(controller, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        listener = ReflectionHelpers.getField(request, "listener")
        clearInvocations(controller)
    }
    @After fun cleanup() {
        CarPlayMediaKeys.detach(controller)
        sink.close()
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
    }
    @Test fun briefFocusBlipMustNotSendPlayWhenNoPauseWasSent() {
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY)
    }
    @Test fun detachedControllerMustNotReceiveDelayedPlay() {
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY)
    }
    @Test fun renewedFocusLossMustCancelPreviouslyScheduledPlay() {
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun genuinePauseResumesOnceAfterTheLatestInterruptionEnds() {
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(controller, times(1)).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun userPauseCancelsTheScheduledAssistantResume() {
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        val callback = ReflectionHelpers.getField<android.media.session.MediaSession.Callback>(CarPlayMediaKeys, "callback")
        callback.onPause()
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun rejectedPauseDoesNotSchedulePlay() {
        `when`(controller.sendMediaButton(CarPlayMediaButton.PAUSE)).thenReturn(false)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        verify(controller, never()).sendMediaButton(CarPlayMediaButton.PLAY)
    }

    @Test fun gainWhilePauseIsBeingAcceptedStillRestoresTheMusic() {
        doAnswer {
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            true
        }.`when`(controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        verify(controller).sendMediaButton(CarPlayMediaButton.PAUSE)
        verify(controller).sendMediaButton(CarPlayMediaButton.PLAY)
    }
}
