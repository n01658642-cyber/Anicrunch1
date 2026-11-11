package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRequestHeadersTest {
    @Test
    fun `flixcloud requests receive browser fetch headers`() {
        val headers = mediaRequestHeaders(
            referer = "https://player.flixcloud.cc/embed/123",
            userAgent = "Mozilla/5.0 Chrome/141.0.0.0 Mobile",
        )

        assertEquals("https://player.flixcloud.cc", headers["Origin"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("cors", headers["Sec-Fetch-Mode"])
        assertTrue(headers.getValue("Sec-CH-UA").contains("141"))
    }

    @Test
    fun `provider headers override derived defaults`() {
        val headers = mediaRequestHeaders(
            referer = "https://player.flixcloud.cc/embed/123",
            userAgent = "Chromium/137",
            extraHeaders = mapOf("Origin" to "https://token.example", "Accept" to "application/x-mpegURL"),
        )

        assertEquals("https://token.example", headers["Origin"])
        assertEquals("application/x-mpegURL", headers["Accept"])
    }

    @Test
    fun `ordinary providers do not receive flixcloud-only headers`() {
        val headers = mediaRequestHeaders(
            referer = "https://provider.example/watch/1",
            userAgent = "Chromium/137",
            extraHeaders = mapOf("Authorization" to "Bearer test"),
        )

        assertEquals("https://provider.example", headers["Origin"])
        assertEquals("Bearer test", headers["Authorization"])
        assertFalse(headers.containsKey("Sec-CH-UA"))
    }

    @Test
    fun `missing referer preserves provider headers only`() {
        val headers = mediaRequestHeaders(
            referer = null,
            userAgent = "Chromium/137",
            extraHeaders = mapOf("Cookie" to "session=test"),
        )

        assertEquals(mapOf("Cookie" to "session=test"), headers)
    }
}
