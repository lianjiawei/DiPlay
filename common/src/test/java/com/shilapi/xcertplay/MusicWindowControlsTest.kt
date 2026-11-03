package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.ViewGroup
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 28, 33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWindowControlsTest {
    @Test fun buttonsForwardOnceToTheCurrentPhone() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying(title = "Song", playing = false))
        `when`(controller.sendMediaButton(anyInt())).thenReturn(true)
        try {
            CarPlayMediaKeys.attach(activity.get(), controller)
            shadowOf(Looper.getMainLooper()).idle()
            val window = CarPlayMusicWindow(activity.get())
            activity.get().setContentView(window, ViewGroup.LayoutParams(600, 900))
            shadowOf(Looper.getMainLooper()).idle()
            val buttons = window.transportButtons
            buttons[0].performClick()
            buttons[1].performClick()
            buttons[2].performClick()
            verify(controller).sendMediaButton(CarPlayMediaButton.PREVIOUS)
            verify(controller).sendMediaButton(CarPlayMediaButton.PLAY_PAUSE)
            verify(controller).sendMediaButton(CarPlayMediaButton.NEXT)
            assertEquals(activity.get().getString(R.string.music_window_pause), buttons[1].contentDescription)
        } finally {
            CarPlayMediaKeys.detach(controller)
            activity.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
