package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeDekhoParserTest {
    @Test
    fun `search results read the block link and decoded title`() {
        val html = """
            <article class="post dfx fcl movies">
              <header><h2 class="entry-title">Frieren: Beyond Journey&#8217;s End</h2></header>
              <a href="https://animedekho.app/series-hindi/frieren-hindi/" class="lnk-blk"></a>
            </article>
        """.trimIndent()

        val result = AnimeDekhoParser.parseSearch(html).single()

        assertEquals("Frieren: Beyond Journey’s End", result.title)
        assertEquals("https://animedekho.app/series-hindi/frieren-hindi/", result.url)
    }

    @Test
    fun `season lists keep their own episode numbering`() {
        val html = """
            <div class="seasons-bx" id="0">
              <div class="seasons-tt"><p>Season <span>1</span></p><span class="date">September 29, 2023</span></div>
              <ul class="seasons-lst anm-a">
                <li><h3 class="title"><span>S1-E1</span> The Journey's End</h3><a href="https://animedekho.app/epi/frieren-1x1/">Watch</a></li>
                <li><h3 class="title"><span>S1-E2</span> Magic</h3><a href="https://animedekho.app/epi/frieren-1x2/">Watch</a></li>
              </ul>
            </div>
            <div class="seasons-bx" id="1">
              <div class="seasons-tt"><p>Season <span>2</span></p><span class="date">10 Episodes</span></div>
              <ul class="seasons-lst anm-a">
                <li><h3 class="title"><span>S2-E1</span> Shall We Go?</h3><a href="https://animedekho.app/epi/frieren-2x1/">Watch</a></li>
              </ul>
            </div>
        """.trimIndent()

        val seasons = AnimeDekhoParser.parseSeasons(html)

        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(2023, seasons[0].year)
        assertEquals(listOf(1, 2), seasons[0].episodes.map { it.number })
        assertEquals(listOf(1), seasons[1].episodes.map { it.number })
        assertEquals(2, AnimeDekhoParser.selectSeason(seasons, 2, 1, 2026)?.number)
        assertEquals(1, AnimeDekhoParser.selectSeason(seasons, null, 2, null)?.number)
        assertEquals(1, AnimeDekhoParser.selectSeason(seasons, null, null, 2023)?.number)
    }

    @Test
    fun `resolver id and iframe URLs support the site's two attributes`() {
        val html = """
            <body class="term-frieren-1x1 term-47842 wp-theme-toronites">
              <iframe src="https://abyssplayer.example/a1"></iframe>
              <iframe data-src="/aaa/pixel/second"></iframe>
              <iframe src="https://abyssplayer.example/a1"></iframe>
            </body>
        """.trimIndent()

        assertEquals("47842", AnimeDekhoParser.termId(html))
        assertEquals(
            listOf("https://abyssplayer.example/a1", "https://animedekho.app/aaa/pixel/second"),
            AnimeDekhoParser.iframeUrls(html, "https://animedekho.app"),
        )
        assertNull(AnimeDekhoParser.termId("<body class=plain>"))
    }

    @Test
    fun `healthy external mirrors lead the site's down placeholder`() {
        val mirrors = listOf(
            "https://animedekho.app/aaa/down/v/dead/",
            "https://vidcloud.example/#third",
            "https://vidmoly.example/embed-second.html",
            "https://abyssplayer.example/first",
            "https://animedekho.app/aaa/pixel/fallback",
            "https://abyssplayer.example/first",
        )

        assertEquals(
            listOf(
                "https://abyssplayer.example/first",
                "https://vidmoly.example/embed-second.html",
                "https://vidcloud.example/#third",
                "https://animedekho.app/aaa/pixel/fallback",
                "https://animedekho.app/aaa/down/v/dead/",
            ),
            AnimeDekhoParser.orderMirrors(mirrors, "https://animedekho.app"),
        )
    }

    @Test
    fun `season hints cover common AniList title forms`() {
        assertEquals(2, AnimeDekhoParser.seasonNumber("Frieren: Beyond Journey's End Season 2"))
        assertEquals(3, AnimeDekhoParser.seasonNumber("Kaguya-sama 3rd Season"))
        assertEquals(2, AnimeDekhoParser.seasonNumber("The Ancient Magus' Bride II"))
        assertEquals(3, AnimeDekhoParser.seasonNumber("KonoSuba 3"))
        assertNull(AnimeDekhoParser.seasonNumber("Frieren: Beyond Journey's End"))
    }
}
