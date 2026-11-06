package com.shilapi.xcertplay

import android.content.Context

/**
 * Optional: CarPlay can move to two thirds of the main screen while DiPlay shows its own panel in the rest:
 * the right third of a landscape screen, a band at the bottom of a portrait one. The areas are declared
 * with the session's view areas ([CarPlayViewAreas]); the in-session menu shows and hides the panel. The
 * iPhone keeps streaming the whole frame and leaves the panel's part black; the panel is an Android view
 * over it, so CarPlay knows nothing about its contents.
 */
object SidePanelSettings {
    private const val PREFS = "diplay_side_panel"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Preserve the previous sixths/twelfths preferences when adding finer music-window sizes. */
    fun share(context: Context, music: Boolean): Int {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = preferences.getInt(KEY_SIXTHS, 4).takeIf { it in listOf(4, 3, 2) } ?: 4
        val oldShare = preferences.getInt(KEY_TWELFTHS, legacy * 2)
            .takeIf { it in listOf(8, 6, 4, 9, 10) }?.times(10) ?: legacy * 20
        val saved = preferences.getInt(KEY_SHARE, oldShare)
        val allowed = if (music) CarPlayViewAreas.MUSIC_PANEL_SHARES else CarPlayViewAreas.SIDE_PANEL_SHARES
        return saved.takeIf { it in allowed } ?: oldShare.takeIf { it in allowed } ?: legacy * 20
    }

    fun setShare(context: Context, share: Int) {
        if (share !in CarPlayViewAreas.MUSIC_PANEL_SHARES) return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SHARE, share)
        // Keep older builds and the non-music panel on their last compatible width.
        if (share in listOf(80, 60, 40, 90, 100)) edit.putInt(KEY_TWELFTHS, share / 10)
        if (share in CarPlayViewAreas.SIDE_PANEL_SHARES) edit.putInt(KEY_SIXTHS, share / 20)
        edit.apply()
    }

    private const val KEY_SIXTHS = "carplay_sixths"
    private const val KEY_TWELFTHS = "carplay_twelfths"
    private const val KEY_SHARE = "carplay_120ths"
}

/** What a touch that starts on the side panel's pull tab turns out to be. */
internal enum class SidePanelTabIntent { UNDECIDED, OPEN_PANEL, CARPLAY }

/**
 * A touch on the pull tab drags the panel out only when it first moves towards the inside of the screen by
 * at least [slop], and more than along the edge. Movement along the edge or back towards it belongs to CarPlay
 * under the tab (for example a list scrolled near the edge), and so does a tap or a long press.
 */
internal fun sidePanelTabIntent(inward: Float, along: Float, slop: Float): SidePanelTabIntent = when {
    inward >= slop && inward >= along -> SidePanelTabIntent.OPEN_PANEL
    along >= slop || -inward >= slop -> SidePanelTabIntent.CARPLAY
    else -> SidePanelTabIntent.UNDECIDED
}
