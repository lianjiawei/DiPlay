package com.shilapi.xcertplay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source scan for the "Settings layout" rules in AGENTS.md. */
class SettingsLayoutSourceRulesTest {
    private val sources = File("src/main/java/com/shilapi/xcertplay").listFiles { f -> f.extension == "kt" }!!
    // Android's unit selector is not an app-defined fixed pixel dimension. Measured text
    // can use that selector after accounting for the user's font scale and window width.
    private val pixelConstant = Regex("""(?<!TypedValue\.)\b[A-Z][A-Z0-9_]*_PX\b""")

    @Test fun blockGapsUseTheNamedConstant() {
        val literal = Regex("""(bottom|top)Margin = dp\((16|18|20)\)""")
        assertEquals(emptyList<String>(), hits(File(sources.first().parentFile, "DiPlayActivity.kt"), literal))
    }

    @Test fun sizesAreNotPixelConstants() {
        assertEquals(emptyList<String>(), sources.flatMap { hits(it, pixelConstant) })
    }

    @Test fun pixelRuleDistinguishesDimensionsFromFrameworkUnits() {
        assertTrue(pixelConstant.containsMatchIn("const val BUTTON_HEIGHT_PX = 48"))
        assertTrue(pixelConstant.containsMatchIn("view.minimumWidth = BUTTON_WIDTH_PX"))
        assertFalse(pixelConstant.containsMatchIn("setTextSize(TypedValue.COMPLEX_UNIT_PX, measuredSize)"))
    }

    private fun hits(file: File, pattern: Regex) = file.readLines().withIndex()
        .filter { pattern.containsMatchIn(it.value) }
        .map { "${file.name}:${it.index + 1}: ${it.value.trim()}" }
}
