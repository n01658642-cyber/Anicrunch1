package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markup here is copied from live animekhor.org pages, trimmed to the parts the parser reads. */
class AnimekhorParserTest {
    @Test
    fun `search results carry the type badge that separates donghua from comics`() {
        val html = """
            <div class="listupd">
            <article class="bs" itemscope="itemscope">
              <div class="bsx">
                <a href="https://animekhor.org/anime/battle-through-the-heavens-season-4/" itemprop="url" title="Battle Through the Heavens Season 4" class="tip" rel="65760">
                  <div class="limit"><div class="status Completed">Completed</div><div class="typez ONA">ONA</div></div>
                </a>
              </div>
            </article>
            <article class="bs" itemscope="itemscope">
              <div class="bsx">
                <a href="https://animekhor.org/anime/battle-through-the-heavens-comics/" itemprop="url" title="Battle Through the Heavens Comics" class="tip">
                  <div class="limit"><div class="typez Comic">Comic</div></div>
                </a>
              </div>
            </article>
            </div>
        """.trimIndent()

        val results = AnimekhorParser.parseSearch(html)

        assertEquals(2, results.size)
        assertEquals("Battle Through the Heavens Season 4", results[0].title)
        assertEquals(
            "https://animekhor.org/anime/battle-through-the-heavens-season-4/",
            results[0].url,
        )
        assertTrue(!results[0].isComic)
        assertTrue(results[1].isComic)
    }

    @Test
    fun `episode labels cover every numbering style the catalogue uses`() {
        assertEquals(listOf(207), AnimekhorParser.episodeNumbers("207"))
        assertEquals(listOf(637, 638), AnimekhorParser.episodeNumbers("637 to 638"))
        assertEquals((1..12).toList(), AnimekhorParser.episodeNumbers("1 to 12"))
        assertEquals(listOf(380), AnimekhorParser.episodeNumbers("S4 E380"))
        assertEquals(listOf(378, 379), AnimekhorParser.episodeNumbers("S4 E378-379"))
        assertEquals(listOf(290), AnimekhorParser.episodeNumbers("S2E290"))
        assertEquals(emptyList<Int>(), AnimekhorParser.episodeNumbers("Special"))
    }

    @Test
    fun `an implausible span is read as a single episode rather than a range`() {
        // A label that pairs an episode with a year would otherwise expand to a thousand entries
        // and drown out the real ones.
        assertEquals(listOf(12), AnimekhorParser.episodeNumbers("12 (2024)"))
    }

    @Test
    fun `both ends of a batched range resolve to the page that holds it`() {
        val html = """
            <div class="eplister">
              <div class="ephead"><div class="eph-num">Ep</div></div>
              <ul>
                <li data-index="0">
                  <a href="https://animekhor.org/god-of-martial-arts-episode-637-to-638/"><div class="epl-num">637 to 638</div><div class="epl-title">Episode 637 to 638</div></a>
                </li>
                <li data-index="1">
                  <a href="https://animekhor.org/god-of-martial-arts-episode-636/"><div class="epl-num">636</div><div class="epl-title">Episode 636</div></a>
                </li>
              </ul>
            </div>
        """.trimIndent()

        val episodes = AnimekhorParser.parseEpisodes(html)

        assertEquals(setOf(636, 637, 638), episodes.keys)
        assertEquals(episodes[637], episodes[638])
        assertEquals("https://animekhor.org/god-of-martial-arts-episode-636/", episodes[636])
    }

    @Test
    fun `mirrors are decoded from the base64 option values`() {
        val okRu =
            "PGlmcmFtZSB3aWR0aD0iNjQwIiBoZWlnaHQ9IjM2MCIgc3JjPSIvL29rLnJ1L3ZpZGVvZW1iZWQvMTYzOTU0" +
                "MjQxNzI2NDAiIGZyYW1lYm9yZGVyPSIwIiBhbGxvd2Z1bGxzY3JlZW4+PC9pZnJhbWU+"
        val dailymotion =
            "PGRpdiBzdHlsZT0icG9zaXRpb246cmVsYXRpdmUiPiA8aWZyYW1lIHNyYz0iaHR0cHM6Ly93d3cuZGFpbHlt" +
                "b3Rpb24uY29tL2VtYmVkL3ZpZGVvL2s0eFJ3Z0FGeWNXZjVPSEg1SGciIGFsbG93ZnVsbHNjcmVlbj4g" +
                "PC9pZnJhbWU+IDwvZGl2Pg=="
        val html = """
            <div class="mobius">
            <select class="mirror" name="mirror" onchange="loadMi(this);">
              <option value="">Select Video Server</option>
              <option value="$dailymotion" data-index="1">
            VidPlayer [MULTI SUB]</option>
              <option value="$okRu" data-index="2">
            ok.ru&#12298;Ads Free&#12299; [ENGLISH]</option>
            </select>
            </div>
        """.trimIndent()

        val mirrors = AnimekhorParser.parseMirrors(html)

        assertEquals(2, mirrors.size)
        assertEquals("VidPlayer [MULTI SUB]", mirrors[0].label)
        assertEquals(
            "https://www.dailymotion.com/embed/video/k4xRwgAFycWf5OHH5Hg",
            mirrors[0].url,
        )
        // Protocol-relative sources are the site's own habit and no player can open one.
        assertEquals("https://ok.ru/videoembed/16395424172640", mirrors[1].url)
    }

    @Test
    fun `a page without a mirror select falls back to its inline player`() {
        val html = """
            <div class="player-embed">
              <iframe src="https://emturbovid.com/t/6a524624e82b3" frameborder="0"></iframe>
            </div>
        """.trimIndent()

        val mirror = AnimekhorParser.parseMirrors(html).single()

        assertEquals("https://emturbovid.com/t/6a524624e82b3", mirror.url)
        assertEquals("Emturbovid", mirror.label)
    }
}
