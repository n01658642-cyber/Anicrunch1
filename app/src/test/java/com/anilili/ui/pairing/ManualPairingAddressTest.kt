package com.anilili.ui.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The typed fallback for when mDNS does not survive the network between the two devices. It is the
 * one place in pairing where a person enters free text, so it has to refuse nonsense rather than
 * hand a malformed host to the socket and surface the failure as a pairing error.
 */
class ManualPairingAddressTest {
    @Test
    fun `a host and port is accepted`() {
        val parsed = parseManualAddress(" 192.168.1.50:47001 ")
        assertEquals("192.168.1.50", parsed?.host)
        assertEquals(47001, parsed?.port)
        assertEquals("192.168.1.50", parsed?.name)
    }

    @Test
    fun `an IPv6 literal keeps everything before the last colon`() {
        val parsed = parseManualAddress("[fe80::1]:47001")
        assertEquals("[fe80::1]", parsed?.host)
        assertEquals(47001, parsed?.port)
    }

    @Test
    fun `anything without a usable port is refused`() {
        assertNull(parseManualAddress(""))
        assertNull(parseManualAddress("192.168.1.50"))
        assertNull(parseManualAddress("192.168.1.50:"))
        assertNull(parseManualAddress(":47001"))
        assertNull(parseManualAddress("192.168.1.50:not-a-port"))
        assertNull(parseManualAddress("192.168.1.50:0"))
        assertNull(parseManualAddress("192.168.1.50:70000"))
    }
}
