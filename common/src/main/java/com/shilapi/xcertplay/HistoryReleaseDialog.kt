package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateRelease
import com.shilapi.xcertplay.update.UpdateReleaseNotes

/** Keeps long historical release notes scrollable without moving the actions off screen. */
internal class HistoryReleaseDialog(
    private val context: Context,
    private val release: UpdateRelease,
    onDownload: () -> Unit,
) {
    internal val messageView = TextView(context.appDialogContext()).apply {
        textSize = UiTypography.BODY_SP.toFloat()
        setTextColor(DiPlayPalette.DARK.secondaryText)
        text = VehicleBranding.text(context, R.string.update_history_confirm, release.apkName) +
            "\n\n" + VehicleBranding.text(context, R.string.update_history_notes_title) + "\n" +
            UpdateReleaseNotes.plainText(release.releaseNotes)
                .ifBlank { VehicleBranding.text(context, R.string.update_history_notes_missing) }
        setPadding(0, 0, 0, dp(BLOCK_GAP_DP))
    }
    internal val messageScroll: ScrollView = BoundedScrollView(
        context.appDialogContext(),
        minOf(dp(MESSAGE_MAX_HEIGHT_DP),
            (context.resources.configuration.screenHeightDp * context.resources.displayMetrics.density *
                MESSAGE_HEIGHT_SHARE).toInt()),
    ).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        addView(messageView, LinearLayout.LayoutParams(-1, -2))
    }
    private val content = LinearLayout(context.appDialogContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), 0)
        addView(messageScroll, LinearLayout.LayoutParams(-1, -2))
    }
    internal val dialog: AlertDialog = context.appDialogBuilder()
        .setTitle(release.tagName)
        .setView(content)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.update_history_action) { _, _ -> onDownload() }
        .create()

    fun show() {
        dialog.show()
        messageScroll.post { messageScroll.scrollTo(0, 0) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val SIDE_PADDING_DP = 24
        const val BLOCK_GAP_DP = 16
        const val MESSAGE_MAX_HEIGHT_DP = 420
        const val MESSAGE_HEIGHT_SHARE = .52f
    }

    private class BoundedScrollView(context: Context, private val maximumHeight: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maximumHeight
            super.onMeasure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(minOf(available, maximumHeight), MeasureSpec.AT_MOST))
        }
    }
}
