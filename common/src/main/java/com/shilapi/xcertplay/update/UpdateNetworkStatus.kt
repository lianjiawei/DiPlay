package com.shilapi.xcertplay.update

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.shilapi.xcertplay.host.R
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Preflight is deliberately less strict than route selection: OEM validation can be absent. */
internal object UpdateNetworkStatus {
    fun isOffline(connectivity: ConnectivityManager): Boolean {
        val networks = buildList {
            connectivity.activeNetwork?.let { add(it) }
            connectivity.allNetworks.forEach { if (it !in this) add(it) }
        }
        if (networks.isEmpty()) return true
        var unknown = false
        for (network in networks) {
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities == null) {
                unknown = true
            } else if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return false
            }
        }
        // Missing OEM capability information should allow the request to try normally.
        return !unknown
    }

    fun failureMessage(failure: Throwable): Int = when (failure) {
        is UnknownHostException -> R.string.update_server_unreachable
        is SocketTimeoutException -> R.string.update_connection_timeout
        else -> R.string.update_failed
    }
}
