package com.anilili.data.remote

import java.security.MessageDigest
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTrustAnchorsTest {

    @Test
    fun `the bundled blob is ISRG Root X1 and nothing else`() {
        // The whole point of pinning the fingerprint: this file carries a trust anchor as a base64
        // blob, and a blob is not reviewable by eye. Published SHA-256 for ISRG Root X1.
        val certificate = LegacyTrustAnchors.parseIsrgRootX1()
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString("") { "%02X".format(it) }

        assertEquals(
            "96BCEC06264976F37460779ACF28C5A7CFE8A3C0AAE11A8FFCEE05C0BDDF08C6",
            fingerprint,
        )
        assertTrue(certificate.subjectX500Principal.name.contains("ISRG Root X1"))
    }

    @Test
    fun `it is the self-signed root, not a cross-signature that can expire out from under it`() {
        // Old Android used to reach Let's Encrypt through the DST Root CA X3 cross-sign, and that
        // is exactly what broke when it expired in 2021. Bundling a cross-signed certificate would
        // repeat the mistake with a later date on it.
        val certificate = LegacyTrustAnchors.parseIsrgRootX1()

        assertEquals(
            certificate.subjectX500Principal,
            certificate.issuerX500Principal,
        )
    }

    @Test
    fun `the anchor has not expired`() {
        // A trust anchor is only load-bearing while it is valid, and this one runs to 2035-06-04.
        // If this ever fails, the fix is a newer root, not a longer wait.
        LegacyTrustAnchors.parseIsrgRootX1().checkValidity(Date())
    }

    @Test
    fun `only the platform versions missing the root get it from us`() {
        // 25 is 7.1.1, where ISRG Root X1 arrives in the system image. From there up the platform
        // does its own verification and is better at it than a merged trust manager.
        assertTrue("Android 5.1 needs it", LegacyTrustAnchors.needsBundledIsrgRoot(22))
        assertTrue("Android 7.0 needs it", LegacyTrustAnchors.needsBundledIsrgRoot(24))
        assertFalse("Android 7.1.1 has it", LegacyTrustAnchors.needsBundledIsrgRoot(25))
        assertFalse("Android 16 has it", LegacyTrustAnchors.needsBundledIsrgRoot(36))
    }
}
