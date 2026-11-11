package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MegaPlay's source route is read off its own client script rather than written down here, because
 * on 2026-09-09 the route the app had hardcoded stopped returning streams: `stream/getSources`
 * answered with tracks, intro, outro and an encrypted `enc` blob but no `sources` object, while
 * `stream/getSourcesNew` — shipped by the same script — still answered in the clear. The literals
 * below are how both names appear in the live `lib/newclient.min.js`.
 */
class MegaPlayRouteDiscoveryTest {
    /** Verbatim from the live `lib/newclient.min.js?v=4.7`, read 2026-09-09. */
    private val script =
        """(function(r){"use strict";var E="stream/getSources",C="stream/getSourcesNew";function P(g){return!g||"""

    @Test
    fun `both routes are found, the newest first`() {
        assertEquals(listOf("stream/getSourcesNew", "stream/getSources"), megaPlaySourceRoutes(script))
    }

    @Test
    fun `a route named once is not offered twice`() {
        val repeated = """a("stream/getSources");b('stream/getSources');c("stream/getSources")"""
        assertEquals(listOf("stream/getSources"), megaPlaySourceRoutes(repeated))
    }

    /** The script assigns whole routes today, but building the URL inline would hide one. */
    @Test
    fun `a route concatenated into its query string is still found`() {
        val inline = """var u=t+"stream/getSourcesNew?id="+n+"&id="+n;"""
        assertEquals(listOf("stream/getSourcesNew"), megaPlaySourceRoutes(inline))
    }

    @Test
    fun `a script naming no route yields none, so the caller falls back`() {
        assertEquals(emptyList<String>(), megaPlaySourceRoutes("""var a="stream/getTracks";"""))
        assertEquals(emptyList<String>(), megaPlaySourceRoutes(""))
    }

    /**
     * Ordering is by specificity, not by discovery order: a successor is the old name plus a
     * suffix, so the longest is the newest whichever way round the script happens to name them.
     */
    @Test
    fun `a future successor sorts ahead of the routes it succeeds`() {
        val next = """x("stream/getSourcesNew");y("stream/getSources");z("stream/getSourcesNewV3")"""
        assertEquals(
            listOf("stream/getSourcesNewV3", "stream/getSourcesNew", "stream/getSources"),
            megaPlaySourceRoutes(next),
        )
    }

    /** Same-length names would otherwise come back in hash order and make the running order random. */
    @Test
    fun `equally specific routes keep a stable order`() {
        val tie = """a("stream/getSourcesB");b("stream/getSourcesA")"""
        assertEquals(listOf("stream/getSourcesA", "stream/getSourcesB"), megaPlaySourceRoutes(tie))
    }

    private val page = """
        <script src="https://cdn.jsdelivr.net/gh/itspro-dev/project_files@master/jw/hls.js?v=0.2"></script>
        <script src="https://code.jquery.com/jquery-3.6.0.min.js"></script>
        <script src="/lib/app.main.js?v=21788825600"></script>
        <script src="https://megaplay.buzz/lib/newclient.min.js?v=4.7"></script>
    """.trimIndent()

    @Test
    fun `only the embed's own scripts are worth opening, newclient first`() {
        assertEquals(
            listOf(
                "https://megaplay.buzz/lib/newclient.min.js?v=4.7",
                "https://megaplay.buzz/lib/app.main.js?v=21788825600",
            ),
            megaPlayClientScripts(page, "https://megaplay.buzz/stream/ani/21/1/sub"),
        )
    }

    /**
     * jQuery and the CDN's hls.js can neither carry MegaPlay's routes nor be worth the request, and
     * on a metered connection they are the two largest files on the page.
     */
    @Test
    fun `public CDN scripts are never fetched`() {
        val scripts = megaPlayClientScripts(page, "https://megaplay.buzz/stream/ani/21/1/sub")
        assertTrue(scripts.none { it.contains("jsdelivr") || it.contains("code.jquery.com") })
    }

    /** MegaPlay serves the same page from several hosts; the discovery must follow the one in hand. */
    @Test
    fun `scripts are judged against the embed's own origin`() {
        val mirrored = """<script src="https://megaplay.buzz/lib/newclient.min.js"></script>"""
        assertEquals(emptyList<String>(), megaPlayClientScripts(mirrored, "https://other.example/stream/x"))
    }

    @Test
    fun `a page with no scripts asks for nothing`() {
        assertEquals(emptyList<String>(), megaPlayClientScripts("<html><body></body></html>", "https://megaplay.buzz/s"))
    }
}
