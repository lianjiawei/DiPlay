package com.shilapi.xcertplay.update

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class InternetNetworkSelectorTest {
    @Test fun requiresBothInternetAndValidation() {
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = false, vpn = false))
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = false, validated = true, vpn = false))
        assertTrue(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = true, vpn = false))
        assertFalse(InternetNetworkSelector.isValidatedInternet(hasInternet = true, validated = true, vpn = true))
    }

    @Test fun skipsTheActiveVpnAndUsesTheValidatedUnderlyingNetwork() {
        val connectivity = mock(ConnectivityManager::class.java)
        val vpn = mock(Network::class.java)
        val wifi = mock(Network::class.java)
        val vpnCapabilities = capabilities(vpn = true)
        val wifiCapabilities = capabilities(vpn = false)
        `when`(connectivity.activeNetwork).thenReturn(vpn)
        `when`(connectivity.allNetworks).thenReturn(arrayOf(vpn, wifi))
        `when`(connectivity.getNetworkCapabilities(vpn)).thenReturn(vpnCapabilities)
        `when`(connectivity.getNetworkCapabilities(wifi)).thenReturn(wifiCapabilities)

        assertSame(wifi, InternetNetworkSelector.select(connectivity))

        `when`(connectivity.getNetworkCapabilities(wifi)).thenReturn(null)
        assertNull(InternetNetworkSelector.select(connectivity))
    }

    @Test fun retainsTheActivePhysicalNetworkWhenItHasInternet() {
        val connectivity = mock(ConnectivityManager::class.java)
        val mobile = mock(Network::class.java)
        val wifi = mock(Network::class.java)
        val internetCapabilities = capabilities(vpn = false)
        `when`(connectivity.activeNetwork).thenReturn(mobile)
        `when`(connectivity.allNetworks).thenReturn(arrayOf(wifi, mobile))
        `when`(connectivity.getNetworkCapabilities(mobile)).thenReturn(internetCapabilities)
        `when`(connectivity.getNetworkCapabilities(wifi)).thenReturn(internetCapabilities)

        assertSame(mobile, InternetNetworkSelector.select(connectivity))
    }

    private fun capabilities(vpn: Boolean) = mock(NetworkCapabilities::class.java).apply {
        `when`(hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        `when`(hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(vpn)
    }
}
