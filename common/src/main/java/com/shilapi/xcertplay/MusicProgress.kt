package com.shilapi.xcertplay

/** A position anchored to the same monotonic clock used by the media session. */
internal data class MusicProgress(
    val positionMillis: Long,
    val durationMillis: Long,
    val playing: Boolean,
    val receivedAt: Long,
) {
    fun positionAt(now: Long): Long {
        val carried = if (playing) (now - receivedAt).coerceAtLeast(0L) else 0L
        return (positionMillis + carried).coerceIn(0L, durationMillis.coerceAtLeast(0L))
    }

    fun fractionAt(now: Long): Float =
        if (durationMillis <= 0L) 0f else positionAt(now).toFloat() / durationMillis
}
