package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.provider.Settings
import java.util.UUID

/** Eligibility for this boot's background wireless wait. */
internal object BackgroundWirelessWake {
    private const val PREFS = "diplay_background_wireless_wake"
    private const val BOOT_COUNT = "boot_count"
    private const val ARMED = "armed"
    private const val TOKEN = "token"
    private const val TARGET = "target"
    const val EXTRA_TOKEN = "com.corsairplay.BACKGROUND_BOOT_WIRELESS_TOKEN"
    const val PENDING_INTENT_REQUEST = 31
    private val stateLock = Any()

    private fun currentBoot(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private fun configured(context: Context): Boolean =
        AirPlayPersistence.loadAutoStartOnBoot(context) &&
            AirPlayPersistence.loadBackgroundStartOnBoot(context) &&
            DiPlayPreferences.autoConnect(context) &&
            DiPlayPreferences.autoConnectWireless(context) &&
            DiPlayPreferences.phoneAddress(context) != null

    /** Each boot supersedes any previous wait; unknown boot identity fails closed. */
    fun armForCurrentBoot(context: Context) {
        val boot = currentBoot(context)
        val previous = synchronized(stateLock) {
            val eligible = boot >= 0 && configured(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(TOKEN, null)
            prefs.edit()
                .putBoolean(ARMED, eligible)
                .putInt(BOOT_COUNT, boot)
                .putString(TOKEN, if (eligible) UUID.randomUUID().toString() else null)
                .putString(TARGET, if (eligible) DiPlayPreferences.phoneAddress(context) else null)
                .apply()
            old
        }
        if (previous != null) {
            BackgroundWirelessProbeService.stopWaiting(context, clearHandoff = true, expectedToken = previous)
            cancelPendingIntent(context, previous)
        }
    }

    fun isArmed(context: Context): Boolean {
        if (!configured(context)) return false
        val boot = currentBoot(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return boot >= 0 && prefs.getBoolean(ARMED, false) &&
            prefs.getInt(BOOT_COUNT, -1) == boot &&
            !prefs.getString(TOKEN, null).isNullOrBlank() &&
            prefs.getString(TARGET, null).equals(DiPlayPreferences.phoneAddress(context), true)
    }

    fun token(context: Context): String? = synchronized(stateLock) {
        if (isArmed(context)) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, null) else null
    }

    fun matches(context: Context, token: String?): Boolean = token != null && this.token(context) == token

    fun matches(context: Context, intent: Intent): Boolean = matches(context, intent.getStringExtra(EXTRA_TOKEN))

    fun launchIntent(context: Context, token: String): Intent = Intent(context, DiPlayActivity::class.java).apply {
        // PendingIntent identity includes data, but ignores extras. Keep each wait independent.
        data = Uri.parse("diplay-background://wireless/$token")
        putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)
        putExtra(PhoneBluetoothReceiver.EXTRA_BACKGROUND_BOOT_WIRELESS, true)
        putExtra(EXTRA_TOKEN, token)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    fun cancel(context: Context) {
        val previous = synchronized(stateLock) { disarm(context) }
        BackgroundWirelessProbeService.stopWaiting(context, clearHandoff = true)
        cancelPendingIntent(context, previous)
    }

    /** Stop probing but preserve the successful socket for the controller to claim. */
    fun consume(context: Context, token: String?): Boolean {
        synchronized(stateLock) {
            if (!matches(context, token)) return false
            disarm(context)
        }
        BackgroundWirelessProbeService.stopWaiting(context, clearHandoff = false, expectedToken = token)
        cancelPendingIntent(context, token)
        return true
    }

    fun cancelIfCurrent(context: Context, token: String?) {
        synchronized(stateLock) {
            if (!matches(context, token)) return
            disarm(context)
        }
        BackgroundWirelessProbeService.stopWaiting(context, clearHandoff = true, expectedToken = token)
        cancelPendingIntent(context, token)
    }

    private fun disarm(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(TOKEN, null)
        prefs.edit()
            .putBoolean(ARMED, false).remove(TOKEN).remove(TARGET).apply()
        return previous
    }

    private fun cancelPendingIntent(context: Context, token: String?) {
        if (token == null) return
        PendingIntent.getActivity(context, PENDING_INTENT_REQUEST, launchIntent(context, token),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.cancel()
    }
}
