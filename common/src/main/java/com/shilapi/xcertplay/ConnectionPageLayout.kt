package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Dimensions shared by the full-page home and connecting surfaces, independent of connection state. */
internal object ConnectionPageLayout {
    const val RING_DP = 112
    const val CONTENT_GAP_DP = 8
    const val PHONE_DP = 48
    const val ACTION_MAX_LINES = 2
    const val SHORT_BODY_DP = 200
    const val COMPACT_BODY_DP = 240
    const val COMPACT_GAP_DP = 4
    const val COMPACT_HEADLINE_SP = 20f
    const val MIN_READING_WIDTH_DP = 432

    fun contentWidth(safeWidth: Int, bodyHeight: Int, landscape: Boolean, density: Float): Int {
        val innerWidth = (safeWidth-ReferenceHomeMetrics.SIDE_DP*2*density).roundToInt().coerceAtLeast(1)
        if (!landscape || bodyHeight < SHORT_BODY_DP*density) return innerWidth
        return (safeWidth*.6f-ReferenceHomeMetrics.SIDE_DP*2*density).roundToInt()
            .coerceAtLeast((MIN_READING_WIDTH_DP*density).roundToInt()).coerceAtMost(innerWidth)
    }

    fun actionWidth(innerWidth: Int, landscape: Boolean, density: Float): Int =
        (if (landscape) innerWidth*.287f else (innerWidth-ReferenceHomeMetrics.HERO_FOOTER_GAP_DP*density)/2)
            .toInt().coerceAtLeast(1)

    const val COMPACT_ACTION_WIDTH_DP = 160
    private const val MIN_ACTION_TEXT_SP = 12
    private const val ACTION_SIDE_PAD_DP = 16
    private const val ACTION_VERTICAL_PAD_DP = 8
    private const val ACTION_ICON_RESERVE_DP = 28
    data class ActionMetrics(val height: Int, val textSp: Int)
    private var actionKey = ""
    private var cachedActions: ActionMetrics? = null

    fun compactActions(context: Context, width: Int) =
        width < COMPACT_ACTION_WIDTH_DP * context.resources.displayMetrics.density *
            context.resources.configuration.fontScale.coerceAtLeast(1f)

    fun actionHeight(context: Context, width: Int): Int = actionMetrics(context, width).height

    fun actionMetrics(context: Context, width: Int): ActionMetrics {
        val resources = context.resources
        val metrics = resources.displayMetrics
        val density = metrics.density
        val key = "$width/${metrics.densityDpi}/${resources.configuration.fontScale}/${resources.configuration.locales.toLanguageTags()}/${resources.configuration.layoutDirection}"
        cachedActions?.takeIf { actionKey == key }?.let { return it }
        val compact = compactActions(context, width)
        val compactConnectionModes = setOf(R.string.home_connection_mode_wireless_short, R.string.home_connection_mode_usb_short)
        // The footer always uses the concise transport name. The full wording remains its
        // accessibility description and must never make this fixed-width action wrap.
        val connectionModes = compactConnectionModes
        val icons = connectionModes + setOf(R.string.home_cancel_connection, R.string.home_disconnect_carplay, R.string.settings)
        val labels = icons + setOf(R.string.ui_glass_connect_carplay, R.string.open_carplay,
            R.string.ui_glass_connecting_action,
            if (compact) R.string.ui_glass_retry_short else R.string.retry_carplay_connection,
            if (compact) R.string.ui_glass_wifi_reset_short else R.string.reset_carplay_wi_fi)
        val measure = android.widget.Button(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            isAllCaps = false
            maxLines = ACTION_MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = null
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = 0
            minHeight = (ReferenceHomeMetrics.ACTION_HEIGHT_DP*density).roundToInt()
            layoutDirection = resources.configuration.layoutDirection
        }
        for (sp in UiTypography.ACTION_SP downTo MIN_ACTION_TEXT_SP) {
            measure.textSize = sp.toFloat()
            var height = 0
            var fits = true
            for (id in labels) {
                measure.text = context.getString(id)
                val iconReserve = when {
                    id in icons -> ACTION_ICON_RESERVE_DP
                    else -> 0
                }
                measure.setPadding(((ACTION_SIDE_PAD_DP + iconReserve)*density).roundToInt(),
                    (ACTION_VERTICAL_PAD_DP*density).roundToInt(), (ACTION_SIDE_PAD_DP*density).roundToInt(), (ACTION_VERTICAL_PAD_DP*density).roundToInt())
                measure.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                height = maxOf(height, measure.measuredHeight)
                val layout = measure.layout
                if (layout != null && (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) fits = false
            }
            if (fits || sp == MIN_ACTION_TEXT_SP) return ActionMetrics(height, sp).also {
                actionKey = key
                cachedActions = it
            }
        }
        error("No home action typography")
    }

    const val CONNECTION_MODE_ACTION_TAG = "home_connection_mode_action"
}
