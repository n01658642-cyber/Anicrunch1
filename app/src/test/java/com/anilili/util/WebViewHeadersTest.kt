package com.anilili.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewHeadersTest {

    @Test
    fun `header maps handed to a WebView survive having entries removed`() {
        // This is the whole point of the helper. Chromium removes the referer from the map it is
        // given instead of copying it first, and a Kotlin mapOf("Referer" to x) is a
        // Collections.singletonMap whose remove throws UnsupportedOperationException — which took
        // the app down on Android 7.1.1 TV boxes.
        val headers = webViewRefererHeaders("https://kwik.si/")

        assertEquals("https://kwik.si/", headers["Referer"])
        headers.remove("Referer")
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `no referer still yields a map that can be written to`() {
        val headers = webViewRefererHeaders(null)

        assertTrue(headers.isEmpty())
        headers["Referer"] = "https://example.test/"
        assertEquals(1, headers.size)
    }

    @Test
    fun `a blank referer is not sent as a header`() {
        assertTrue(webViewRefererHeaders("   ").isEmpty())
    }

    @Test
    fun `copied headers leave the caller's map alone`() {
        val original = mapOf("Referer" to "https://example.test/", "Origin" to "https://example.test")
        val copy = webViewHeaders(original)

        copy.remove("Referer")

        assertEquals(1, copy.size)
        assertEquals(2, original.size)
    }
}
