package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.widget.TextView
import com.shilapi.xcertplay.host.R

/** Reserves the longest localized status before connecting, so routine status changes cannot reflow it. */
internal class StableConnectionTextView(context: Context, variants: IntArray) : TextView(context) {
    private val messages = variants.map { VehicleBranding.text(context, it) }
    private var reservedWidth = -1
    private var reservedTextSize = -1f
    private var reservedTypeface: Typeface? = null
    private var reservedDirection = -1
    private var reservedHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = (measuredWidth - compoundPaddingLeft - compoundPaddingRight).coerceAtLeast(1)
        if (width != reservedWidth || textSize != reservedTextSize || typeface != reservedTypeface ||
            layoutDirection != reservedDirection) {
            reservedWidth = width
            reservedTextSize = textSize
            reservedTypeface = typeface
            reservedDirection = layoutDirection
            reservedHeight = messages.maxOfOrNull { message ->
                StaticLayout.Builder.obtain(message, 0, message.length, paint, width)
                    .setIncludePad(includeFontPadding)
                    .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                    .setBreakStrategy(breakStrategy)
                    .setHyphenationFrequency(hyphenationFrequency)
                    .setTextDirection(if (layoutDirection == LAYOUT_DIRECTION_RTL)
                        TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(isFallbackLineSpacing) }
                    .build().height
            } ?: 0
        }
        val height = maxOf(measuredHeight, reservedHeight + compoundPaddingTop + compoundPaddingBottom)
        setMeasuredDimension(measuredWidth, resolveSizeAndState(height, heightMeasureSpec, 0))
    }
}

internal object ConnectionScreenCopy {
    private val conciseStages = mapOf(
        R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb to R.string.ui_connection_hint_usb_fallback,
        R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth to R.string.ui_connection_hint_wireless,
        R.string.bluetooth_connections_not_real to R.string.ui_connection_hint_usb_fallback,
        R.string.bluetooth_cannot_open_connections to R.string.ui_connection_hint_usb_fallback,
        R.string.a_previous_wi_fi_direct_connection_is_still_running_reset to R.string.ui_connection_hint_reset_wifi,
        R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and to R.string.ui_connection_hint_network,
        R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per to R.string.ui_connection_hint_permissions,
        R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p to R.string.ui_connection_hint_permissions,
        R.string.iphone_usb_mode_rejected to R.string.ui_connection_hint_usb,
        R.string.vpn_authorization_unavailable to R.string.ui_connection_hint_permissions,
    )

    fun summary(context: Context, message: String): String {
        val id = conciseStages.entries.firstOrNull { VehicleBranding.text(context, it.key) == message }?.value
        return id?.let { VehicleBranding.text(context, it) } ?: message
    }

    val stages = intArrayOf(
        R.string.getting_carplay_ready, R.string.connecting_to_your_iphone,
        R.string.opening_carplay, R.string.reconnecting_to_your_iphone,
        R.string.looking_for_your_paired_iphone, R.string.connect_your_iphone_with_a_usb_cable,
        R.string.connection_interrupted_retrying, R.string.allow_the_connection_permission_to_continue,
        R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb,
        R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth,
        R.string.bluetooth_connections_not_real, R.string.bluetooth_cannot_open_connections,
        R.string.a_previous_wi_fi_direct_connection_is_still_running_reset,
        R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and,
        R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per,
        R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p,
        R.string.turn_on_bluetooth_in_the_head_unit_s_settings_to_connect,
        R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect,
        R.string.iphone_usb_mode_rejected, R.string.vpn_authorization_unavailable,
        R.string.waiting_for_mfi_coprocessor, R.string.requesting_mfi_usb_permission,
        R.string.ui_glass_video_preparing, R.string.ui_connection_waiting_network,
        R.string.ui_connection_network_timeout,
    ).map { conciseStages[it] ?: it }.distinct().toIntArray()
    val hints = intArrayOf(
        R.string.ui_connection_hint_usb, R.string.ui_connection_hint_same_wifi,
        R.string.ui_connection_hint_wireless, R.string.ui_connection_hint_reset_wifi,
        R.string.ui_connection_hint_permissions, R.string.ui_connection_hint_usb_fallback,
        R.string.ui_connection_hint_network, R.string.ui_connection_hint_retry,
    )
}
