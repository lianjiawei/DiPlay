package com.shilapi.xcertplay

import android.content.Context

/** Legacy backup values; old preferences remain readable, but app chrome is always dark. */
enum class AppAppearance(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    AUTO("auto");

    companion object {
        fun fromKey(key: String?): AppAppearance = entries.firstOrNull { it.key == key } ?: DARK
    }
}

/** App-owned screens and dialogs use one palette; the iPhone's day/night policy stays separate. */
internal fun Context.resolveAppNightNow(): Boolean = true
