package com.shilapi.xcertplay

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R

/** Decorative vehicle artwork; independent of pairing, OEM identity and hardware settings. */
internal enum class VehicleModel(
    val key: String,
    @StringRes val label: Int,
    @DrawableRes val scene: Int,
    @DrawableRes val softScene: Int,
) {
    CORSAIR("corsair", R.string.settings_vehicle_corsair, R.drawable.corsair_scene, R.drawable.corsair_scene_soft),
    NAUTILUS("nautilus", R.string.settings_vehicle_nautilus, R.drawable.nautilus_scene, R.drawable.nautilus_scene_soft),
    AVIATOR("aviator", R.string.settings_vehicle_aviator, R.drawable.aviator_scene, R.drawable.aviator_scene_soft);

    companion object {
        fun fromKey(key: String?): VehicleModel = entries.firstOrNull { it.key == key } ?: CORSAIR
    }
}
