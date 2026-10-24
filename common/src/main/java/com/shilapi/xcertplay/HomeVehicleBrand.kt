package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R

/** Home artwork is independent of the identity and icon advertised to the iPhone. */
internal enum class HomeVehicleBrand(val key: String, val label: Int, val wordmark: Int, val logo: Int) {
    LINCOLN("lincoln", R.string.settings_home_brand_lincoln, R.string.car_button_brand_lincoln, R.drawable.ic_carplay),
    FORD("ford", R.string.settings_home_brand_ford, R.string.car_button_brand_ford, R.drawable.ic_ford);

    fun displayName(context: Context): String = context.getString(wordmark)

    companion object {
        fun fromKey(key: String?): HomeVehicleBrand? = entries.firstOrNull { it.key == key }
    }
}
