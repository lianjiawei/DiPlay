package com.shilapi.xcertplay

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStateTest {
    @After fun tearDown() = PendingReconnect.clear()

    @Test fun pendingReconnectBelongsToTheSessionThatSavedIt() {
        val first = Any()
        val second = Any()
        assertFalse(PendingReconnect.isPending(first))

        PendingReconnect.mark(first)

        assertTrue(PendingReconnect.isPending(first))
        assertFalse(PendingReconnect.isPending(second))
        assertFalse(PendingReconnect.isPending(null))
        PendingReconnect.clear()
        assertFalse(PendingReconnect.isPending(first))
    }
}
