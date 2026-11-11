package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * BabaStream is 2Dhive's only remaining server on most episodes, and everything about reaching it
 * is read off the embed page rather than written down: the session, the AES parameters and the API
 * paths all come from markup that a redeploy can reshape. The fixtures here are the live page's
 * own shapes, captured 2026-09-09 from `babastream.top/embed/21/1/sub`.
 */
class BabaStreamParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The page declares four `var x = {…}` objects. Only the first is JSON; the rest are JavaScript
     * literals with unquoted keys and `await` calls in them, which is why the config is identified
     * by carrying `sid` and `pk` rather than by being the first match or by its variable name.
     */
    private val page = """
        <script>
        var CFG = {"mal":"21","ep":"1","sub":"sub","sid":"ce172667-7554-45ce-a115-371f3ac2098f","pk":"CaeyVfJJTrobktj+9dYgMvdjMMx4viXBxlLOW2+xOAM=","cap":"https://hostinger.babastream.top/6c06a92a15/"};
        var lastTap = { time: 0, x: 0 };
        async function d(){ var body = { s: CFG.sid, d: await encAes(CFG.pk, JSON.stringify({ts: Date.now()})) };
          const r = await fetch("/api/download-token", {method:"POST"});
          const q = await fetch("/api/resolve", {method:"POST", body:JSON.stringify(body)});
          const v = await fetch("/api/cap-verify", {method:"POST"});
        }
        const key = await crypto.subtle.importKey("raw", b64d(k), {name:"AES-GCM"}, false, ["encrypt","decrypt"]);
        const iv = crypto.getRandomValues(new Uint8Array(12));
        const ct = await crypto.subtle.encrypt({name:"AES-GCM",iv:iv}, key, data);
        </script>
    """.trimIndent()

    @Test
    fun `the session is picked out of the page's several object literals`() {
        val config = babaStreamConfig(page, json)
        assertEquals("ce172667-7554-45ce-a115-371f3ac2098f", config?.sid)
        assertEquals("CaeyVfJJTrobktj+9dYgMvdjMMx4viXBxlLOW2+xOAM=", config?.pk)
        assertEquals("https://hostinger.babastream.top/6c06a92a15/", config?.capBase)
    }

    /** A page that has stopped declaring a session is a page this cannot talk to; say so. */
    @Test
    fun `a page with no session yields nothing rather than a half-built one`() {
        assertNull(babaStreamConfig("""<script>var CFG = {"mal":"21"};</script>""", json))
        assertNull(babaStreamConfig("<html></html>", json))
        // A session naming no key cannot encrypt a request, so it is not a session.
        assertNull(babaStreamConfig("""<script>var C = {"sid":"abc","cap":"x"};</script>""", json))
    }

    /** Cap is optional in the shape; without it there is nothing to solve and nothing to verify. */
    @Test
    fun `a session without a cap endpoint still parses`() {
        val config = babaStreamConfig("""<script>var C = {"sid":"a","pk":"b"};</script>""", json)
        assertEquals("a", config?.sid)
        assertNull(config?.capBase)
    }

    /**
     * `/api/download-token` is fetched before either of these on the live page, so taking the first
     * `fetch` would post the resolve payload to the wrong endpoint.
     */
    @Test
    fun `routes are matched by purpose, not by position`() {
        val routes = babaStreamRoutes(page)
        assertEquals("/api/resolve", routes.resolve)
        assertEquals("/api/cap-verify", routes.verify)
    }

    @Test
    fun `a page naming no routes reports none`() {
        val routes = babaStreamRoutes("<html></html>")
        assertNull(routes.resolve)
        assertNull(routes.verify)
    }

    @Test
    fun `the AES shape is read from the page's own crypto calls`() {
        assertEquals(BabaStreamCrypto(ivLength = 12, tagBits = 128), babaStreamCrypto(page))
    }

    /** Web Crypto omits `tagLength` when it is the 128-bit default, which is the live case. */
    @Test
    fun `an explicit tag length is honoured`() {
        val explicit = page.replace(""",iv:iv}""", """,iv:iv,tagLength:96}""")
        assertEquals(BabaStreamCrypto(ivLength = 12, tagBits = 96), babaStreamCrypto(explicit))
    }

    /**
     * The wire format below — IV first, tag last — is GCM's. A page that moved to CBC would decrypt
     * to garbage rather than fail, so the algorithm is checked rather than assumed.
     */
    @Test
    fun `a page that is not AES-GCM is refused`() {
        assertNull(babaStreamCrypto(page.replace("AES-GCM", "AES-CBC")))
        assertNull(babaStreamCrypto("<html></html>"))
    }

    /** An IV length outside GCM's range would be a misread rather than a new parameter. */
    @Test
    fun `an implausible IV length is refused`() {
        assertNull(babaStreamCrypto(page.replace("new Uint8Array(12)", "new Uint8Array(0)")))
    }

    /**
     * The live answer is `{"t":"direct","u":"…mp4?Expires=…"}` — a signed progressive file. `t` says
     * how it was resolved, not what it is, so a playlist served under `direct` must still be HLS.
     */
    @Test
    fun `the stream type comes from the URL, not the label`() {
        val mp4 = "https://us-icbuim-file.oss-us-east-1.aliyuncs.com/saFile/057d95c3.mp4?Expires=1789052670"
        assertEquals("mp4", babaStreamMediaType(mp4, "direct"))
        assertEquals("hls", babaStreamMediaType("https://x.test/master.m3u8", "direct"))
        assertEquals("hls", babaStreamMediaType("https://x.test/master.m3u8?token=1", "direct"))
        assertEquals("embed", babaStreamMediaType("https://x.test/player/1", "embed"))
        assertEquals("mp4", babaStreamMediaType("https://x.test/video", null))
    }
}
