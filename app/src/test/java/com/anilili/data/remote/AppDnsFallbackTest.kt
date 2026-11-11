package com.anilili.data.remote

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [AppDns] decides whether to fall back to the network's own resolver by reading the message on the
 * exception [DnsOverHttps] threw, because that is the only place the library records the difference
 * between "the resolver says this name does not exist" and "the resolver never answered".
 *
 * Reading a library's exception text is a thing that breaks quietly on an upgrade — the fallback
 * would simply stop happening, and the symptom would be the app going dead on a network that blocks
 * the chosen resolver. So the classification is checked against a real [DnsOverHttps] rather than
 * against a hand-written string, and an okhttp upgrade that reworded either case fails here.
 */
class AppDnsFallbackTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a resolver that says the name does not exist is believed`() {
        server.dispatcher = answering(NXDOMAIN)

        val error = lookupFailure(resolver(), "blocked.example.com")

        assertNull("A decoded answer carries no transport failure", error.cause)
        assertTrue(
            "AppDns would fall back to the ISP resolver for a name that genuinely does not " +
                "exist, and connect to whatever it parks there: ${error.message}",
            AppDns.isNameDenied(error),
        )
    }

    @Test
    fun `a resolver that fails the query is not`() {
        // SERVFAIL is the resolver admitting it could not answer, which is a reason to ask someone
        // else rather than to report the host as missing.
        server.dispatcher = answering(SERVFAIL)

        val error = lookupFailure(resolver(), "example.com")

        assertFalse(AppDns.isNameDenied(error))
    }

    @Test
    fun `a resolver that cannot be reached is not`() {
        val url = server.url("/dns-query")
        // Nothing is listening once the server is down, which is what a blocked or unreachable
        // resolver looks like from here.
        server.shutdown()

        val error = lookupFailure(
            DnsOverHttps.Builder()
                .client(OkHttpClient())
                .url(url)
                .bootstrapDnsHosts(InetAddress.getByName(url.host))
                .build(),
            "example.com",
        )

        assertNotNull("A transport failure has to stay reachable as the cause", error.cause)
        assertFalse(AppDns.isNameDenied(error))
    }

    private fun resolver(): DnsOverHttps = DnsOverHttps.Builder()
        .client(OkHttpClient())
        .url(server.url("/dns-query"))
        // The loopback address the mock server is on, so the lookup never leaves the machine.
        .bootstrapDnsHosts(InetAddress.getByName(server.hostName))
        .build()

    private fun lookupFailure(resolver: DnsOverHttps, hostname: String): UnknownHostException = try {
        val addresses = resolver.lookup(hostname)
        throw AssertionError("Expected the lookup to fail; it answered $addresses")
    } catch (expected: UnknownHostException) {
        expected
    }

    /** Answers every query — A and AAAA are asked for separately — with the same response code. */
    private fun answering(responseCode: Int) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = MockResponse()
            .setHeader("content-type", "application/dns-message")
            .setBody(Buffer().write(response(responseCode)))
    }

    /**
     * A DNS response header carrying [responseCode], and nothing else: the decoder reads the id and
     * the flags and gives up on a failing code before it reaches any section.
     */
    private fun response(responseCode: Int): ByteString = Buffer().apply {
        writeShort(0) // query id
        writeShort(RESPONSE_FLAGS or responseCode) // response, recursion available, code
        writeShort(1) // question count
        writeShort(0) // answer count
        writeShort(0) // authority count
        writeShort(0) // additional count
    }.readByteString()

    private companion object {
        /** QR and RA set, which is what the decoder checks before it reads the response code. */
        const val RESPONSE_FLAGS = 0x8180
        const val SERVFAIL = 2
        const val NXDOMAIN = 3
    }
}
