package com.shilapi.xcertplay

/**
 * The driver's single lux preference is the centre of a small hysteresis band. Keeping the band
 * derived here avoids exposing two thresholds while preventing sensor noise around one value from
 * repeatedly changing CarPlay appearance.
 */
data class AmbientLightThreshold(val lux: Int = DEFAULT_LUX) {
    init {
        require(isValid(lux)) { "Lux threshold is outside the supported range" }
    }

    companion object {
        const val DEFAULT_LUX = 350
        const val MIN_LUX = 1
        const val MAX_LUX = 200_000
        private const val HYSTERESIS_PERCENT = 0.05f
        private const val MIN_HYSTERESIS_LUX = 5f
        private const val MAX_HYSTERESIS_LUX = 50f

        fun isValid(lux: Int): Boolean = lux in MIN_LUX..MAX_LUX

        fun fromStored(lux: Int): AmbientLightThreshold =
            if (isValid(lux)) AmbientLightThreshold(lux) else AmbientLightThreshold()
    }

    internal val hysteresisLux: Float
        get() = (lux * HYSTERESIS_PERCENT).coerceIn(MIN_HYSTERESIS_LUX, MAX_HYSTERESIS_LUX)
            .coerceAtMost(lux / 2f)

    internal val enterNightLux: Float
        get() = (lux - hysteresisLux).coerceAtLeast(0f)

    internal val enterDayLux: Float
        get() = lux + hysteresisLux
}
