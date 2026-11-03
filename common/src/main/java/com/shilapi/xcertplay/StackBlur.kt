package com.shilapi.xcertplay

/**
 * The blurred album backdrop behind the music card.
 *
 * A stack blur is a box blur applied three times, which is what makes it look like frosted glass and
 * not like a smear: one pass leaves visible banding, three passes approximate a Gaussian closely
 * enough that the eye cannot tell. Each pass is a sliding window, so the cost is the pixel count and
 * not the radius — the backdrop is built from a thumbnail a hundred pixels wide, and the whole thing
 * runs once per album, on a worker thread.
 *
 * Kept free of Android types so the arithmetic is unit-tested on the JVM.
 */
internal object StackBlur {
    /** Three passes: one band, two soften, three read as glass. */
    private const val PASSES = 3
    private const val MAX_RADIUS = 64

    /**
     * Blurs a copy of [source] and returns it. Pixels are ARGB, row-major, [width] wide.
     *
     * [radius] is the blur radius in pixels of the *thumbnail*; the caller scales the result up, so a
     * radius of eight on a hundred-pixel backdrop reads as a wide frost on the card.
     */
    fun blur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width >= 0 && height >= 0) { "size must not be negative" }
        require(source.size >= width * height) { "source holds fewer pixels than $width x $height" }
        if (width <= 0 || height <= 0 || radius < 1) return source

        val pixels = source.copyOf()
        val target = IntArray(pixels.size)
        // Split the radius across the passes so three passes add up to the requested softness.
        val perPass = (radius / PASSES).coerceAtLeast(1).coerceAtMost(MAX_RADIUS)
        repeat(PASSES) {
            boxPass(pixels, target, width, height, perPass, horizontal = true)
            boxPass(target, pixels, width, height, perPass, horizontal = false)
        }
        return pixels
    }

    /**
     * One box pass in one direction. Every line is walked once with a running sum, and the window is
     * clamped at both ends so the edge pixels do not pick up black from outside the bitmap.
     */
    private fun boxPass(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val span = radius * 2 + 1
        for (line in 0 until lines) {
            val offset = if (horizontal) line * width else line
            val step = if (horizontal) 1 else width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = source[offset + i.coerceIn(0, length - 1) * step]
                a += pixel ushr 24 and 0xFF
                r += pixel ushr 16 and 0xFF
                g += pixel ushr 8 and 0xFF
                b += pixel and 0xFF
            }
            for (i in 0 until length) {
                target[offset + i * step] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val out = source[offset + (i - radius).coerceIn(0, length - 1) * step]
                val into = source[offset + (i + radius + 1).coerceIn(0, length - 1) * step]
                a += (into ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                r += (into ushr 16 and 0xFF) - (out ushr 16 and 0xFF)
                g += (into ushr 8 and 0xFF) - (out ushr 8 and 0xFF)
                b += (into and 0xFF) - (out and 0xFF)
            }
        }
    }
}
