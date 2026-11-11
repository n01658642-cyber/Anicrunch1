package com.anilili.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures trimmed from live `__data.json` responses (Frieren, 2026-09-25). */
class AnimeAv1ParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun page(text: String): JsonObject =
        AnimeAv1Parser.pageData(json.parseToJsonElement(text)) ?: error("no page data")

    private val search = """
        {"type":"data","nodes":[null,{"type":"data","data":[{"user":1},null],"uses":{}},
        {"type":"data","data":[{"results":1,"total":17},[2,9],
        {"id":3,"title":4,"categoryId":5,"slug":6,"category":7},"3560","Sousou no Frieren 2nd Season",1,
        "sousou-no-frieren-2nd-season",{"id":5,"slug":8},"tv-anime",
        {"id":10,"title":11,"categoryId":5,"slug":12,"category":7},"159","Sousou no Frieren","sousou-no-frieren"]}]}
    """.trimIndent()

    private val media = """
        {"type":"data","nodes":[null,{"type":"data","data":[{"user":1},null]},
        {"type":"data","data":[{"media":1},{"id":2,"title":3,"malId":4,"episodes":5,"nextDate":-1},
        159,"Sousou no Frieren",52991,[6,9,12,15],{"id":7,"number":8},2047,2,{"id":10,"number":11},2051,3,
        {"id":13,"number":14},2060,1,{"id":16,"number":17},2099,12.5]}]}
    """.trimIndent()

    private val episode = """
        {"type":"data","nodes":[null,{"type":"data","data":[{"user":1},null]},
        {"type":"data","data":[{"episode":1,"embeds":5},{"number":2,"variants":3},1,{"DUB":4,"SUB":4},1,
        {"SUB":6,"DUB":13},[7,10],{"server":8,"url":9},"UPNShare","https://animeav1.uns.bio/#i9i8gq",
        {"server":11,"url":12},"MP4Upload","https://www.mp4upload.com/embed-cujiyz2jez93.html",
        [14],{"server":15,"url":16},"YourUpload","https://www.yourupload.com/embed/a31XCPFKNP55"]}]}
    """.trimIndent()

    @Test
    fun `search hits come out of the flattened page in order`() {
        assertEquals(
            listOf(
                AnimeAv1Parser.Hit("sousou-no-frieren-2nd-season", "Sousou no Frieren 2nd Season"),
                AnimeAv1Parser.Hit("sousou-no-frieren", "Sousou no Frieren"),
            ),
            AnimeAv1Parser.searchResults(page(search)),
        )
    }

    @Test
    fun `a title page gives its MAL id and whole-numbered episodes in order`() {
        val detail = AnimeAv1Parser.media(page(media))

        assertEquals(52991, detail.malId)
        // 12.5 is a recap the app's whole-number episode lists have no row for.
        assertEquals(listOf(1, 2, 3), detail.episodes)
    }

    @Test
    fun `negative references read as absent`() {
        val media = page(media)["media"] as JsonObject
        assertTrue(media["nextDate"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `an episode page names its variants and its embeds per category`() {
        val page = page(episode)

        assertEquals(setOf("SUB", "DUB"), AnimeAv1Parser.variants(page))
        assertEquals(listOf("UPNShare", "MP4Upload"), AnimeAv1Parser.embeds(page, "SUB").map { it.server })
        assertEquals(
            listOf(AnimeAv1Parser.Embed("YourUpload", "https://www.yourupload.com/embed/a31XCPFKNP55")),
            AnimeAv1Parser.embeds(page, "DUB"),
        )
    }

    @Test
    fun `only the hosts the app can read are ranked`() {
        assertEquals(0, AnimeAv1Parser.hostRank("Voe"))
        assertEquals(1, AnimeAv1Parser.hostRank("YourUpload"))
        assertEquals(2, AnimeAv1Parser.hostRank("MP4Upload"))
        assertEquals(Int.MAX_VALUE, AnimeAv1Parser.hostRank("UPNShare"))
    }

    @Test
    fun `yourupload's file is its og video, unless it is the placeholder`() {
        assertEquals(
            "https://vidcache.net:8161/a2026/video.mp4",
            AnimeAv1Parser.ogVideo("""<meta property="og:video" content="https://vidcache.net:8161/a2026/video.mp4">"""),
        )
        assertNull(AnimeAv1Parser.ogVideo("""<meta property="og:video" content="https://www.yourupload.com/embed/novideo.mp4">"""))
        assertNull(AnimeAv1Parser.ogVideo("<html></html>"))
    }

    @Test
    fun `voe's encoded config decodes to its hls source`() {
        // Encoded the way VOE encodes it, junk tokens included.
        val page = "<html><script type=\"application/json\">[\"CR1THKb0pR#&9TJGELnISK^^HTyEE1W9IT1ZBHkJKKMIJ2DmGHMCoTcKM30mpR98JHcasHynMKyHJ119r3OzAQuTG3u7sSggGIcprHuUHUyZsJM2nmIComunMUR4Jy15IQMzo1IkG297FzM3FHcbomufMJ5EAH95pa1zryIYM3WAoSWfJQIpsSx2MK1AsTt\"]</script></html>"
        val config = AnimeAv1Parser.voeConfig(json, page)

        assertEquals(
            "https://cdn.example.test/engine/hls2/01/abc_,n,.urlset/master.m3u8",
            config?.let(AnimeAv1Parser::voeSource),
        )
    }

    @Test
    fun `voe's stub page is followed to the domain of the day`() {
        // The live stub's shape (2026-09-25): it reads the property before it assigns it.
        val stub = """
            <script>
                const currentUrl = new URL(window.location.href);
                currentUrl.searchParams.set('adb', '1');
                window.location.href = currentUrl.toString();
                window.location.href = 'https://jamesbornmain.com/e/ouxefu5mtmb4';
            </script>
        """.trimIndent()
        assertNull(AnimeAv1Parser.voeConfig(json, stub))
        assertEquals("https://jamesbornmain.com/e/ouxefu5mtmb4", AnimeAv1Parser.voeRedirect(stub))
    }

    @Test
    fun `a page without data nodes yields nothing`() {
        assertNull(AnimeAv1Parser.pageData(json.parseToJsonElement("""{"type":"redirect","location":"/"}""")))
    }
}
