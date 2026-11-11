package com.anilili.errors

import java.io.IOException
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two shapes a filtered connection took in a tester's bundle, and the lookalikes that are not. */
class TlsInterceptionTest {

    @Test
    fun `okhttp's wrapped conscrypt failure is interception`() {
        // As the bundle had it: the player's IOException around an ExecutionException around the
        // SSLException that says what happened.
        val failure = IOException(
            ExecutionException(SSLException("Unable to parse TLS packet header")),
        )
        assertTrue(failure.isTlsIntercepted())
    }

    @Test
    fun `cronet's protocol error is interception`() {
        val failure = IOException(
            "Exception in CronetUrlRequest: net::ERR_SSL_PROTOCOL_ERROR, ErrorCode=11, " +
                "InternalErrorCode=-107, Retryable=false",
        )
        assertTrue(failure.isTlsIntercepted())
    }

    @Test
    fun `a certificate this device cannot verify is not interception`() {
        val failure = SSLHandshakeException(
            "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.",
        )
        assertFalse(failure.isTlsIntercepted())
        assertEquals(AnililiError.NETWORK_TLS_REJECTED, failure.toFailure().error)
    }

    @Test
    fun `interception outside the player gets the network entry`() {
        val failure = SSLException("Unable to parse TLS packet header")
        assertEquals(AnililiError.NETWORK_INTERCEPTED, failure.toFailure().error)
    }
}
