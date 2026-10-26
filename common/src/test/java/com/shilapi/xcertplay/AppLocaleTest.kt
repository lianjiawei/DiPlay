package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import com.shilapi.xcertplay.host.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @Test fun onlySimplifiedChineseAndEnglishAreSelectable() {
        assertEquals(listOf(AppLocale.SIMPLIFIED_CHINESE, AppLocale.ENGLISH), AppLocale.ALL)
        assertEquals("简体中文", AppLocale.displayName(context, AppLocale.SIMPLIFIED_CHINESE))
        assertEquals("English", AppLocale.displayName(context, AppLocale.ENGLISH))
    }

    @Test fun freshInstallDefaultsToSimplifiedChinese() {
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        assertSame(context, AppLocale.wrap(context))
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
    }

    @Test fun pickerAndPlatformSettingsShareTheTwoSupportedChoices() {
        AppLocale.save(context, AppLocale.ENGLISH)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.ENGLISH, AppLocale.preference(context))
        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
    }

    @Test fun oldUnsupportedPreferenceMigratesToSimplifiedChinese() {
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "fr").putBoolean("app_language_platform_migrated", false).commit()
        AppLocale.wrap(context)
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
    }

    @Test fun oldUnsupportedPlatformLocaleMigratesToSimplifiedChinese() {
        manager.applicationLocales = LocaleList.forLanguageTags("fr")
        AppLocale.wrap(context)
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
    }

    @Test fun existingSupportedPlatformChoiceWinsOverAnOldPreference() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "fr").putBoolean("app_language_platform_migrated", false).commit()
        manager.applicationLocales = LocaleList.forLanguageTags("en")
        AppLocale.wrap(context)
        assertEquals("en", manager.applicationLocales.toLanguageTags())
        assertEquals(AppLocale.ENGLISH, AppLocale.preference(context))
    }

    @Test @Config(sdk = [28, 32])
    fun olderAndroidWrapsBothSupportedLanguages() {
        AppLocale.save(context, AppLocale.ENGLISH)
        var wrapped = AppLocale.wrap(context)
        assertEquals(Locale.ENGLISH, wrapped.resources.configuration.locales[0])
        assertEquals("App language", wrapped.getString(R.string.language_app_language))

        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        wrapped = AppLocale.wrap(context)
        assertEquals(Locale.SIMPLIFIED_CHINESE, wrapped.resources.configuration.locales[0])
        assertEquals("应用语言", wrapped.getString(R.string.language_app_language))
    }

    @Test @Config(sdk = [28, 32])
    fun oldUnsupportedPreferenceDefaultsToSimplifiedChineseOnOlderAndroid() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "fr").commit()
        val wrapped = AppLocale.wrap(context)
        assertEquals(Locale.SIMPLIFIED_CHINESE, wrapped.resources.configuration.locales[0])
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
    }
}
