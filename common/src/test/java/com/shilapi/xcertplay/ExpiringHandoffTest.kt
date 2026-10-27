package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable

class ExpiringHandoffTest {
    private class Resource : Closeable {
        var closes = 0
        override fun close() { closes++ }
    }
    private var clock = 0L
    private val handoff = ExpiringHandoff<Resource> { clock }

    @Test fun claimedResourceHasExactlyOneOwner() {
        val resource = Resource()
        handoff.offer("AA", resource, 100)
        assertSame(resource, handoff.take("aa"))
        handoff.clear()
        assertEquals(0, resource.closes)
        assertNull(handoff.take("AA"))
    }

    @Test fun expiredWrongTargetOrReplacementClosesUnclaimedResource() {
        val first = Resource()
        val second = Resource()
        val third = Resource()
        handoff.offer("AA", first, 100)
        handoff.offer("AA", second, 100)
        assertEquals(1, first.closes)
        assertNull(handoff.take("BB"))
        assertEquals(1, second.closes)
        handoff.offer("AA", third, 100)
        clock = 100
        assertNull(handoff.take("AA"))
        handoff.clear()
        assertEquals(1, third.closes)
    }

    @Test fun staleTimeoutCannotCloseNewOrClaimedResource() {
        val first = Resource()
        val next = Resource()
        handoff.offer("AA", first, 100)
        handoff.offer("AA", next, 100)
        handoff.clearIf(first)
        assertSame(next, handoff.take("AA"))
        handoff.clearIf(next)
        assertEquals(1, first.closes)
        assertEquals(0, next.closes)
    }

    @Test fun oldWaitCannotClearANewerWaitsSocket() {
        val resource = Resource()
        handoff.offer("AA", resource, 100, owner = "new")
        handoff.clearOwner("old")
        assertEquals(0, resource.closes)
        handoff.clearOwner("new")
        assertEquals(1, resource.closes)
    }
}
