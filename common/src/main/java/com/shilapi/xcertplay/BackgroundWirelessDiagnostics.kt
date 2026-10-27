package com.shilapi.xcertplay

import android.content.Context

/** Latest state only; never records phone identifiers or connection payloads. */
internal object BackgroundWirelessDiagnostics {
    private const val PREFS = "diplay_background_wireless_diagnostics"
    fun record(context: Context, state: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("state", state).putLong("at", System.currentTimeMillis()).apply()
    }
    fun report(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return "Background wireless: state=${prefs.getString("state", "not_started")} " +
            "atEpochMs=${prefs.getLong("at", 0)}; activity request does not confirm visibility."
    }
}
