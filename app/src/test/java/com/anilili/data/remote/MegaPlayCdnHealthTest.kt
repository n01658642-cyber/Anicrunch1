package com.anilili.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class MegaPlayCdnHealthTest {
    private val directory = "/anime/05e374aeae418cba14cf28fc22ff2e18/d0c09c0ee397372299e636b04761dd32/"
    private val original = ("https://tx-01.tyrionx.top" + directory + "seg-f1-00025.html?token=a%2Bb&n=1").toHttpUrl()
    private val response = """{"fallback":"xdw5v.qeltrix.top","failed":["tx-01.tyrionx.top"],"checked":15}"""

    @Test fun `empty failed list does not substitute the advertised fallback`() {
        val state = MegaPlayHealthState { 1_000L }
        assertTrue(state.accept("""{"fallback":"xdw5v.qeltrix.top","failed":[]}"""))
        assertEquals(original, state.rewrite(original))
    }

    @Test fun `only the failed host changes and encoded path query and scheme survive`() {
        val state = MegaPlayHealthState { 1_000L }
        assertTrue(state.accept(response))
        assertEquals(original.newBuilder().host("xdw5v.qeltrix.top").build(), state.rewrite(original))
        val other = original.newBuilder().host("tx-02.tyrionx.top").build()
        assertEquals(other, state.rewrite(other))
    }

    @Test fun `failed host remains sticky after recovery report then expires even without refresh`() {
        var now = 0L
        val state = MegaPlayHealthState { now }
        state.accept(response)
        now = 30_000
        state.accept("""{"fallback":"xdw5v.qeltrix.top","failed":[]}""")
        assertEquals("xdw5v.qeltrix.top", state.rewrite(original).host)
        now = 300_000
        assertEquals(original, state.rewrite(original))
    }

    @Test fun `a repeated failed report extends its lifetime and uses a rotated fallback`() {
        var now = 0L
        val state = MegaPlayHealthState { now }
        state.accept(response)
        now = 250_000
        state.accept(response.replace("xdw5v.qeltrix.top", "next.qeltrix.top"))
        now = 300_000
        assertEquals("next.qeltrix.top", state.rewrite(original).host)
        now = 550_000
        assertEquals(original, state.rewrite(original))
    }

    @Test fun `malformed and oversized responses cannot overwrite last good state`() {
        val state = MegaPlayHealthState { 1_000L }
        state.accept(response)
        listOf("<html>offline</html>", "{}", "x".repeat(16_385),
            """{"fallback":"other.top","failed":"tx-01.tyrionx.top"}""").forEach {
            assertFalse(state.accept(it))
            assertEquals("xdw5v.qeltrix.top", state.rewrite(original).host)
        }
    }

    @Test fun `health hosts cannot be URLs addresses credentials or private hostnames`() {
        val state = MegaPlayHealthState { 1_000L }
        listOf("https://other.top/path", "127.0.0.1", "[::1]", "localhost", "router.local",
            "service.internal", "host.test", "user@other.top", "other.top:443", "other.top/a").forEach { host ->
            assertFalse(host, state.accept(response.replace("xdw5v.qeltrix.top", host)))
        }
        assertEquals(original, state.rewrite(original))
    }

    @Test fun `fallback never rewrites itself`() {
        val state = MegaPlayHealthState { 1_000L }
        state.accept(response.replace("xdw5v.qeltrix.top", "tx-01.tyrionx.top"))
        assertEquals(original, state.rewrite(original))
    }

    @Test fun `eligibility includes all MegaPlay media resources but excludes other traffic`() {
        for (filename in listOf("master.m3u8", "index-f1.m3u8", "seg-f1-00025.html", "subtitles/en.vtt")) {
            assertTrue(isMegaPlayMedia(("https://cdn.example" + directory + filename).toHttpUrl(), "https://megaplay.buzz/"))
        }
        assertFalse(isMegaPlayMedia(original, null))
        assertFalse(isMegaPlayMedia(original, "https://other.example/"))
        assertFalse(isMegaPlayMedia(original, "https://megaplay.buzz.evil.example/"))
        assertFalse(isMegaPlayMedia(original.newBuilder().scheme("http").build(), "https://megaplay.buzz/"))
        assertFalse(isMegaPlayMedia(original.newBuilder().port(8443).build(), "https://megaplay.buzz/"))
        assertFalse(isMegaPlayMedia("https://megaplay.buzz/lib/check_domain.json".toHttpUrl(), "https://megaplay.buzz/"))
        assertFalse(isMegaPlayMedia("https://other.example/anime/private".toHttpUrl(), "https://megaplay.buzz/"))
    }
}
