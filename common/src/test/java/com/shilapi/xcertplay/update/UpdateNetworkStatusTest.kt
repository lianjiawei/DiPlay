package com.shilapi.xcertplay.update

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateNetworkStatusTest {
    private val connectivity = mock(ConnectivityManager::class.java)
    private val network = mock(Network::class.java)

    @Test fun noNetworksIsOffline() {
        `when`(connectivity.allNetworks).thenReturn(emptyArray())
        assertTrue(UpdateNetworkStatus.isOffline(connectivity))
    }

    @Test fun aCarPlayVpnOrLocalHotspotAloneIsOffline() {
        `when`(connectivity.allNetworks).thenReturn(arrayOf(network))
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
        assertTrue(UpdateNetworkStatus.isOffline(connectivity))
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        assertTrue(UpdateNetworkStatus.isOffline(connectivity))
    }

    @Test fun anUnvalidatedUnderlyingNetworkStillAllowsTheRequest() {
        val vpn = mock(Network::class.java)
        val vpnCapabilities = mock(NetworkCapabilities::class.java)
        `when`(connectivity.activeNetwork).thenReturn(vpn)
        `when`(connectivity.allNetworks).thenReturn(arrayOf(vpn, network))
        `when`(connectivity.getNetworkCapabilities(vpn)).thenReturn(vpnCapabilities)
        `when`(vpnCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true)
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        assertFalse(UpdateNetworkStatus.isOffline(connectivity))
    }

    @Test fun missingOemCapabilitiesDoNotFalselyReportOffline() {
        `when`(connectivity.allNetworks).thenReturn(arrayOf(network))
        assertFalse(UpdateNetworkStatus.isOffline(connectivity))
    }
}
