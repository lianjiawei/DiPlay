package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateRelease
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The catalog is published newest first. Keep the entire row clickable and the actions fixed. */
internal class TestReleaseListDialog(private val context: Context, releases: List<UpdateRelease>, onSelect: (UpdateRelease) -> Unit) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    internal val rows = LinearLayout(context.appDialogContext()).apply { orientation = LinearLayout.VERTICAL }
    internal val scroll = object : ScrollView(context.appDialogContext()) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = minOf(dp(420), (context.resources.displayMetrics.heightPixels * .48f).toInt())
            val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: max
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(max, available), MeasureSpec.AT_MOST))
        }
    }.apply { isVerticalScrollBarEnabled = true; addView(rows) }
    internal val dialog: AlertDialog

    init {
        releases.forEachIndexed { index, release ->
            if (index > 0) rows.addView(View(context).apply {
                setBackgroundColor(DiPlayPalette.DARK.outline)
                alpha = .45f
            }, LinearLayout.LayoutParams(-1, dp(1)))
            val row = LinearLayout(context.appDialogContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                minimumHeight = dp(64)
                isClickable = true; isFocusable = true
                background = RippleDrawable(ColorStateList.valueOf(DiPlayPalette.DARK.ripple), null, null)
                addView(TextView(context.appDialogContext()).apply {
                    text = displayName(context, release, index == 0)
                    textSize = UiTypography.BODY_SP.toFloat()
                    setTextColor(DiPlayPalette.DARK.primaryText)
                })
                addView(TextView(context.appDialogContext()).apply {
                    text = buildId(release.tagName)
                    textSize = UiTypography.CAPTION_SP.toFloat()
                    setTextColor(DiPlayPalette.DARK.secondaryText)
                })
                setOnClickListener { dialog.dismiss(); onSelect(release) }
            }
            rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = context.appDialogBuilder().setTitle(R.string.update_test_versions).setView(scroll)
            .setNegativeButton(android.R.string.cancel, null).create()
    }

    fun show() = dialog.show()

    companion object {
        private val buildPattern = Regex("test-(\\d{14})-([a-f0-9]{8})")
        private fun timestamp(tag: String): java.util.Date? {
            val match = buildPattern.matchEntire(tag) ?: return null
            val format = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
            }
            return runCatching { format.parse(match.groupValues[1]) }.getOrNull()
        }
        fun displayName(context: Context, release: UpdateRelease, latest: Boolean): String {
            val date = timestamp(release.tagName)?.let {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(it)
            } ?: release.tagName
            return if (latest) context.getString(R.string.update_test_latest, date) else date
        }
        private fun buildId(tag: String): String = buildPattern.matchEntire(tag)?.groupValues?.get(2) ?: tag
    }
}
