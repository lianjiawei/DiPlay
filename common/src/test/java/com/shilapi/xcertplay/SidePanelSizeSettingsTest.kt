package com.shilapi.xcertplay

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
class SidePanelSizeSettingsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay_side_panel", Context.MODE_PRIVATE)

    @Before fun clearBefore() { prefs.edit().clear().commit() }
    @After fun clearAfter() { prefs.edit().clear().commit() }

    @Test fun legacyWidthsRemainTheSameWhenUpgrading() {
        assertEquals(80, SidePanelSettings.share(context, music = true))
        for (old in listOf(4, 3, 2)) {
            prefs.edit().clear().putInt("carplay_sixths", old).commit()
            assertEquals(old * 20, SidePanelSettings.share(context, music = true))
            assertEquals(old * 20, SidePanelSettings.share(context, music = false))
        }
    }

    @Test fun previousTwelfthsWidthsRemainTheSameWhenUpgrading() {
        for (old in listOf(8, 6, 4, 9, 10)) {
            prefs.edit().clear().putInt("carplay_sixths", 3).putInt("carplay_twelfths", old).commit()
            assertEquals(old * 10, SidePanelSettings.share(context, music = true))
            assertEquals(if (old in listOf(8, 6, 4)) old * 10 else 60,
                SidePanelSettings.share(context, music = false))
        }
    }

    @Test fun narrowerMusicWidthsPersistWithoutForcingThemOnTheClockPanel() {
        prefs.edit().putInt("carplay_sixths", 3).commit()
        for (size in listOf(90, 100, 105, 96, 93, 87, 84, 72)) {
            SidePanelSettings.setShare(context, size)
            assertEquals(size, SidePanelSettings.share(context, music = true))
            assertEquals(60, SidePanelSettings.share(context, music = false))
        }
        SidePanelSettings.setShare(context, 80)
        assertEquals(4, prefs.getInt("carplay_sixths", -1))
        assertEquals(8, prefs.getInt("carplay_twelfths", -1))
        assertEquals(80, SidePanelSettings.share(context, music = false))
    }

    @Test fun invalidValuesFallBackWithoutDiscardingTheLegacyWidth() {
        prefs.edit().putInt("carplay_sixths", 2).putInt("carplay_twelfths", 17).commit()
        assertEquals(40, SidePanelSettings.share(context, music = true))
        SidePanelSettings.setShare(context, 110)
        assertEquals(40, SidePanelSettings.share(context, music = true))
        prefs.edit().putInt("carplay_twelfths", 9).putInt("carplay_120ths", -1).commit()
        assertEquals(90, SidePanelSettings.share(context, music = true))
        assertEquals(40, SidePanelSettings.share(context, music = false))
    }
}
