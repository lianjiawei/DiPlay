package com.shilapi.xcertplay

import android.view.View

/** Presentation only. Caller validates the existing session/generation before supplying a frame. */
internal class FirstFramePresentation(private val waiting: View, private val badge: View) {
    private var delivered = false

    fun waiting() {
        if (waiting.visibility == View.VISIBLE && !delivered) return
        delivered = false
        badge.animate().cancel()
        badge.visibility = View.GONE
        badge.alpha = 1f
        waiting.visibility = View.VISIBLE
    }

    fun firstFrame(animate: Boolean) {
        if (delivered) return
        delivered = true
        // The noninteractive badge is separate: an opaque preparation screen never covers video during the fade.
        waiting.visibility = View.GONE
        badge.animate().cancel()
        if (!animate || !badge.isAttachedToWindow || badge.windowVisibility != View.VISIBLE) {
            badge.visibility = View.GONE
            return
        }
        badge.alpha = 1f
        badge.visibility = View.VISIBLE
        badge.animate().alpha(0f).setDuration(200L).withEndAction {
            if (delivered) badge.visibility = View.GONE
        }.start()
    }

    fun finish() {
        badge.animate().cancel()
        badge.visibility = View.GONE
    }
}
