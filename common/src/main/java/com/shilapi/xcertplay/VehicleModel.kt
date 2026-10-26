package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R

/** Decorative home background; independent of the Lincoln/Ford home-logo choice. */
internal enum class VehicleModel(
    val key: String,
    @StringRes val label: Int,
) {
    CORSAIR("corsair", R.string.settings_vehicle_corsair),
    CUSTOM("custom", R.string.settings_vehicle_custom);

    companion object {
        /** Old Nautilus/Aviator preferences safely migrate to the single packaged default. */
        fun fromKey(key: String?): VehicleModel = if (key == CUSTOM.key) CUSTOM else CORSAIR
    }
}
