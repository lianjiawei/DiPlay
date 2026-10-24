package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Attaches to an existing station network. Never creates an AP, joins Wi-Fi or changes routing. */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
    /** Credentials for the original iAP2 Wi-Fi provisioning handshake. */
    private val passphrase: String,
) : WirelessHotspotManager {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: throw IllegalStateException("ConnectivityManager is unavailable")
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    private val invalidated = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var selected: Network? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var hosts: List<InetAddress> = emptyList()
    @Volatile private var gateways: List<InetAddress> = emptyList()
    private var interfaceIndex = 0
    @Volatile private var interfaceName: String? = null
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selected) invalidate("network lost")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network == selected && !sameLink(properties)) invalidate("interface or address changed")
        }
    }

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null && passphrase.length in 8..63) {
            "Existing Wi-Fi requires its SSID and an 8–63 character WPA2 password"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(selected == null) { "ExistingWifiManager has already started" }
        val started = System.nanoTime()
        while (!closed) {
            // INTERNET/VALIDATED and the default route do not identify the CarPlay LAN.
            val networks = connectivity.allNetworks.filter { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            }
            if (networks.size > 1) throw IOException("Multiple Wi-Fi networks are active; keep one existing Wi-Fi connection")
            val network = networks.singleOrNull()
            val properties = network?.let(connectivity::getLinkProperties)
            val name = properties?.interfaceName
            val iface = name?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
            val address = if (iface == null) null else properties?.let {
                wirelessHostAddress(it.linkAddresses.map { link -> link.address }, iface.index)
            }
            if (network != null && name != null && address != null) {
                val addresses = existingWifiHostAddresses(properties.linkAddresses.map { it.address }, iface!!.index)
                val capabilities = connectivity.getNetworkCapabilities(network)
                val networkInfo = if (Build.VERSION.SDK_INT >= 31) capabilities?.transportInfo as? WifiInfo else null
                // On-demand capabilities can redact SSID/BSSID even when the legacy station
                // snapshot can supply them. A non-null redacted object must not hide that input.
                @Suppress("DEPRECATION")
                val stationInfo = try { wifi.connectionInfo } catch (_: SecurityException) { null }
                val info = listOfNotNull(networkInfo, stationInfo).firstOrNull { readableSsid(it) != null }
                    ?: networkInfo ?: stationInfo
                val liveSsid = readableSsid(info)
                val networkSsid = liveSsid ?: ssid.takeIf { it.isNotBlank() }
                    ?: throw IOException("Cannot read the connected Wi-Fi name. Allow location access and enable system location, or select its name in Connection setup")
                if (networkSsid != ssid) throw IOException(
                    "Connected Wi-Fi differs from the saved credentials; select the current network and enter its password again",
                )
                if (Build.VERSION.SDK_INT >= 31 && info != null &&
                    info.currentSecurityType !in listOf(WifiInfo.SECURITY_TYPE_UNKNOWN,
                        WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_SAE)) {
                    throw IOException("Existing Wi-Fi connection requires a WPA2-Personal network")
                }
                val frequency = info?.frequency?.takeIf { it > 0 }
                val channel = frequency?.let(::wifiFrequencyMhzToChannel) ?: 0
                val apBssid = accessPointAddress(info?.bssid)
                synchronized(lock) {
                    check(!closed) { "ExistingWifiManager is closed" }
                    selected = network
                    host = address
                    hosts = addresses
                    gateways = gatewayCandidates(properties)
                    interfaceIndex = iface.index
                    interfaceName = name
                    connectivity.registerNetworkCallback(requestWithoutDefaultCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
                    callbackRegistered = true
                }
                // Close the gap between reading the link and registering the callback.
                val current = connectivity.getLinkProperties(network)
                if (current == null || !sameLink(current) || invalidated.get()) {
                    throw IOException("Existing Wi-Fi changed during startup; connect again")
                }
                onDiagnostic("Existing Wi-Fi attached iface=$name host=${address.hostAddress} " +
                    "networkNameReadable=${liveSsid != null} channel=$channel " +
                    "handshake=credentials " +
                    "receiverIdentity=saved")
                return WirelessHotspotInfo(networkSsid, passphrase, Iap2WirelessSecurity.WPA_WPA2,
                    channel, frequency,
                    // The router's BSSID is not this receiver's AirPlay device identity.
                    null, name, address, when {
                        frequency == null -> "Auto"
                        frequency < 2500 -> "2.4 GHz"
                        frequency < 5955 -> "5 GHz"
                        else -> "6 GHz"
                    }, WirelessHotspotBackend.EXISTING_WIFI, hostAddresses = addresses, accessPointBssid = apBssid,
                    configureWifi = true, network = network, gatewayAddresses = gateways)
            }
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                throw IOException("Existing Wi-Fi is not connected or has no usable address. Connect both devices to the same Wi-Fi in system settings")
            }
            Thread.sleep(200)
        }
        throw IOException("Existing Wi-Fi attachment was cancelled")
    }

    private fun sameLink(properties: LinkProperties): Boolean =
        properties.interfaceName == interfaceName && properties.linkAddresses.any { it.address == host } &&
            existingWifiHostAddresses(properties.linkAddresses.map { it.address }, interfaceIndex).toSet() == hosts.toSet()
            && gatewayCandidates(properties).toSet() == gateways.toSet()

    private fun gatewayCandidates(properties: LinkProperties): List<InetAddress> =
        properties.routes.filter { it.isDefaultRoute && it.`interface` == properties.interfaceName }
            .mapNotNull { it.gateway as? Inet4Address }
            .filter { gateway ->
                !gateway.isAnyLocalAddress && !gateway.isLoopbackAddress && !gateway.isMulticastAddress &&
                    properties.linkAddresses.any { link ->
                        val local = link.address
                        local is Inet4Address && local != gateway && link.prefixLength in 1..32 &&
                            (0 until link.prefixLength).all { bit ->
                                (local.address[bit / 8].toInt() xor gateway.address[bit / 8].toInt()) and
                                    (1 shl (7 - bit % 8)) == 0
                            }
                    }
            }.distinct().take(2)

    override fun validateReady() {
        val network = selected ?: throw IOException("Existing Wi-Fi is not attached")
        val properties = connectivity.getLinkProperties(network)
        if (closed || invalidated.get() || properties == null || !sameLink(properties)) {
            throw IOException("Existing Wi-Fi changed; connect again")
        }
    }

    private fun readableSsid(info: WifiInfo?): String? = info?.ssid?.removeSurrounding("\"")
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }

    private fun accessPointAddress(text: String?): ByteArray? {
        if (text == null || !text.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val bytes = text.split(':').map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.all { it == 0.toByte() } || bytes[0].toInt() and 1 != 0 ||
            text.equals("02:00:00:00:00:00", ignoreCase = true)) return null
        return bytes
    }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            if (callbackRegistered) {
                connectivity.unregisterNetworkCallback(callback)
                callbackRegistered = false
            }
        }
    }

    // NetworkRequest.Builder.clearCapabilities needs API 30; before that, drop the defaults one by one.
    private fun requestWithoutDefaultCapabilities(): NetworkRequest.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NetworkRequest.Builder().clearCapabilities()
        } else {
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        }
}
