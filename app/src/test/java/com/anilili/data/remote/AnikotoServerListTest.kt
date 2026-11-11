package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AniKoto groups its servers by audio in the wrapping `<div>`, never on the `<li>` that carries the
 * link. The markup below is the shape of the live `ajax/server/list` payload for Shangri-La
 * Frontier Season 2 episode 16 (captured 2026-09-05), trimmed to the attributes that decide the
 * answer: three groups in the site's own order, seven items, not one of them naming its audio.
 */
class AnikotoServerListTest {
    private val serverList = """
        <div class="servers">
          <div class="type" data-type="sub">
            <label>SUB</label>
            <ul>
              <li data-ep-id="115216" data-sv-id="e54" data-link-id="sub-vidstream">Vidstream-2</li>
              <li data-ep-id="115216" data-sv-id="323" data-link-id="sub-hd1">HD-1</li>
              <li data-ep-id="115216" data-sv-id="8e4" data-link-id="sub-vidplay">VidPlay-1</li>
            </ul>
          </div>
          <div class="type" data-type="hsub">
            <label>HSUB</label>
            <ul>
              <li data-ep-id="115216" data-sv-id="8e4" data-link-id="hsub-vidplay">VidPlay-1</li>
            </ul>
          </div>
          <div class="type" data-type="dub">
            <label>DUB</label>
            <ul>
              <li data-ep-id="115216" data-sv-id="e54" data-link-id="dub-vidstream">Vidstream-2</li>
              <li data-ep-id="115216" data-sv-id="323" data-link-id="dub-hd1">HD-1</li>
              <li data-ep-id="115216" data-sv-id="8e4" data-link-id="dub-vidplay">VidPlay-1</li>
            </ul>
          </div>
        </div>
    """.trimIndent()

    /** The regression: a dub request used to answer with the sub group, whichever came first. */
    @Test
    fun `each audio gets its own group`() {
        assertEquals("dub-vidstream", anikotoServerLink(serverList, "dub"))
        assertEquals("sub-vidstream", anikotoServerLink(serverList, "sub"))
    }

    @Test
    fun `the preferred server is picked within the group, not across the page`() {
        // e54 exists in every group, so a page-wide search always found the first group's.
        val dubFirst = serverList.replace("data-type=\"sub\"", "data-type=\"zzz\"")
        assertEquals("dub-vidstream", anikotoServerLink(dubFirst, "dub"))
    }

    /**
     * Borrowing another group's stream is worse than reporting nothing: it plays, so the caller
     * accepts it, and the viewer hears Japanese with DUB on the card.
     */
    @Test
    fun `an audio the site does not list answers null`() {
        val subOnly = serverList.substringBefore("""<div class="type" data-type="hsub">""")
        assertEquals("sub-vidstream", anikotoServerLink(subOnly, "sub"))
        assertNull(anikotoServerLink(subOnly, "dub"))
    }

    /** Ungrouped markup means the grouping is gone, not the server; keep the old whole-page scan. */
    @Test
    fun `markup with no groups still answers`() {
        val flat = """
            <ul>
              <li data-sv-id="323" data-link-id="only-hd1">HD-1</li>
              <li data-sv-id="e54" data-link-id="only-vidstream">Vidstream-2</li>
            </ul>
        """.trimIndent()
        assertEquals("only-vidstream", anikotoServerLink(flat, "dub"))
    }

    @Test
    fun `an empty group answers null rather than reaching past it`() {
        val emptyDub = serverList.replace(
            Regex("""<li[^>]*data-link-id="dub-[^"]*"[^>]*>[^<]*</li>"""),
            "",
        )
        assertNull(anikotoServerLink(emptyDub, "dub"))
    }
}
