package com.anilili.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which player a stream goes to. The WebView is the fallback of last resort — it costs the app's
 * own controls, Cast, downloads and subtitle delay — so a stream only goes there when it may really
 * be a page.
 */
class StreamRoutingTest {

    private fun stream(url: String, type: String = "") =
        StreamItem(url, type, quality = null, audio = null, referer = null, isActive = true, width = null, height = null)

    /** Cog's AnimeGG links, as the embed player received them on 2026-09-23. */
    @Test
    fun `a video file from an iframe provider plays natively`() {
        val cog = "https://www.animegg.org/play/462595/video.mp4?for=101788461908710"
        assertFalse(stream(cog).playsInWebView("cog"))
        assertFalse(stream(cog, type = "mp4").playsInWebView("cog"))
        assertFalse(stream("https://cdn.example/file", type = "mp4").playsInWebView("nun"))
    }

    @Test
    fun `an iframe provider's page still goes to the web player`() {
        assertTrue(stream("https://www.animegg.org/embed/133564").playsInWebView("cog"))
        assertTrue(stream("https://bysekoze.com/e/abc", type = "embed").playsInWebView("nun"))
        // A page that names a file in its query is still a page.
        assertTrue(stream("https://host.example/player?file=video.mp4").playsInWebView("cog"))
    }

    @Test
    fun `a typed embed is a page even if its path looks like a file`() {
        assertTrue(stream("https://host.example/v/clip.mp4", type = "embed").playsInWebView("xfdm"))
    }

    @Test
    fun `manifests and ordinary providers are unchanged`() {
        assertFalse(stream("https://host.example/master.m3u8").playsInWebView("nun"))
        assertFalse(stream("https://host.example/stream", type = "dash").playsInWebView("cog"))
        assertFalse(stream("https://host.example/watch/123").playsInWebView("animegg"))
    }
}
