package com.shilapi.xcertplay.update

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.shilapi.xcertplay.CarPlayBackgroundSession
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackgroundUpdateWorkerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val release = UpdateRelease("v0.2.26-precision.9", "app.apk", "https://example.com/app.apk", "https://example.com/SHA256SUMS.txt")

    @After fun cleanup() {
        UpdateAvailability.clearAllForTest(app)
        CarPlayBackgroundSession.clear()
    }

    @Test fun shortPersonalVersionChecksAndStoresTheNextRelease() = checkVersion("precision.7")
    @Test fun legacyVersionStillChecksAndStoresTheNextRelease() = checkVersion("0.2.26-precision.7")

    @Test fun unknownDevelopmentVersionsDoNotCreatePermanentUpdateBanners() {
        val worker = worker("development")
        worker.updateLookup = { _, _ -> error("Development builds must not query releases") }
        assertEquals(Result.success(), worker.doWork())
        assertNull(UpdateAvailability.available(app, "development"))
    }

    private fun checkVersion(installed: String) {
        val worker = worker(installed)
        var calls = 0
        worker.updateLookup = { actual, network ->
            assertEquals(installed, actual)
            assertEquals(99, shadowOf(network).netId)
            calls++
            release
        }
        assertEquals(Result.success(), worker.doWork())
        assertEquals(1, calls)
        assertEquals(release, UpdateAvailability.available(app, installed))
        assertFalse(UpdateAvailability.shouldCheck(app, System.currentTimeMillis()))
    }

    private fun worker(version: String): BackgroundUpdateWorker {
        CarPlayBackgroundSession.clear()
        UpdateAvailability.clearAllForTest(app)
        UpdateAvailability.saveBackgroundChecksEnabled(app, true)
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.versionName = version
        shadowOf(app.packageManager).installPackage(info)
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        connectivity.clearAllNetworks()
        connectivity.setActiveNetworkInfo(null)
        val network = ShadowNetwork.newInstance(99)
        connectivity.addNetwork(network, mock(NetworkInfo::class.java))
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        connectivity.setNetworkCapabilities(network, capabilities)
        return BackgroundUpdateWorker(app, mock(WorkerParameters::class.java))
    }
}
