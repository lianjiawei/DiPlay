package com.shilapi.xcertplay

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** A real host interface keeps ordinary Wi-Fi P2P tests off the production two-second retry path. */
internal fun testNetworkInterfaceName(): String = runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { networkInterface ->
        Collections.list(networkInterface.inetAddresses).any { address ->
            address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress &&
                !address.isLinkLocalAddress && !address.isMulticastAddress
        }
    }?.name
}.getOrNull() ?: "p2p-test-missing"
