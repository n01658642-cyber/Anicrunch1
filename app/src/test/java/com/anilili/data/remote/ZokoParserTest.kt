package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ONE_PIECE] is the `__P` blob exactly as `/stream/mal/21/1/sub` served it on 2026-09-22.
 * [SYNTHETIC] was produced with the site's own `obfuscate()` over a hand-written payload, to pin
 * what the live one does not exercise: a non-default first track, no skip times and non-ASCII text.
 */
class ZokoParserTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun page(blob: String) =
        """<div id="player"></div><script>window.__P="$blob"</script><script type="module" src="x"></script>"""

    @Test
    fun `the live payload decodes to its stream, subtitle and skip windows`() {
        val payload = ZokoParser.payload(page(ONE_PIECE), json)!!
        val result = ZokoParser.sources(payload)

        val stream = result.streams.single()
        assertEquals(
            "https://hls.1embed.buzz/v/scxqicy/ta9t4m1bhw/cm72fkvbpx/09qmw7k6mi5nzd/master.m3u8",
            stream.url,
        )
        assertTrue(stream.isHls)
        // The playlists 403 without it; see ZokoProvider.
        assertEquals("https://zokoanime.video/", stream.referer)

        val subtitle = result.subtitles.single()
        assertEquals("English", subtitle.label)
        assertEquals("en", subtitle.language)
        assertTrue(subtitle.url.endsWith("/subs/6e4a0p3k8ehe9eee.vtt"))

        val skip = result.skip!!
        assertEquals(31.0, skip.introStart!!, 0.0)
        assertEquals(111.0, skip.introEnd!!, 0.0)
        assertEquals(1376.0, skip.outroStart!!, 0.0)
        assertEquals(1447.0, skip.outroEnd!!, 0.0)
    }

    @Test
    fun `the default track leads and each language comes from the label, not the lang field`() {
        val payload = ZokoParser.payload(page(SYNTHETIC), json)!!
        val subtitles = ZokoParser.sources(payload).subtitles

        assertEquals(listOf("English", "Arabic (Arabic - [Full])", "Portuguese (Portuguese - Brazilian [Full])"), subtitles.map { it.label })
        assertEquals(listOf("en", "ar", "pt"), subtitles.map { it.language })
    }

    @Test
    fun `non-ASCII text survives the XOR because it runs over UTF-8 bytes`() {
        val payload = ZokoParser.payload(page(SYNTHETIC), json)!!

        assertEquals("\"葬送のフリーレン\"", payload["title"].toString())
    }

    @Test
    fun `a title with no skip windows leaves skip null so AniSkip gets its turn`() {
        assertNull(ZokoParser.sources(ZokoParser.payload(page(SYNTHETIC), json)!!).skip)
    }

    @Test
    fun `the not-found card is a miss, not an error`() {
        // A missing episode is still HTTP 200; only the absent payload says so.
        val notFound = """<div class="card"><h1 class="title">Not <span class="stroke">found.</span></h1></div>"""

        assertFalse(ZokoParser.hasPayload(notFound))
        assertNull(ZokoParser.payload(notFound, json))
        assertTrue(ZokoParser.hasPayload(page(ONE_PIECE)))
    }

    @Test
    fun `a changed key reads as no payload rather than a garbage stream`() {
        val wrongKey = page("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=")

        assertNull(ZokoParser.payload(wrongKey, json))
    }

    @Test
    fun `labels map to ISO codes by their first word`() {
        assertEquals("es", ZokoParser.language("Spanish (- Castilian [Full])"))
        assertEquals("zh", ZokoParser.language("Chinese (Chinese - (Simplified) [Full])"))
        assertEquals("en", ZokoParser.language("English 2"))
        assertEquals("en", ZokoParser.language("English (- (Crunchyroll))"))
        assertEquals("und", ZokoParser.language("Klingon"))
    }

    private companion object {
        const val ONE_PIECE =
            "FFYFBAJDCQIDATtYBF1NTkNEEUISAw4KBUlZXA4YTllEAlRCERAGD1oTHAYCSU8PDRkWFRcXWR4HGBJFREgIDwcBSk8DSxVbF0QGTh0cCwYdAgJQVgBVBkRPDRpNBgkaRFcEAgMbDQJVVBMIExodBwIdVAUPSUoAAxYQSAQfAkcUU1cBRx4XBxBEAl0KB0NRLlZHAQMLAw9MEwoaQ0dXQQQPBwlGF1R0ARMNAgZFR0FAAQFLF0QDAENRAV8QCE5HF18VE1VWCR8BXRZXTUoMQQUfXhEMCRBJSw8XHx4CAB4cFxkaHE4cQhYEXVlCXF4WCRxaTghaUAMPWxRBF1tRUgRAEloJUwlEQ18VEE4YAE8WQlQAUExGQVwfWQ4dSFwIBwBKWwJFTQk8R1ddCh4WABYPTBNNWEMYBV8MGQc6ElkCE1VWQ0dXTg0MEhEBXwUTVS88R1dJAAsDEAhZKVAaEAgEVxcLGA4JSA8AWAsRDjQcSUdXUF1UG1oTGR0EHFcXHk8PDApyAVAbFwlJTx5VQUABAUkDQQorCQQAXxZPWFdQUFoTHxgAEhBfR1cZRwBIEFAaGBU0BFgEAQsRHQ9MEw4BFQRXAUcMAQYBQwITVVZCWEBJUA8ER0gPGl4IGz4fEFURT1hHPkIdXi4aCAYQD0lPBAoWWhdDCysSDhZCCwkRR14cRh1NEQ8MHEMAT1hHDloGXQ4NBBlXAUceCQwKD0wTCxEHCgBBEU9ORxdFGUYwEA4cG0EKDAZHXlkERApYQwoAWQoyEQ4NXVQLCRUNGBABRx4JDBRyFEQbAA4FVxcRHxcAGQFUUAwXBAUBD19PQVZRSUNTCVZNSRRYEQISCQVUVAsbBhQOWQ8IGBYAAA9MVw4YEg5ZDxcIERAJSFQLGwYUDlkPFhkDFxByAlgCEUNRRQFHHgkMFA9MSk0dDx8HQkdXGUcXWRdDG1ZbWEQBRwgMAUYXRwBeCU1JGlgRHw1HXlZUQhsVEx9XF1ReVVNIDxNfC1ZbWkEZUhAfSUZMA0UAKxIAHF1HVwQECF4THU0VFB8acgsIGhFGFxBQAwcER1deDgQSOgZYAkUAGkNRAV8QCE5HBl8XXwtWWx8HWAAQ"
        const val SYNTHETIC =
            "FFYSGRYPX08KERBdBQtAWwkHBgNUCA8HAUlYUxoOG0QDAh1CG0oeAhtQHAAEGVtAVhhaR0gPBUQNAAgfGUgWT1g+Hw8aUAETQ1FXSAtPTkcITBRUA1ZbSTRfBA8LBkQFN0MOFggIVQBFNiQQCEErGE1YQw8QSwQYDhFGFxBQAwcER1deFw5AX0ZFAkUfB1tEWkUJHkxUAUAUVAtaAx4PV0obTR1LVFlLQAcUCQYCBB9MExBZVExDD0MHFEMCT1hHAUNUHU0YAAkQQUdXQCAKShpYHBxDR1dJAAsDEAhZVAsbBhQOWQ8WHwFHXg8eRRsEElFaAg0BEUtVSBtTChBPCQBXH0IUShwCDx4VWxIeF15KCAxLElkCExJYGkkZTAsKQF9GSBgTQ1YNChdICU9YRzRCBEUaExQOBkhFRTIKFlkDVhoREg5VAEUvEAQeRBpYDhpBMDNYCQE/TEYBVFUKEgAeGVlHVwQECF4THU0HEwhXF0cFFhEUXkweQBwNGFscAAAAAAADFEQVDk4dWlVKFE0fS14DUxxbER9bWxEZQBg5AVRCBB0RSU9DEAEOSUZZH0UDEUNRV8X0wYvl5c73n4z39Ij2h4bu3obngZWy3FYc"
    }
}
