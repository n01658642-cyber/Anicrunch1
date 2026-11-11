package com.anilili.data.remote

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns

/**
 * [DohProvider.AUTO]: the network's own resolver, switching to a secure one only where the network
 * turns out to be blocking a source.
 *
 * Turning a DoH resolver on for everyone would cost every lookup a trip to a third party, show that
 * party every host the app asks for, and break the app outright on the networks that block the DoH
 * resolvers themselves. Leaving it to viewers means a viewer on a blocking ISP sees every source
 * "down" and has no reason to suspect DNS. So the network's resolver goes first, as it always has,
 * and a secure resolver is asked only when its answer looks like a block:
 *
 *  - **no answer** — the name did not resolve at all;
 *  - **a sinkhole** — a public hostname answered with 0.0.0.0, loopback, or a private range;
 *  - **a landing page** — see [interceptionSuspected]: the lookup "worked" but the certificate
 *    that came back is not the site's.
 *
 * A secure answer is used only if it is a real one: a secure resolver that says the name does not
 * exist is believed rather than second-guessed, and one that cannot be reached leaves the network's
 * answer standing. When a secure answer does rescue a lookup, the network is remembered as one that
 * needs it — per [networkKey], so home Wi-Fi learning it says nothing about mobile data — and from
 * then on that network asks the secure resolver first.
 *
 * Only names that are not the device's own business are questioned: `localhost`, `.local` and
 * dot-less names legitimately resolve to private addresses, and a split-horizon corporate name
 * still wins when the secure resolver has never heard of it.
 */
internal class AutoDns(
    private val system: Dns,
    /** Secure resolvers in the order to try them; each may throw [UnknownHostException]. */
    private val secure: () -> List<Dns>,
    private val networkKey: () -> String,
    private val isNameDenied: (UnknownHostException) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onEngaged: (network: String, host: String, reason: String) -> Unit = { _, _, _ -> },
    engaged: Map<String, Long> = emptyMap(),
) : Dns {

    /** Networks known to need the secure resolver, with when that was learnt. */
    private val needsSecure = ConcurrentHashMap(engaged)

    /** Names no resolver could produce, so a dead host does not cost a DoH round trip each time. */
    private val deadUntil = ConcurrentHashMap<String, Long>()

    /** A copy of what is remembered, for persisting. */
    fun engagedNetworks(): Map<String, Long> = HashMap(needsSecure)

    /** Whether lookups on the current network go to the secure resolver first. */
    fun engagedHere(): Boolean = needsSecure.containsKey(networkKey())

    override fun lookup(hostname: String): List<InetAddress> {
        if (isPrivateName(hostname)) return system.lookup(hostname)
        deadUntil[hostname]?.let { until ->
            if (clock() < until) throw UnknownHostException("$hostname: unresolvable (remembered)")
            deadUntil.remove(hostname)
        }
        val network = networkKey()
        if (needsSecure.containsKey(network)) {
            return secureLookup(hostname) ?: system.lookup(hostname)
        }

        var failure: UnknownHostException? = null
        val answer = try {
            system.lookup(hostname)
        } catch (e: UnknownHostException) {
            failure = e
            null
        }
        if (answer != null && !isSinkhole(answer)) return answer

        val rescued = secureLookup(hostname)
        if (rescued != null && !isSinkhole(rescued)) {
            engage(network, hostname, if (answer == null) "no answer" else "sinkhole ${answer.first().hostAddress}")
            return rescued
        }
        if (answer != null) return answer
        deadUntil[hostname] = clock() + DEAD_HOST_MS
        throw failure ?: UnknownHostException(hostname)
    }

    /**
     * Called when a connection to [hostname] came back with a certificate that is not the site's —
     * the way an ISP's block page looks when its DNS points the name at its own server. Decides
     * whether DNS is really the cause, and if so switches this network over so a retry can succeed.
     *
     * It is not always DNS: a server can present a broken certificate, and an old phone can fail to
     * trust a perfectly good one ([LegacyTrustAnchors] exists for that). So the network's answer and
     * a secure one are compared, and only a *different* set of addresses counts. Same addresses,
     * same server, and switching resolvers would change nothing.
     */
    fun interceptionSuspected(hostname: String): Boolean {
        if (isPrivateName(hostname)) return false
        val network = networkKey()
        if (needsSecure.containsKey(network)) return false
        val local = runCatching { system.lookup(hostname) }.getOrNull() ?: return false
        val trusted = secureLookup(hostname) ?: return false
        if (isSinkhole(trusted) || trusted.toSet().intersect(local.toSet()).isNotEmpty()) return false
        engage(network, hostname, "certificate for another site at ${local.first().hostAddress}")
        return true
    }

    private fun engage(network: String, hostname: String, reason: String) {
        if (needsSecure.putIfAbsent(network, clock()) == null) onEngaged(network, hostname, reason)
    }

    /** The first real answer a secure resolver gives, or null if none could say. */
    private fun secureLookup(hostname: String): List<InetAddress>? {
        for (resolver in secure()) {
            try {
                return resolver.lookup(hostname).takeIf { it.isNotEmpty() }
            } catch (e: UnknownHostException) {
                // "No such name" from a resolver that answered is the answer; do not shop around.
                if (isNameDenied(e)) return null
            } catch (_: Exception) {
                // Unreachable or broken: the next resolver gets its turn.
            }
        }
        return null
    }

    internal companion object {
        /** Long enough to absorb a burst of retries, short enough to notice a host coming back. */
        const val DEAD_HOST_MS = 2 * 60_000L

        fun isPrivateName(hostname: String): Boolean {
            val name = hostname.lowercase().trimEnd('.')
            return '.' !in name || name == "localhost" || name.endsWith(".localhost") ||
                name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".home") ||
                name.endsWith(".internal") || name.endsWith(".home.arpa")
        }

        /** Every address is one a public site could not really be at. */
        fun isSinkhole(addresses: List<InetAddress>): Boolean =
            addresses.isNotEmpty() && addresses.all(::isNonPublic)

        private fun isNonPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress
            ) {
                return true
            }
            val b = address.address
            return when (address) {
                // 0.0.0.0/8 and the carrier-grade NAT range 100.64.0.0/10.
                is Inet4Address -> b[0].toInt() == 0 ||
                    (b[0].toInt() == 100 && (b[1].toInt() and 0xC0) == 64)
                // fc00::/7, unique local addresses.
                is Inet6Address -> (b[0].toInt() and 0xFE) == 0xFC
                else -> false
            }
        }
    }
}
