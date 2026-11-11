package com.anilili.data.remote

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DohProviderTest {

    @Test
    fun `a stored choice survives the round trip`() {
        DohProvider.entries.forEach { provider ->
            assertEquals(provider, DohProvider.fromStored(provider.storedValue))
        }
    }

    @Test
    fun `never choosing, or an unreadable choice, gets automatic`() {
        // Automatic still asks the network's own resolver first, so an install whose preference
        // file predates a renamed entry is not sent to a provider it never picked; it reaches for
        // a secure one only where the network is seen blocking.
        assertEquals(DohProvider.AUTO, DohProvider.fromStored(null))
        assertEquals(DohProvider.AUTO, DohProvider.fromStored(""))
        assertEquals(DohProvider.AUTO, DohProvider.fromStored("dns.watch"))
    }

    @Test
    fun `turning it off is a choice that is kept`() {
        assertEquals(DohProvider.NONE, DohProvider.fromStored("none"))
    }

    @Test
    fun `no two entries share a stored value`() {
        val stored = DohProvider.entries.map { it.storedValue }
        assertEquals(
            "Two entries writing the same value would read back as whichever is declared first",
            stored.size,
            stored.toSet().size,
        )
    }

    @Test
    fun `none and automatic name no resolver of their own`() {
        listOf(DohProvider.NONE, DohProvider.AUTO).forEach {
            assertNull(it.url)
            assertTrue(it.bootstrap.isEmpty())
        }
    }

    @Test
    fun `every resolver is an https endpoint`() {
        DohProvider.entries.filter { it != DohProvider.NONE && it != DohProvider.AUTO }.forEach { provider ->
            val url = provider.url?.toHttpUrlOrNull()
            assertNotNull("${provider.label} has no usable URL", url)
            // Plain HTTP would put the lookup back in front of the resolver being bypassed.
            assertEquals("${provider.label} is not https", "https", url!!.scheme)
        }
    }

    /**
     * A resolver that filters adult content refuses the app's own catalogue host, so offering one
     * would empty the app rather than filter it. Cloudflare's is the one that keeps being found —
     * it sits one address along from the two entries that are here, and its own documentation
     * lists all three together — so the decision is pinned rather than left to the comment beside
     * it. Matched on the host and on the bootstrap address, because either alone can be rewritten.
     */
    @Test
    fun `no resolver filters adult content`() {
        DohProvider.entries.forEach { provider ->
            assertFalse(
                "${provider.label} blocks www.miruro.bz and most sources with it",
                provider.url.orEmpty().contains("family.cloudflare-dns.com"),
            )
            assertFalse(
                "${provider.label} bootstraps an adult-content-filtering resolver",
                provider.bootstrap.any { it == "1.1.1.3" || it == "1.0.0.3" },
            )
        }
    }

    /**
     * The bootstrap list is the whole reason a chosen resolver can be reached on a network that
     * blocks it: without one, resolving the resolver's own hostname goes back to the resolver being
     * bypassed. A hostname here would be silently useless, and a second address is what survives one
     * anycast site being unreachable.
     */
    @Test
    fun `every resolver is reachable without asking the network to resolve it`() {
        DohProvider.entries.filter { it != DohProvider.NONE && it != DohProvider.AUTO }.forEach { provider ->
            assertTrue(
                "${provider.label} needs at least two bootstrap addresses",
                provider.bootstrap.size >= 2,
            )
            provider.bootstrap.forEach { address ->
                // getByName on a literal never touches the network; on a hostname it would, which
                // is exactly what this list exists to avoid.
                val parsed = InetAddress.getByName(address)
                assertTrue(
                    "${provider.label} bootstrap $address is not an IPv4 literal",
                    parsed is Inet4Address && parsed.hostAddress == address,
                )
            }
        }
    }
}
