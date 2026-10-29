package com.shilapi.xcertplay.update

import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackgroundUpdateWorkerTest {
    @Test fun persistedLegacyWorkerStopsWithoutCheckingTheNetwork() {
        val app = RuntimeEnvironment.getApplication()
        UpdateAvailability.saveBackgroundChecksEnabled(app, true)
        val worker = BackgroundUpdateWorker(app, mock(WorkerParameters::class.java))
        assertEquals(Result.success(), worker.doWork())
    }
}
