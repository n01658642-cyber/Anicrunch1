package com.anilili.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.TelephonyManager
import java.security.MessageDigest

/**
 * A short, stable name for the network the device is on now — "the home Wi-Fi", "the carrier's
 * mobile data" — so anything learned about how servers behave can be kept per network.
 *
 * The same phone sees very different things on its owner's home broadband and on a train's 3G: a
 * server that is quick at home can be slow on mobile data, and a carrier can block at the DNS level
 * what the home ISP never touches. One device-wide record averages those into an answer that is
 * right on neither.
 *
 * What goes into the key, and why:
 *
 *  - **Wi-Fi and Ethernet** are keyed by the router's gateway and the DNS servers it hands out. The
 *    SSID would be the natural name, but reading it needs location permission, which this app has
 *    no other reason to ask for. The gateway plus the ISP's resolvers tells one home from another
 *    well enough, and changes when the ISP does, which is the change that matters here.
 *  - **Mobile data** is keyed by the carrier's MCC+MNC, which needs no permission.
 *  - **VPN** is keyed by its DNS servers, since the tunnel, not the carrier, decides the route.
 *
 * Only a hash of the addresses is kept, never the addresses themselves.
 */
object NetworkIdentity {

    /** Used before [init], on API 22, and whenever the platform cannot say. */
    const val UNKNOWN = "unknown"

    /** A network change costs a key recompute; a lookup storm should not. */
    private const val CACHE_MS = 3_000L

    @Volatile private var appContext: Context? = null
    @Volatile private var cached: String = UNKNOWN
    @Volatile private var cachedAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The current network's key. Cheap: recomputed at most every few seconds. */
    fun current(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - cachedAt < CACHE_MS && cachedAt != 0L) return cached
        val key = runCatching { compute() }.getOrNull() ?: UNKNOWN
        cached = key
        cachedAt = now
        return key
    }

    private fun compute(): String? {
        val context = appContext ?: return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return null
        val link = cm.getLinkProperties(network)
        val dns = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.sorted()
        val gateways = link?.routes.orEmpty()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway?.hostAddress }
            .sorted()
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn:" + digest(dns)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                "cell:" + (tm?.networkOperator?.takeIf { it.isNotBlank() } ?: "unknown")
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi:" + digest(gateways + dns)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth:" + digest(gateways + dns)
            else -> "other:" + digest(gateways + dns)
        }
    }

    /** Eight hex characters: plenty to tell a handful of networks apart, nothing to identify them. */
    internal fun digest(parts: List<String>): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray())
        return bytes.take(4).joinToString("") { "%02x".format(it) }
    }
}
