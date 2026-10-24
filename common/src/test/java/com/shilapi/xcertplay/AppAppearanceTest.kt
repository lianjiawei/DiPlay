package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAppearanceTest {
    @Test fun legacyBackupKeysRemainReadable() {
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey(null))
        assertEquals(AppAppearance.DARK, AppAppearance.fromKey("future-value"))
        AppAppearance.entries.forEach { assertEquals(it, AppAppearance.fromKey(it.key)) }
    }
}
