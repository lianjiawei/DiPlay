package com.shilapi.xcertplay.media

/**
 * Scales interleaved 16-bit little-endian PCM in place.
 *
 * This is what actually ducks music: `AudioTrack.setStereoVolume` is advisory and several head
 * unit HALs ignore it for a whole stream, while the samples themselves are ours to scale.
 */
internal object PcmGain {
    /** Gain below which scaling is skipped, so normal playback keeps its exact bytes. */
    const val UNITY = 0.999f

    /** Absolute sample peak for periodic diagnostics; never copies or changes the audio. */
    fun peakS16(data: ByteArray, offset: Int, length: Int): Int {
        val start = offset.coerceIn(0, data.size)
        val end = start + (length.coerceIn(0, data.size - start) and 1.inv())
        var peak = 0
        var index = start
        while (index + 1 < end) {
            val sample = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(sample))
            index += 2
        }
        return peak
    }

    fun scaleS16(data: ByteArray, offset: Int, length: Int, gain: Float) {
        if (gain >= UNITY || length < 2) return
        val clamped = gain.coerceIn(0f, 1f)
        val start = offset.coerceAtLeast(0)
        // Whole 16-bit samples only; a trailing odd byte stays untouched.
        val end = start + ((length.coerceAtMost(data.size - start)) and 1.inv())
        var index = start
        while (index + 1 < end) {
            val sample = ((data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)).toShort()
            val scaled = (sample * clamped).toInt()
            data[index] = scaled.toByte()
            data[index + 1] = (scaled shr 8).toByte()
            index += 2
        }
    }
}
