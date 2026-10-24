package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.graphics.drawable.GradientDrawable
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppDialogsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun dialogThemesStayDarkForLegacyAppearancePreferences() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)
        assertDialogColors(context.appDialogContext(), DiPlayPalette.DARK)

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        assertDialogColors(context.appDialogContext(), DiPlayPalette.DARK)
    }

    @Test fun anExistingDialogContextKeepsItsAppearanceAfterThePreferenceChanges() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        val existingContext = context.appDialogBuilder().context

        AirPlayPersistence.saveAppAppearance(context, AppAppearance.DARK)

        assertDialogColors(existingContext, DiPlayPalette.DARK)
        assertDialogColors(context.appDialogBuilder().context, DiPlayPalette.DARK)
    }

    @Test fun wrappedContextsAlsoUseDarkDialogs() {
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        assertDialogColors(ContextWrapper(context).appDialogContext(), DiPlayPalette.DARK)
    }

    /** Android 9 and older drop a literal colour here, and dialogs showed through (#640, #617). */
    @Test fun windowBackgroundsAreResourcesThatAndroid9CanLoad() {
        for (theme in listOf(
            R.style.Theme_Xcertplay,
            R.style.Theme_Xcertplay_Dialog_Dark,
        )) {
            val attributes = android.view.ContextThemeWrapper(context, theme).theme
                .obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            try {
                // PhoneWindow.generateLayout() on API 28 uses exactly this lookup.
                assertNotEquals("theme $theme", 0, attributes.getResourceId(0, 0))
            } finally {
                attributes.recycle()
            }
        }
    }

    private fun assertDialogColors(themedContext: android.content.Context, palette: DiPlayPalette) {
        val attributes = themedContext.theme.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorAccent, android.R.attr.windowBackground),
        )
        try {
            assertEquals(palette.accent, attributes.getColor(0, 0))
            assertEquals(R.drawable.glass_dialog_dark, attributes.getResourceId(1, 0))
            assertTrue(attributes.getDrawable(1) is GradientDrawable)
        } finally {
            attributes.recycle()
        }
    }
}
