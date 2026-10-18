package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.text.BidiFormatter
import android.text.TextDirectionHeuristics
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateRelease
import com.shilapi.xcertplay.update.UpdateReleaseNotes

internal enum class UpdatePromptStage { OFFER, DOWNLOADING, VERIFYING, READY, FAILED }

/** One window follows the explicit download through verification to the system installer. */
internal class UpdatePromptDialog(
    private val context: Context,
    onDownload: (UpdateRelease) -> Unit,
    onInstall: () -> Unit,
    onHide: () -> Unit,
) {
    private var stage = UpdatePromptStage.OFFER
    private lateinit var displayedRelease: UpdateRelease
    internal val progress = ProgressBar(context.appDialogContext(), null, android.R.attr.progressBarStyleHorizontal)
    private val progressPanel = LinearLayout(context.appDialogContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(SIDE_PADDING_DP), 0, dp(SIDE_PADDING_DP), dp(BLOCK_GAP_DP))
        addView(progress, LinearLayout.LayoutParams(-1, dp(PROGRESS_HEIGHT_DP)))
    }
    internal val dialog: AlertDialog = context.appDialogBuilder()
        .setView(progressPanel)
        .setPositiveButton(R.string.update_download, null)
        .setNegativeButton(R.string.update_later) { _, _ -> onHide() }
        .create().apply {
            setOnCancelListener { onHide() }
            setOnShowListener {
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    when (stage) {
                        UpdatePromptStage.OFFER, UpdatePromptStage.FAILED -> onDownload(displayedRelease)
                        UpdatePromptStage.READY -> onInstall()
                        else -> Unit
                    }
                }
            }
        }

    val isShowing: Boolean get() = dialog.isShowing
    fun dismiss() = dialog.dismiss()

    fun show(release: UpdateRelease, state: UpdatePromptStage, percent: Int?, failure: String?) {
        update(release, state, percent, failure)
        dialog.show()
        update(release, state, percent, failure)
    }

    fun update(release: UpdateRelease, state: UpdatePromptStage, percent: Int?, failure: String?) {
        displayedRelease = release
        stage = state
        val version = BidiFormatter.getInstance(context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            .unicodeWrap(release.tagName, TextDirectionHeuristics.LTR)
        dialog.setTitle(text(R.string.update_prompt_title, version))
        dialog.setMessage(when (state) {
            UpdatePromptStage.OFFER -> text(R.string.update_history_notes_title) + "\n\n" +
                UpdateReleaseNotes.plainText(release.releaseNotes).ifBlank { text(R.string.update_history_notes_missing) }
            UpdatePromptStage.DOWNLOADING -> if (percent == null) text(R.string.update_downloading_unknown)
                else text(R.string.update_downloading, percent.coerceIn(0, 100))
            UpdatePromptStage.VERIFYING -> text(R.string.update_verifying)
            UpdatePromptStage.READY -> text(R.string.update_ready_to_install)
            UpdatePromptStage.FAILED -> text(R.string.update_failed) + "\n\n" + failure.orEmpty()
        })
        progressPanel.visibility = if (state == UpdatePromptStage.OFFER || state == UpdatePromptStage.FAILED)
            View.GONE else View.VISIBLE
        progress.isIndeterminate = state == UpdatePromptStage.VERIFYING ||
            state == UpdatePromptStage.DOWNLOADING && percent == null
        progress.progress = if (state == UpdatePromptStage.READY) 100 else (percent ?: 0).coerceIn(0, 100)
        progress.contentDescription = when (state) {
            UpdatePromptStage.VERIFYING -> text(R.string.update_verifying)
            UpdatePromptStage.READY -> text(R.string.update_history_saved_title)
            else -> if (percent == null) text(R.string.update_downloading_unknown)
                else text(R.string.update_downloading, percent.coerceIn(0, 100))
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            visibility = if (state == UpdatePromptStage.DOWNLOADING || state == UpdatePromptStage.VERIFYING)
                View.GONE else View.VISIBLE
            text = text(when (state) {
                UpdatePromptStage.READY -> R.string.update_install_now
                UpdatePromptStage.FAILED -> R.string.update_retry
                else -> R.string.update_download
            })
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = text(
            if (state == UpdatePromptStage.DOWNLOADING || state == UpdatePromptStage.VERIFYING)
                R.string.update_hide else R.string.update_later,
        )
    }

    private fun text(id: Int, vararg args: Any) = VehicleBranding.text(context, id, *args)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    private companion object {
        const val SIDE_PADDING_DP = 24
        const val BLOCK_GAP_DP = 16
        const val PROGRESS_HEIGHT_DP = 12
    }
}
