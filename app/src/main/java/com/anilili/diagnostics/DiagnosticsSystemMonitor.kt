package com.anilili.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/** Process-lifetime native callbacks for network transitions and thermal throttling. */
object DiagnosticsSystemMonitor {
    private val installed = AtomicBoolean(false)

    /**
     * The last capabilities actually reported, so an unchanged repeat is dropped.
     *
     * A Sony BRAVIA re-announces its default network every six seconds with byte-identical
     * capabilities. One bundle from that television held 3168 events of which 2187 were this line
     * repeated verbatim, leaving five navigation events to diagnose from: the callback was
     * evicting the record it exists to provide.
     */
    @Volatile
    private var lastCapabilities: Map<String, Any?>? = null

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        installNetworkMonitor(context.applicationContext)
        installThermalMonitor(context.applicationContext)
    }

    private fun installNetworkMonitor(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    DiagnosticsLog.event("network", "default.available")
                }

                override fun onLost(network: Network) {
                    lastCapabilities = null
                    DiagnosticsLog.event("network", "default.lost")
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val current = mapOf<String, Any?>(
                        "transport" to transport(capabilities),
                        "validated" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        "metered" to !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                        "captivePortal" to capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                        "vpn" to capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        "downKbps" to capabilities.linkDownstreamBandwidthKbps,
                        "upKbps" to capabilities.linkUpstreamBandwidthKbps,
                    )
                    if (current == lastCapabilities) return
                    lastCapabilities = current
                    DiagnosticsLog.event(
                        category = "network",
                        name = "default.capabilities_changed",
                        attributes = current,
                    )
                }

                override fun onLosing(network: Network, maxMsToLive: Int) {
                    // A network on its way out is a real transition, unlike the repeats above.
                    DiagnosticsLog.event("network", "default.losing", mapOf("maxMsToLive" to maxMsToLive))
                }
            })
        }.onFailure { DiagnosticsLog.throwable("default network monitor unavailable", it) }
    }

    private fun installThermalMonitor(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        runCatching {
            manager.addThermalStatusListener { status ->
                DiagnosticsLog.event("system", "thermal.changed", mapOf("status" to status))
            }
        }.onFailure { DiagnosticsLog.throwable("thermal monitor unavailable", it) }
    }

    private fun transport(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
        else -> "other"
    }
}
