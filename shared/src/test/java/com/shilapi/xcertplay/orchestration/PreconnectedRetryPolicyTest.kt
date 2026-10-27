package com.shilapi.xcertplay.orchestration

import org.junit.Assert.*
import org.junit.Test

class PreconnectedRetryPolicyTest {
    @Test fun retriesOnlyAnEarlyFailureOfThePreconnectedPath() {
        assertTrue(PreconnectedRetryPolicy.shouldRetry(true, false, false))
        assertFalse(PreconnectedRetryPolicy.shouldRetry(false, false, false))
        assertFalse(PreconnectedRetryPolicy.shouldRetry(true, true, false))
        assertFalse(PreconnectedRetryPolicy.shouldRetry(true, false, true))
    }
}
