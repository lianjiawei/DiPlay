package com.shilapi.xcertplay.lincoln

import android.content.Context
import android.os.Build

/**
 * Switches for the Lincoln/Ford cluster output. Off by default; the output does nothing until the
 * driver enables it, and it stays disabled on a head unit whose cluster app is not present.
 */
object LincolnClusterSettings {
    private const val PREFS = "diplay_lincoln_cluster"
    private const val KEY_NAVIGATION = "navigation"
    private const val KEY_ENHANCED_NAVIGATION = "enhanced_navigation"
    private const val KEY_MUSIC = "music"
    private const val KEY_REMAINING = "remaining"
    private const val KEY_CLUSTER_PACKAGE = "cluster_package"

    /** On by default on the Lincoln/Ford hardware this fork targets; elsewhere it stays opt-in. */
    fun navigation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NAVIGATION, LincolnVehicle.isHardware())

    fun setNavigation(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_NAVIGATION, enabled).apply()

    /** Optional factory channels for cluster variants that do not consume the basic INFO packet. */
    fun enhancedNavigation(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENHANCED_NAVIGATION, false)

    fun setEnhancedNavigation(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENHANCED_NAVIGATION, enabled).apply()

    fun music(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUSIC, LincolnVehicle.isHardware())

    fun setMusic(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_MUSIC, enabled).apply()

    /** Experimental: also send total remaining distance/time, which the tested cluster may ignore. */
    fun remaining(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMAINING, LincolnVehicle.isHardware())

    fun setRemaining(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_REMAINING, enabled).apply()

    /** The cluster app to broadcast to; overridable so a probe can retarget a different firmware. */
    fun clusterPackage(context: Context): String =
        prefs(context).getString(KEY_CLUSTER_PACKAGE, null)
            ?.takeIf { it.isNotBlank() }
            ?: LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE

    fun setClusterPackage(context: Context, pkg: String) =
        prefs(context).edit().putString(KEY_CLUSTER_PACKAGE, pkg).apply()

    fun enabled(context: Context): Boolean = navigation(context) || music(context)

    /** True when the configured cluster app is installed, so the settings card can be shown. */
    fun available(context: Context): Boolean =
        installed(context, clusterPackage(context)) || clusterFirmware(context)

    private fun clusterFirmware(context: Context): Boolean =
        installed(context, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE) ||
            Build.MODEL.equals("MEK-MX8Q", ignoreCase = true) ||
            (Build.HARDWARE.equals("freescale", ignoreCase = true) && Build.BOARD.equals("MEK", ignoreCase = true))

    private fun installed(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
