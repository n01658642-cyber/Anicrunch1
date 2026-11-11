package com.anilili.data.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

internal const val PAIRING_SERVICE_TYPE = "_anilili._tcp."

/** Advertised so a phone can tell one television from another before either shows a code. */
internal const val PAIRING_TXT_VERSION = "v"
internal const val PAIRING_TXT_SESSION = "id"
internal const val PAIRING_TXT_SERVICES = "svc"

/**
 * A television found on the network. Everything here is unauthenticated — mDNS is a broadcast
 * anyone can answer — which is exactly why the handshake that follows ends in two people
 * comparing a code.
 */
data class DiscoveredTv(
    val name: String,
    val host: String,
    val port: Int,
)

/**
 * The television's mDNS registration, live only while pairing mode is open.
 *
 * No key material goes in the TXT records: they are broadcast in the clear and cached by unrelated
 * resolvers on the network.
 */
internal class PairingAdvertiser(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register(port: Int, sessionId: String, services: Set<String>) {
        if (listener != null) return
        val requestedName = "Anilili on ${android.os.Build.MODEL.orEmpty().ifBlank { "this TV" }}"
        val info = NsdServiceInfo().apply {
            serviceName = requestedName
            serviceType = PAIRING_SERVICE_TYPE
            this.port = port
            setAttribute(PAIRING_TXT_VERSION, PAIRING_PROTOCOL_VERSION.toString())
            setAttribute(PAIRING_TXT_SESSION, sessionId)
            setAttribute(PAIRING_TXT_SERVICES, services.joinToString(","))
        }
        PairingDiagnostics.log("mDNS registering \"$requestedName\" $PAIRING_SERVICE_TYPE port $port")
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // The name can come back changed: mDNS renames on collision, so a second TV in the
                // house appears as "... (1)" and the phone will show that, not what we asked for.
                val actual = info.serviceName.orEmpty()
                PairingDiagnostics.log(
                    if (actual == requestedName) {
                        "mDNS registered OK as \"$actual\""
                    } else {
                        "mDNS registered as \"$actual\" (renamed from \"$requestedName\")"
                    },
                )
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.advertised",
                    attributes = mapOf("name" to actual),
                )
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                PairingDiagnostics.log("mDNS REGISTRATION FAILED ${nsdErrorName(errorCode)}")
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.advertise_failed",
                    attributes = mapOf("errorCode" to errorCode),
                )
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                PairingDiagnostics.log("mDNS unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                PairingDiagnostics.log("mDNS unregister failed ${nsdErrorName(errorCode)}")
            }
        }
        listener = registration
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                listener = null
                PairingDiagnostics.log("mDNS registerService threw: ${it.javaClass.simpleName}")
                DiagnosticsLog.throwable("Pairing advertisement could not start", it)
            }
    }

    fun unregister() {
        val registration = listener ?: return
        listener = null
        runCatching { nsd.unregisterService(registration) }
    }
}

/**
 * The phone's browser.
 *
 * Resolutions are serialised: `NsdManager.resolveService` rejects a second concurrent call with
 * FAILURE_ALREADY_ACTIVE, and a living room with two televisions reports both at once.
 *
 * A multicast lock is held while browsing because several vendors stop delivering multicast to a
 * dozing Wi-Fi radio, which makes discovery work on the bench and fail on a real sofa.
 */
internal class PairingBrowser(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _found = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val found = _found.asStateFlow()

    private val resolveLock = Mutex()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (discoveryListener != null) return

        localPairingAddresses().forEach { (iface, address) ->
            PairingDiagnostics.log("This phone is $address ($iface)")
        }
        if (localPairingAddresses().isEmpty()) {
            PairingDiagnostics.log("WARNING this phone has no site-local IPv4 — is Wi-Fi on?")
        }

        multicastLock = runCatching {
            wifi?.createMulticastLock("anilili-pairing")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        PairingDiagnostics.log(
            if (multicastLock?.isHeld == true) {
                "Multicast lock held"
            } else {
                "Multicast lock NOT held — discovery may fail on some Wi-Fi chipsets"
            },
        )

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                PairingDiagnostics.log("mDNS browsing $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName.orEmpty()
                PairingDiagnostics.log("mDNS found \"$name\" — resolving")
                scope.launch {
                    val resolved = resolveLock.withLock { resolve(info, name) }
                    if (resolved == null) return@launch
                    val host = resolved.host?.hostAddress
                    if (host.isNullOrBlank()) {
                        // Resolving without an address happens on some stacks and would otherwise
                        // drop the TV from the list with no explanation at all.
                        PairingDiagnostics.log("\"$name\" resolved but carried no address")
                        return@launch
                    }
                    PairingDiagnostics.log("\"$name\" resolved to $host:${resolved.port}")
                    val entry = DiscoveredTv(
                        name = name.ifBlank { host },
                        host = host,
                        port = resolved.port,
                    )
                    _found.value = (_found.value.filterNot { it.name == entry.name } + entry)
                        .sortedBy { it.name.lowercase() }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                PairingDiagnostics.log("mDNS lost \"${info.serviceName.orEmpty()}\"")
                _found.value = _found.value.filterNot { it.name == info.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                PairingDiagnostics.log("mDNS browsing stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                PairingDiagnostics.log("mDNS BROWSE FAILED ${nsdErrorName(errorCode)}")
                DiagnosticsLog.event(
                    category = "pairing",
                    name = "pairing.discovery_failed",
                    attributes = mapOf("errorCode" to errorCode),
                )
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                PairingDiagnostics.log("mDNS stop failed ${nsdErrorName(errorCode)}")
            }
        }
        discoveryListener = listener
        runCatching { nsd.discoverServices(PAIRING_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                discoveryListener = null
                PairingDiagnostics.log("mDNS discoverServices threw: ${it.javaClass.simpleName}")
                DiagnosticsLog.throwable("Pairing discovery could not start", it)
            }
    }

    fun stop() {
        discoveryListener?.let { listener ->
            discoveryListener = null
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
        _found.value = emptyList()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo, name: String): NsdServiceInfo? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    // The most common real-world failure, and previously invisible: the TV was
                    // seen but never reachable, so the list simply stayed empty.
                    PairingDiagnostics.log("RESOLVE FAILED for \"$name\": ${nsdErrorName(errorCode)}")
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resume(resolved)
                }
            }
            // Still the only resolve API below API 34, and functional above it. A fresh listener
            // per call is required: NsdManager rejects one that is already in use.
            runCatching { nsd.resolveService(info, listener) }
                .onFailure {
                    PairingDiagnostics.log("resolveService threw for \"$name\": ${it.javaClass.simpleName}")
                    if (continuation.isActive) continuation.resume(null)
                }
        }
}
