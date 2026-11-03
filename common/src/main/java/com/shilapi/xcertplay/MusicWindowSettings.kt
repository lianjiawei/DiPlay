package com.shilapi.xcertplay

import android.content.Context

/**
 * The music window beside CarPlay: the side panel's strip, filled with the song instead of the clock.
 *
 * The strip is one of the view areas declared to the iPhone when a session starts, so switching this on
 * is saved for the next connection, like the rest of the layout settings. A declared strip can still
 * be shown or hidden during the session without changing the saved preference.
 *
 * The text size is the one thing that does not need a new session: it is applied to the card as soon as
 * the button on it is pressed, because the card re-measures itself from the strip it already has.
 */
internal object MusicWindowSettings {
    private const val PREFS = "diplay_music_window"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TEXT_SCALE = "text_scale"

    /**
     * Five numbered steps, picked on the settings page: small to largest. Every step grows the lines, the
     * progress bar and the keys together; the last two are for reading from the far seat, where the middle
     * steps left the lines short.
     */
    val textScales = floatArrayOf(0.82f, 1f, 1.22f, 1.45f, 1.7f)
    private const val DEFAULT_TEXT_SCALE = 1f

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /** Whether a session needs the side panel's strip: for its own panel or for the music window. */
    fun needsStrip(context: Context, musicEnabled: Boolean = enabled(context)): Boolean =
        musicEnabled || SidePanelSettings.enabled(context)

    /** How much of the card belongs to the lines and keys: 1 is the design size. */
    fun textScale(context: Context): Float {
        val saved = prefs(context).getFloat(KEY_TEXT_SCALE, DEFAULT_TEXT_SCALE)
        return textScales.minByOrNull { kotlin.math.abs(it - saved) } ?: DEFAULT_TEXT_SCALE
    }

    fun setTextScale(context: Context, scale: Float) =
        prefs(context).edit().putFloat(KEY_TEXT_SCALE, scale).apply()

    /** The step the settings page shows as chosen: 1 is the smallest, [textScales].size the largest. */
    fun textScaleStep(context: Context): Int =
        textScales.indexOfFirst { it == textScale(context) }.coerceAtLeast(0) + 1

    fun setTextScaleStep(context: Context, step: Int) {
        val index = (step - 1).coerceIn(0, textScales.lastIndex)
        setTextScale(context, textScales[index])
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
