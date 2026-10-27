package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Starts the watcher for other apps' calls when that setting is on, even without opening DiPlay.
        PhoneAudioRoute.attach(context)
        val launchEnabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        StartupDiagnosticSnapshot.received(context, launchEnabled)
        // Invalidate any prior boot's staged wake even when boot launch is disabled.
        if (AirPlayPersistence.loadBackgroundStartOnBoot(context)) BackgroundWirelessWake.cancel(context)
        if (!launchEnabled) return
        if (AirPlayPersistence.loadBackgroundStartOnBoot(context) && CarPlayBackgroundSession.hasSession()) {
            StartupDiagnosticSnapshot.sessionAlreadyRunning(context)
            return
        }

        // Keep the legacy entry completely unchanged unless the staged background mode is opted
        // into. USB attach already has a system Activity entry, so waiting needs no permanent
        // receiver or service. A phone attached before boot must be scanned because Android may
        // not replay an attach event.
        if (AirPlayPersistence.loadBackgroundStartOnBoot(context) &&
            !(DiPlayPreferences.autoConnect(context) && PreattachedIphone.isAttached(context))) {
            StartupDiagnosticSnapshot.waitingInBackground(context)
            BackgroundWirelessWake.armForCurrentBoot(context)
            if (BackgroundWirelessWake.isArmed(context)) BackgroundWirelessProbeService.startWaiting(context)
            else if (DiPlayPreferences.autoConnect(context) && DiPlayPreferences.autoConnectWireless(context)) {
                BackgroundWirelessProbeService.notifyNeedsSetup(context)
            }
            return
        }

        val launch = Intent(context, DiPlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            context.startActivity(launch)
            StartupDiagnosticSnapshot.launchResult(context)
        } catch (error: RuntimeException) {
            StartupDiagnosticSnapshot.launchResult(context, error)
            Log.w(TAG, "Boot auto-start could not launch DiPlayActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
