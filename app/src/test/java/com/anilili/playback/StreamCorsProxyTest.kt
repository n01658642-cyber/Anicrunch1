package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamCorsProxyTest {

    /**
     * The encoding is the whole contract with somebody else's server: an off-by-one in the key,
     * the padding or the separator produces a URL that is well-formed and answers 500, which reads
     * as "the proxy is down" rather than "we built the address wrong".
     *
     * This exact URL was fetched live on 2026-09-07 and returned 200 with the playlist rewritten,
     * so a failure here is a change in this code, not a change upstream.
     */
    @Test
    fun `a gated playlist encodes to the address the proxy answers`() {
        val url = StreamCorsProxy.playlist(
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            referer = "https://megaplay.buzz/",
            seed = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        )

        assertEquals(
            "https://s1.watami.win/zTlM7GtoUrCngoMQE1SZzMAsVe82Pwjn_YOVEhFf3ojdJULmNypOqauPih4QSt7LnQ" +
                "~zTlM7GtoUrC-gpcFTkuMx4svTeZifQ/pl.m3u8",
            url,
        )
    }

    /** A source that names no referer still gains CORS, so the referer half is simply absent. */
    @Test
    fun `no referer leaves the separator off entirely`() {
        val url = checkNotNull(StreamCorsProxy.playlist("https://cdn.example/a.m3u8", null, "seed-a"))

        assertFalse(url.contains('~'))
        assertTrue(url.endsWith("/pl.m3u8"))
        assertEquals(
            StreamCorsProxy.playlist("https://cdn.example/a.m3u8", "   ", "seed-a"),
            url,
        )
    }

    @Test
    fun `subtitles use the track endpoint`() {
        val url = checkNotNull(
            StreamCorsProxy.subtitle("https://cdn.example/en.vtt", "https://cdn.example/", "seed-a"),
        )

        assertTrue(url.endsWith("/sub.vtt"))
        assertTrue(StreamCorsProxy.isProxied(url))
    }

    /**
     * Wrapping an already-proxied URL would encode the proxy's own address as the target, which
     * the proxy then fetches from itself.
     */
    @Test
    fun `an already proxied url is returned untouched`() {
        val once = checkNotNull(StreamCorsProxy.playlist("https://cdn.example/a.m3u8", null, "seed-a"))

        assertEquals(once, StreamCorsProxy.playlist(once, "https://cdn.example/", "seed-b"))
    }

    /** Inline and local media have no origin to gate on, and a receiver cannot reach them anyway. */
    @Test
    fun `unroutable schemes are declined rather than mangled`() {
        assertNull(StreamCorsProxy.playlist("data:application/x-mpegURL;base64,QQ==", null, "s"))
        assertNull(StreamCorsProxy.playlist("blob:https://cdn.example/abc", null, "s"))
        assertNull(StreamCorsProxy.playlist("file:///storage/emulated/0/a.mp4", null, "s"))
        assertNull(StreamCorsProxy.subtitle("content://downloads/1", null, "s"))
    }

    /**
     * The site spreads streams across both origins by hashing; matching that keeps our traffic
     * shaped like its own and keeps one stream pinned to one host's cache.
     */
    @Test
    fun `the origin is chosen by seed and is stable`() {
        assertEquals("https://s1.watami.win/", StreamCorsProxy.originFor("seed-a"))
        assertEquals("https://s1.piltover.li/", StreamCorsProxy.originFor("seed-b"))
        assertEquals(StreamCorsProxy.originFor("bee|123"), StreamCorsProxy.originFor("bee|123"))
    }

    @Test
    fun `only the proxy origins count as proxied`() {
        assertTrue(StreamCorsProxy.isProxied("https://s1.watami.win/abc/pl.m3u8"))
        assertTrue(StreamCorsProxy.isProxied("https://s1.piltover.li/abc/sub.vtt"))
        assertFalse(StreamCorsProxy.isProxied("https://s1.watami.win.evil.example/abc/pl.m3u8"))
        assertFalse(StreamCorsProxy.isProxied("https://cdn.example/a.m3u8"))
    }
}
