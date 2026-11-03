package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.lincoln.LincolnClusterOutput
import com.shilapi.xcertplay.lincoln.LincolnClusterProtocol
import com.shilapi.xcertplay.lincoln.LincolnClusterSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 29], qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWindowClusterIsolationTest {
    @Test fun lyricsKeepTheirExistingWireLengthForAllRoadAndMusicWindowChoices() {
        val app = RuntimeEnvironment.getApplication()
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val root = FrameLayout(activity.get())
        activity.get().setContentView(root)
        val road = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸"
        val lyric = "词".repeat(17) + "🎵" + "继续唱"
        val expectedLyric = "词".repeat(17) + "🎵"
        try {
            LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
            LincolnClusterSettings.setNavigation(app, true)
            LincolnClusterSettings.setEnhancedNavigation(app, false)
            LincolnClusterSettings.setMusic(app, true)
            LincolnClusterSettings.setRemaining(app, false)
            CarPlayGlance.setConnected(false)
            for (musicWindow in listOf(false, true)) for (rotateRoad in listOf(false, true)) {
                MusicWindowSettings.setEnabled(app, musicWindow)
                LincolnClusterSettings.setRotateRoad(app, rotateRoad)
                root.removeAllViews()
                if (musicWindow) {
                    val card = CarPlayMusicWindow(activity.get())
                    root.addView(card, FrameLayout.LayoutParams(400, 600))
                    ClusterMusicState.publish(CarPlayNowPlaying(title = lyric, playing = true), null)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(lyric, card.titleTextView.text.toString())
                }

                LincolnClusterOutput.detach()
                LincolnClusterOutput.attach(app)
                shadowOf(app).clearBroadcastIntents()
                val snapshot = CarPlayGlance.Snapshot(connected = true, routeActive = true,
                    maneuverType = 11, maneuverIndex = 7, distanceMeters = 400, road = road,
                    songTitle = lyric, song = lyric, playing = true, distanceRevision = 1)
                LincolnClusterOutput.javaClass.getDeclaredMethod("onSnapshot", CarPlayGlance.Snapshot::class.java)
                    .apply { isAccessible = true }.invoke(LincolnClusterOutput, snapshot)
                val broadcasts = shadowOf(app).broadcastIntents
                val media = broadcasts.last { it.action == LincolnClusterProtocol.ACTION_MEDIA_INFO }
                val nav = broadcasts.last { it.action == LincolnClusterProtocol.ACTION_NAV_INFO }
                assertEquals("music=$musicWindow rotate=$rotateRoad", expectedLyric,
                    media.getStringExtra(LincolnClusterProtocol.EXTRA_TITLE))
                assertEquals(if (rotateRoad) "一二三四五六七..." else road.take(19),
                    nav.getStringExtra(LincolnClusterProtocol.EXTRA_ROAD))
            }
        } finally {
            LincolnClusterOutput.detach()
            LincolnClusterSettings.setRotateRoad(app, false)
            LincolnClusterSettings.setNavigation(app, false)
            LincolnClusterSettings.setMusic(app, false)
            MusicWindowSettings.setEnabled(app, false)
            CarPlayGlance.setConnected(false)
            activity.pause().stop().destroy()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
