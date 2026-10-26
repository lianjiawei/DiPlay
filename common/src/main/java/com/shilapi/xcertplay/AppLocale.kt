// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** The two product languages, persisted through platform app locales or an older-Android override. */
object AppLocale {
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"

    val ALL = listOf(SIMPLIFIED_CHINESE, ENGLISH)

    private const val PREFS = "diplay"
    private const val KEY_LANGUAGE = "app_language"

    private const val KEY_MIGRATED = "app_language_platform_migrated"

    fun preference(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SIMPLIFIED_CHINESE else normalizeLanguage(locales[0].language)
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SIMPLIFIED_CHINESE).normalizeKey()
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList(locale(language))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language).apply()
        }
    }

    /** On Android 13+, migrate old/unsupported choices and keep the OS locale on a supported value. */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(LocaleManager::class.java)
            val current = manager.applicationLocales
            val next = if (current.isEmpty) {
                prefs.getString(KEY_LANGUAGE, SIMPLIFIED_CHINESE).normalizeKey()
            } else {
                normalizeLanguage(current[0].language)
            }
            val target = locale(next)
            if (current.isEmpty || current.size() != 1 || current[0] != target) {
                manager.applicationLocales = LocaleList(target)
            }
            if (!prefs.getBoolean(KEY_MIGRATED, false) || prefs.contains(KEY_LANGUAGE)) {
                prefs.edit().putBoolean(KEY_MIGRATED, true).remove(KEY_LANGUAGE).apply()
            }
            return context
        }
        val locale = locale(preference(context)) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * Before Android 13, some head units run apps in a compatibility mode that puts the system language
     * back into the activity's resources after [wrap]. Writes the chosen language again; returns true
     * if it had to.
     */
    @Suppress("DEPRECATION")
    fun enforce(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) return false
        val locale = locale(preference(context))
        val resources = context.resources
        if (resources.configuration.locales[0] == locale) return false
        val configuration = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return true
    }

    fun showPicker(activity: Activity) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        activity.appDialogBuilder()
            .setTitle(R.string.language_app_language)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    // LocaleManager recreates activities itself on Android 13+.
                    if (Build.VERSION.SDK_INT < 33) activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** Names stay in their native form so either supported language can always be recognized. */
    fun displayName(@Suppress("UNUSED_PARAMETER") context: Context, language: String): String = when (language) {
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> "简体中文"
        else -> language
    }

    private fun locale(language: String): Locale = when (language) {
        ENGLISH -> Locale.ENGLISH
        else -> Locale.SIMPLIFIED_CHINESE
    }

    private fun String?.normalizeKey(): String = if (this == ENGLISH) ENGLISH else SIMPLIFIED_CHINESE

    private fun normalizeLanguage(language: String): String = language.normalizeKey()
}
