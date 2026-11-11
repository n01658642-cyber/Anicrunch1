package com.anilili.data.pairing

import com.anilili.errors.AnililiError
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pairing fails on networks we cannot reproduce, in front of people who cannot read logcat. These
 * pin the translation from a socket exception to the sentence that actually tells them what to do,
 * because the three common causes — a closed port, a router isolating its clients, and two devices
 * on different networks — look identical to a viewer and need completely different fixes.
 */
class PairingFailureAdviceTest {
    @Test
    fun `a refused connection points at the pairing screen, not the network`() {
        val advice = describePairingFailure(
            ConnectException("failed to connect: ECONNREFUSED"),
            host = "192.168.1.50",
            port = 47001,
        )
        assertTrue(advice.summary.contains("192.168.1.50:47001"))
        assertTrue(advice.summary.contains("Nothing is listening"))
        assertTrue(advice.hint.contains("Sign in from my phone"))
    }

    @Test
    fun `a timeout blames router isolation, which is what it usually is`() {
        val advice = describePairingFailure(
            SocketTimeoutException("failed to connect after 8000ms"),
            host = "192.168.1.50",
            port = 47001,
            phoneAddresses = listOf("192.168.1.27"),
        )
        assertTrue(advice.hint.contains("isolation"))
    }

    @Test
    fun `different subnets are named outright rather than guessed at`() {
        val advice = describePairingFailure(
            SocketTimeoutException("timeout"),
            host = "192.168.5.50",
            port = 47001,
            phoneAddresses = listOf("192.168.31.27"),
        )
        assertTrue(advice.hint.contains("different networks"))
        assertTrue(advice.hint.contains("192.168.31.x"))
        assertTrue(advice.hint.contains("192.168.5.x"))
    }

    @Test
    fun `no route and unknown host each get their own explanation`() {
        assertTrue(
            describePairingFailure(NoRouteToHostException(), host = "10.0.0.5", port = 1)
                .summary.contains("Can't reach 10.0.0.5:1"),
        )
        assertTrue(
            describePairingFailure(UnknownHostException(), host = "not-an-address")
                .hint.contains("exactly as the television shows it"),
        )
    }

    @Test
    fun `the television hanging up is not reported as a network fault`() {
        val advice = describePairingFailure(EOFException("Peer closed"), host = "192.168.1.50")
        assertTrue(advice.summary.contains("closed the connection"))
    }

    @Test
    fun `a protocol disagreement warns against continuing`() {
        val advice = describePairingFailure(PairingProtocolException("Opened key does not match the commitment"))
        // What disagreed is the detail; the line is the same short sentence every PAIR-06 shows, so
        // the code means one thing and the specifics are still there to read.
        assertEquals(AnililiError.PAIRING_PROTOCOL_MISMATCH, advice.failure.error)
        assertTrue(advice.hint.contains("Opened key does not match the commitment"))
        assertTrue(advice.hint.contains("do not continue"))
    }

    /**
     * Each branch is its own code, which is the point of naming them: a report saying `PAIR-01` and
     * one saying `PAIR-03` send whoever reads it to two different parts of the network.
     */
    @Test
    fun `each cause carries its own code`() {
        assertEquals(
            AnililiError.PAIRING_NOTHING_LISTENING,
            describePairingFailure(ConnectException("ECONNREFUSED")).failure.error,
        )
        assertEquals(
            AnililiError.PAIRING_NO_REPLY,
            describePairingFailure(SocketTimeoutException("timeout")).failure.error,
        )
        assertEquals(
            AnililiError.PAIRING_BAD_ADDRESS,
            describePairingFailure(UnknownHostException(), host = "not-an-address").failure.error,
        )
        assertEquals(
            AnililiError.PAIRING_CLOSED,
            describePairingFailure(EOFException()).failure.error,
        )
        // Anything unclassified is PAIR-09 rather than an exception class name on screen.
        assertEquals(
            AnililiError.PAIRING_FAILED,
            describePairingFailure(IllegalStateException("something odd")).failure.error,
        )
        assertTrue(
            describePairingFailure(IllegalStateException("something odd")).hint.contains("something odd"),
        )
    }

    @Test
    fun `matching subnets produce no mismatch note`() {
        assertNull(subnetMismatchNote("192.168.1.50", listOf("192.168.1.27")))
        // Multi-homed phone: one matching interface is enough.
        assertNull(subnetMismatchNote("192.168.1.50", listOf("10.0.0.2", "192.168.1.27")))
        assertNull(subnetMismatchNote("192.168.1.50", emptyList()))
        assertNull(subnetMismatchNote(null, listOf("192.168.1.27")))
    }

    @Test
    fun `prefixes are parsed strictly enough to avoid nonsense advice`() {
        assertEquals("192.168.31", ipv24Prefix("192.168.31.27"))
        assertEquals("10.0.0", ipv24Prefix(" 10.0.0.2 "))
        assertNull(ipv24Prefix("fe80::1"))
        assertNull(ipv24Prefix("192.168.1"))
        assertNull(ipv24Prefix("192.168.1.999"))
        assertNull(ipv24Prefix("192.168..1"))
    }
}
