package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProviderParsersTest {
    @Test
    fun parsesNativeEpisodeIds() {
        val request = NativeProviderParsers.episodeRequest("watch/animegg/16498/dub/animegg-7")

        assertEquals("animegg", request?.provider)
        assertEquals(16498, request?.anilistId)
        assertEquals("dub", request?.audio)
        assertEquals(7, request?.episode)
        assertNull(NativeProviderParsers.episodeRequest("https://example.com/not-an-episode"))
    }

    @Test
    fun extractsAttributesAndDecodesEntities() {
        val tag = "<track src=\"https://cdn.test/sub.vtt?a=1&amp;b=2\" label='English'>"

        assertEquals("https://cdn.test/sub.vtt?a=1&b=2", NativeProviderParsers.attr(tag, "src"))
        assertEquals("English", NativeProviderParsers.attr(tag, "label"))
        assertEquals("Attack & Titan", NativeProviderParsers.stripTags("<b>Attack</b> &amp; Titan"))
    }

    @Test
    fun titleMatchingAndHlsDiscoveryHandleProviderMarkup() {
        assertEquals(1.0, NativeProviderParsers.titleScore("Attack on Titan", "Attack on Titan"), 0.0)
        assertTrue(NativeProviderParsers.titleScore("Re:Zero", "ReZero Season 2") > 0.8)
        assertTrue(
            NativeProviderParsers.titleSelectionScore("Jujutsu Kaisen", "Jujutsu Kaisen (TV)") >
                NativeProviderParsers.titleSelectionScore("Jujutsu Kaisen", "Jujutsu Kaisen: The Culling Game Part 1"),
        )
        assertEquals(
            listOf("https://cdn.test/master.m3u8?token=abc"),
            NativeProviderParsers.hlsUrls("file: \"https:\\/\\/cdn.test\\/master.m3u8?token=abc\""),
        )
    }

    @Test
    fun parsesPerEpisodeAudioBadges() {
        val dataAttributes = """
            <a href="/watch/show/ep-1" data-num="1" data-sub="1" data-dub="1">1</a>
            <a href="/watch/show/ep-2" data-num="2" data-hsub="1" data-sub="1" data-dub="0">2</a>
        """.trimIndent()
        val kai = NativeProviderParsers.dataAudioEpisodes(dataAttributes)
        assertEquals(setOf(1, 2), kai.sub)
        assertEquals(setOf(1), kai.dub)

        val animeGg = NativeProviderParsers.animeGgEpisodes(
            """
            <li><a class="anm_det_pop"><strong>Show 7</strong></a><span class="btn-subbed">SUBBED</span></li>
            <li><a class="anm_det_pop"><strong>Show 8</strong></a><span class="btn-dubbed">DUBBED</span></li>
            """.trimIndent(),
        )
        assertEquals(setOf(7), animeGg.sub)
        assertEquals(setOf(8), animeGg.dub)

        val aniNeko = NativeProviderParsers.aniNekoEpisodes(
            """
            <article class="nv-info-episode-item"><a href="/watch/show/ep-3">3</a><span>SUB</span><span>DUB</span></article>
            <article class="nv-info-episode-item"><a href="/watch/show/ep-4">4</a><span>HSUB</span></article>
            """.trimIndent(),
        )
        assertEquals(setOf(3, 4), aniNeko.sub)
        assertEquals(setOf(3), aniNeko.dub)
        assertEquals(1172, NativeProviderParsers.labelledEpisodeCount("<span>1172 Episodes</span>"))
    }

    @Test
    fun `attribute patterns are cached per name without answering for another`() {
        // The patterns are held in a map keyed by attribute name so a page is not thousands of
        // Pattern.compile calls. A key collision would silently return a neighbouring attribute,
        // so ask for several names off one tag, twice, and in a different order each time.
        val tag = """<a href="/watch/1" class="post-thumb" title="Second" aria-label="First">"""

        repeat(2) {
            assertEquals("First", NativeProviderParsers.attr(tag, "aria-label"))
            assertEquals("post-thumb", NativeProviderParsers.attr(tag, "class"))
            assertEquals("/watch/1", NativeProviderParsers.attr(tag, "href"))
            assertEquals("Second", NativeProviderParsers.attr(tag, "title"))
        }
        // `title` must not be served the value of `aria-label`, which ends with it.
        assertEquals("Second", NativeProviderParsers.attr(tag, "title"))
        assertEquals("", NativeProviderParsers.attr(tag, "data-src"))
    }

    @Test
    fun `unquoted attribute values still parse after the quoted pattern misses`() {
        // AniZone emits these, and both patterns for one name share the cache.
        val tag = "<iframe data-src=https://cdn.test/embed/1 width=640>"

        assertEquals("https://cdn.test/embed/1", NativeProviderParsers.attr(tag, "data-src"))
        assertEquals("640", NativeProviderParsers.attr(tag, "width"))
        assertEquals(
            "https://cdn.test/embed/1",
            NativeProviderParsers.attr(tag, "data-src"),
        )
    }

    @Test
    fun `entity decoding skips only strings that cannot contain an entity`() {
        // decodeEntities returns early when the value holds neither '&' nor a backslash, which is
        // most attribute values. Getting that guard wrong would stop decoding silently, so cover
        // both characters it tests for and a value that has neither.
        assertEquals("plain title", NativeProviderParsers.decodeEntities("plain title"))
        assertEquals("a&b", NativeProviderParsers.decodeEntities("a&amp;b"))
        assertEquals("\"quoted\"", NativeProviderParsers.decodeEntities("&quot;quoted&quot;"))
        assertEquals("it's", NativeProviderParsers.decodeEntities("it&#39;s"))
        assertEquals("A", NativeProviderParsers.decodeEntities("&#x41;"))
        assertEquals("https://cdn.test/a", NativeProviderParsers.decodeEntities("https:\\/\\/cdn.test\\/a"))
        assertEquals("", NativeProviderParsers.decodeEntities(""))
    }

    @Test
    fun `season number reads every form these sites number a sequel by`() {
        assertEquals(2, NativeProviderParsers.seasonNumber("Frieren: Beyond Journey's End Season 2"))
        assertEquals(2, NativeProviderParsers.seasonNumber("Naruto Shippuden Season 02 – Episodes"))
        assertEquals(3, NativeProviderParsers.seasonNumber("Kaguya-sama 3rd Season"))
        assertEquals(2, NativeProviderParsers.seasonNumber("The Ancient Magus' Bride II"))
        assertEquals(3, NativeProviderParsers.seasonNumber("KonoSuba 3"))
        assertEquals(2, NativeProviderParsers.seasonNumber("Gintama 2"))
        assertNull(NativeProviderParsers.seasonNumber("Frieren: Beyond Journey's End"))
        assertNull(NativeProviderParsers.seasonNumber("Demon Slayer Mugen Train Movie"))
    }

    /**
     * The guard `AnimeDekhoParser` was missing before this moved here, and the reason the two
     * copies could not simply be deleted down to either one.
     */
    @Test
    fun `a number that is part of the name is not a season`() {
        assertNull(NativeProviderParsers.seasonNumber("Kaiju No. 8"))
        assertNull(NativeProviderParsers.seasonNumber("Mob Psycho 100"))
    }

    @Test
    fun `base series title strips the season an AniList sequel is named for`() {
        assertEquals(
            "Solo Leveling",
            NativeProviderParsers.baseSeriesTitle("Solo Leveling Season 2 -Arise from the Shadow-"),
        )
        assertEquals("Attack on Titan", NativeProviderParsers.baseSeriesTitle("Attack on Titan Final Season"))
        assertEquals("Dandadan", NativeProviderParsers.baseSeriesTitle("Dandadan 2nd Season"))
        assertEquals(
            "Saga of Tanya the Evil",
            NativeProviderParsers.baseSeriesTitle("Saga of Tanya the Evil Season 2"),
        )
        // No marker, no change — which is what lets a caller offer this as a query unconditionally.
        assertEquals("Dandadan", NativeProviderParsers.baseSeriesTitle("Dandadan"))
        assertEquals(
            "Frieren: Beyond Journey's End",
            NativeProviderParsers.baseSeriesTitle("Frieren: Beyond Journey's End"),
        )
    }

    @Test
    fun `season hint takes the first of the AniList names that numbers one`() {
        assertEquals(
            2,
            NativeProviderParsers.seasonHint(listOf("Saga of Tanya the Evil Season 2", "Youjo Senki II")),
        )
        assertNull(NativeProviderParsers.seasonHint(listOf("Dandadan", "Dandadan")))
        assertNull(NativeProviderParsers.seasonHint(emptyList()))
    }
}
