package com.anilili.data.remote

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoDnsTest {

    private fun ip(vararg bytes: Int): InetAddress =
        InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())

    private val real = ip(104, 21, 7, 9)
    private val other = ip(172, 67, 1, 1)
    private val landing = ip(203, 0, 113, 5)

    /** A resolver answering from a fixed table; a missing name is "no answer". */
    private class Table(private val answers: Map<String, List<InetAddress>>) : Dns {
        val asked = mutableListOf<String>()
        override fun lookup(hostname: String): List<InetAddress> {
            asked += hostname
            return answers[hostname] ?: throw UnknownHostException(hostname)
        }
    }

    private class Denying : Dns {
        override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("$hostname: NXDOMAIN")
    }

    private class Unreachable : Dns {
        var asked = 0
        override fun lookup(hostname: String): List<InetAddress> {
            asked++
            throw UnknownHostException("failed").apply { initCause(IOException("connect timed out")) }
        }
    }

    private fun auto(
        system: Dns,
        secure: List<Dns>,
        network: () -> String = { "wifi:home" },
        engaged: MutableList<String> = mutableListOf(),
    ) = AutoDns(
        system = system,
        secure = { secure },
        networkKey = network,
        isNameDenied = AppDns::isNameDenied,
        onEngaged = { net, host, reason -> engaged += "$net $host $reason" },
    )

    @Test
    fun `a network that answers properly is never second-guessed`() {
        val secure = Table(mapOf("miruro.bz" to listOf(other)))
        val dns = auto(Table(mapOf("miruro.bz" to listOf(real))), listOf(secure))
        assertEquals(listOf(real), dns.lookup("miruro.bz"))
        assertTrue("the secure resolver was asked on a network that blocks nothing", secure.asked.isEmpty())
        assertFalse(dns.engagedHere())
    }

    @Test
    fun `a name the network will not answer is rescued, and the network remembered`() {
        val engaged = mutableListOf<String>()
        val system = Table(emptyMap())
        val secure = Table(mapOf("miruro.bz" to listOf(real), "ani.pm" to listOf(other)))
        val dns = auto(system, listOf(secure), engaged = engaged)

        assertEquals(listOf(real), dns.lookup("miruro.bz"))
        assertEquals(listOf("wifi:home miruro.bz no answer"), engaged)
        assertTrue(dns.engagedHere())

        // From now on this network asks the secure resolver first.
        system.asked.clear()
        assertEquals(listOf(other), dns.lookup("ani.pm"))
        assertTrue(system.asked.isEmpty())
    }

    @Test
    fun `a sinkhole answer is treated as a block`() {
        val dns = auto(
            Table(mapOf("miruro.bz" to listOf(ip(0, 0, 0, 0)))),
            listOf(Table(mapOf("miruro.bz" to listOf(real)))),
        )
        assertEquals(listOf(real), dns.lookup("miruro.bz"))
        assertTrue(dns.engagedHere())
    }

    @Test
    fun `what one network learns does not follow the phone onto another`() {
        var network = "wifi:home"
        val dns = auto(Table(emptyMap()), listOf(Table(mapOf("miruro.bz" to listOf(real)))), { network })
        dns.lookup("miruro.bz")
        network = "cell:22201"
        assertFalse(dns.engagedHere())
    }

    @Test
    fun `a secure resolver that says the name does not exist is believed`() {
        val second = Table(mapOf("gone.example" to listOf(real)))
        val dns = auto(Table(emptyMap()), listOf(Denying(), second))
        try {
            dns.lookup("gone.example")
            fail("a name the secure resolver denies must not resolve")
        } catch (_: UnknownHostException) {
        }
        assertTrue("shopped around after a real NXDOMAIN", second.asked.isEmpty())
        assertFalse(dns.engagedHere())
    }

    @Test
    fun `an unreachable secure resolver leaves the network's answer standing`() {
        val unreachable = Unreachable()
        val sinkholed = listOf(ip(0, 0, 0, 0))
        val dns = auto(Table(mapOf("miruro.bz" to sinkholed)), listOf(unreachable))
        assertEquals(sinkholed, dns.lookup("miruro.bz"))
        assertFalse(dns.engagedHere())
    }

    @Test
    fun `a host nobody can resolve is not asked about again straight away`() {
        val unreachable = Unreachable()
        val dns = auto(Table(emptyMap()), listOf(unreachable))
        repeat(3) {
            try {
                dns.lookup("dead.example")
            } catch (_: UnknownHostException) {
            }
        }
        assertEquals(1, unreachable.asked)
    }

    @Test
    fun `the device's own names are never questioned`() {
        val secure = Table(mapOf("printer.local" to listOf(real)))
        val lan = listOf(ip(192, 168, 1, 20))
        val dns = auto(Table(mapOf("printer.local" to lan, "router" to lan)), listOf(secure))
        assertEquals(lan, dns.lookup("printer.local"))
        assertEquals(lan, dns.lookup("router"))
        assertTrue(secure.asked.isEmpty())
    }

    @Test
    fun `another site's certificate switches the network only when DNS sent it there`() {
        // The ISP answers with its own block page's address; the secure resolver knows the real one.
        val blocked = auto(
            Table(mapOf("miruro.bz" to listOf(landing))),
            listOf(Table(mapOf("miruro.bz" to listOf(real)))),
        )
        assertTrue(blocked.interceptionSuspected("miruro.bz"))
        assertTrue(blocked.engagedHere())

        // Same addresses either way: the certificate problem is the server's or the phone's, and
        // changing resolvers would fix nothing.
        val sameServer = auto(
            Table(mapOf("old.example" to listOf(real))),
            listOf(Table(mapOf("old.example" to listOf(real)))),
        )
        assertFalse(sameServer.interceptionSuspected("old.example"))
        assertFalse(sameServer.engagedHere())
    }
}
