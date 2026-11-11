package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are lifted verbatim from live pages (2026-09-13), including the site's apostrophe-quoted
 * attributes, its stray whitespace inside tags and its valueless `alt` — the parser exists to cope
 * with exactly that, so normalising it here would test something the site never sends.
 */
class AnimeHeavenParserTest {
    @Test
    fun `search cards read the entry path and decode the title`() {
        val html = """
            <div class='info3 bc1'><div class='similarimg'><div class='p1'>
            <a href='anime.php?ak2gr'><img class='coverimg' src='image.php?z4mfi' alt='Frieren: Beyond Journey&#039;s End' loading='lazy'></a>
            <div class='similarname c'><a href='anime.php?ak2gr' class='c'>Frieren: Beyond Journey&#039;s End</a></div>
            </div></div><div class='similarimg'><div class='p1'>
            <a href='anime.php?hgj3i'><img class='coverimg' src='image.php?q2wer' alt='Frieren 2nd Season' loading='lazy'></a>
            <div class='similarname c'><a href='anime.php?hgj3i' class='c'>Frieren 2nd Season</a></div>
            </div></div></div>
        """.trimIndent()

        val results = AnimeHeavenParser.parseSearch(html)

        assertEquals(listOf("anime.php?ak2gr", "anime.php?hgj3i"), results.map { it.path })
        // `&#039;` is U+0027, the straight apostrophe — not the typographic one AnimeDekho's
        // `&#8217;` decodes to. Titles are matched against AniList's by NativeProviderParsers,
        // which normalises punctuation away, so the difference costs nothing at match time.
        assertEquals("Frieren: Beyond Journey's End", results[0].title)
        assertEquals("Frieren 2nd Season", results[1].title)
    }

    @Test
    fun `chart rows on the popular page parse as the same entries`() {
        // popular.php and the front page use `chart` rows rather than `similarimg` cards. The inner
        // `chartimg` / `chartep` / `chartinfo` divs must not be mistaken for another card, and the
        // bookmark button's valueless `alt` must not be read as a title.
        val html = """
            <div class='chart bc1'>			<div class='chartimg'><a href='anime.php?sfvog'><img class='coverimg' src='image.php?b1dg7' alt='Mushoku Tensei: Jobless Reincarnation 3rd Season' loading='lazy'><div class='chartepm bc2 c1'>1</div></a><div id='sfvog' onclick='book(this,"sfvog","0")' class='bc2 c1 book'><img src='add.svg' alt></div></div><div class='chartep c2'>1</div><div class='chartinfo'>			<div class='charttitle c'><a href='anime.php?sfvog' class='c'>Mushoku Tensei: Jobless Reincarnation 3rd Season</a></div></div></div>
        """.trimIndent()

        val results = AnimeHeavenParser.parseSearch(html)

        assertEquals(1, results.size)
        assertEquals("anime.php?sfvog", results[0].path)
        assertEquals("Mushoku Tensei: Jobless Reincarnation 3rd Season", results[0].title)
    }

    @Test
    fun `episodes come back ascending with the gate key that opens them`() {
        val episodes = AnimeHeavenParser.parseEpisodes(EPISODE_ROWS)

        assertEquals(listOf(26, 27, 28), episodes.map { it.number })
        assertEquals("aaef295ca750e9bf2d7077cbc27df63d", episodes.first { it.number == 26 }.gateKey)
        assertEquals("47edc338ae59c34cc3ea427c7f6871df", episodes.first { it.number == 28 }.gateKey)
    }

    @Test
    fun `a row with no episode number is dropped rather than borrowing the next row's`() {
        // The page lists newest first, so a lazy match across the whole document would pair this
        // broken row with the *following* row's number and shift every episode below it by one —
        // a viewer would silently get the wrong episode. Losing the unreadable row is the correct
        // outcome; the ones around it must stay on their own numbers.
        val html = EPISODE_ROWS.replace(
            "<div  class= ' watch2 bc ' >27</div>",
            "<div class='watch1 bc c'>27</div>",
        )

        val episodes = AnimeHeavenParser.parseEpisodes(html)

        assertEquals(listOf(26, 28), episodes.map { it.number })
        assertEquals("aaef295ca750e9bf2d7077cbc27df63d", episodes.first { it.number == 26 }.gateKey)
        assertEquals("47edc338ae59c34cc3ea427c7f6871df", episodes.first { it.number == 28 }.gateKey)
    }

    @Test
    fun `gate mirrors keep the page's own order and drop the repeat`() {
        val html = """
            <video id='vid' onloadeddata='unm2()' class='videodiv' controls autoplay playsinline crossOrigin='anonymous'>
            <source src='https://ax.animeheaven.me/video.mp4?47edc338ae59c34cc3ea427c7f6871df&62e1da5299fc22a964b06b8c9125d847' type='video/mp4' onerror="xhr()">
            <source src='https://ct.animeheaven.me/video.mp4?47edc338ae59c34cc3ea427c7f6871df&62e1da5299fc22a964b06b8c9125d847&error' type='video/mp4' onerror="xhr()">
            <source src='https://ck.animeheaven.me/video.mp4?47edc338ae59c34cc3ea427c7f6871df&62e1da5299fc22a964b06b8c9125d847&error2' type='video/mp4' onerror="xhr2()">
            <source src='https://ax.animeheaven.me/video.mp4?47edc338ae59c34cc3ea427c7f6871df&62e1da5299fc22a964b06b8c9125d847' type='video/mp4'>
            </video>
        """.trimIndent()

        val urls = AnimeHeavenParser.videoUrls(html)

        assertEquals(3, urls.size)
        assertTrue("primary mirror must stay first", urls[0].startsWith("https://ax.animeheaven.me/"))
        assertTrue(urls[1].startsWith("https://ct.animeheaven.me/"))
        assertTrue(urls[2].startsWith("https://ck.animeheaven.me/"))
        // The query carries two tokens separated by a bare `&`; entity decoding must not eat it.
        assertTrue(urls[0].endsWith("?47edc338ae59c34cc3ea427c7f6871df&62e1da5299fc22a964b06b8c9125d847"))
    }

    @Test
    fun `the commented-out watch-on-our-site clip is never offered as a mirror`() {
        // Verbatim shape of a live gate page (2026-10-12): the decoy leads, inside a comment, and
        // names a 3.7s "Watch episode on AnimeHeaven.Me" clip rather than the episode.
        val html = """
            <!-- <source src='https://ax.animeheaven.me/video.mp4?2c523c8cd9cc129bd13fc7407fa81220&eed805517af9a226eed805517af9a226' type='video/mp4' onerror="xhr()">  --><h1 style='margin: 0;font-weight: normal;'><div class='linetitle3 c'>Episode 10</div></h1>
            <video id='vid' onloadeddata='unm2()' class='videodiv' controls autoplay playsinline crossOrigin='anonymous' onerror="xhr4()">
            <source src='https://ax.animeheaven.me/video.mp4?b9b3edf494570b48df48fb1e0ec5e5d4&62e1da5299fc22a95a30e4e3a7b502e9' type='video/mp4' onerror="xhr()" onstalled="xhr3()">
            <source src='https://ct.animeheaven.me/video.mp4?b9b3edf494570b48df48fb1e0ec5e5d4&62e1da5299fc22a95a30e4e3a7b502e9&error' type='video/mp4' onerror="xhr()">
            </video>
        """.trimIndent()

        listOf(
            AnimeHeavenParser.videoUrls(html),
            AnimeHeavenParser.videoUrls(html, "b9b3edf494570b48df48fb1e0ec5e5d4"),
        ).forEach { urls ->
            assertEquals(2, urls.size)
            assertTrue(urls.none { it.contains("2c523c8cd9cc129bd13fc7407fa81220") })
            assertTrue("the episode must be the active mirror", urls[0].startsWith("https://ax.animeheaven.me/video.mp4?b9b3"))
        }
    }

    @Test
    fun `a source not named by the episode's gate key is dropped`() {
        val html = """
            <source src='https://ax.animeheaven.me/video.mp4?2c523c8cd9cc129bd13fc7407fa81220&eed805517af9a226eed805517af9a226' type='video/mp4'>
            <source src='https://ax.animeheaven.me/video.mp4?b9b3edf494570b48df48fb1e0ec5e5d4&62e1da5299fc22a95a30e4e3a7b502e9' type='video/mp4'>
        """.trimIndent()

        assertEquals(
            listOf("https://ax.animeheaven.me/video.mp4?b9b3edf494570b48df48fb1e0ec5e5d4&62e1da5299fc22a95a30e4e3a7b502e9"),
            AnimeHeavenParser.videoUrls(html, "b9b3edf494570b48df48fb1e0ec5e5d4"),
        )
    }

    @Test
    fun `a page with no episodes parses as empty rather than throwing`() {
        assertEquals(emptyList<AnimeHeavenParser.Episode>(), AnimeHeavenParser.parseEpisodes("<div>Not Found</div>"))
        assertEquals(emptyList<AnimeHeavenParser.SearchResult>(), AnimeHeavenParser.parseSearch("<div>Not Found</div>"))
        assertEquals(emptyList<String>(), AnimeHeavenParser.videoUrls("<div>Not Found</div>"))
    }

    private companion object {
        /** Three consecutive rows off a live `anime.php`, newest first, whitespace intact. */
        val EPISODE_ROWS = """
            <a class='c' onmouseover='gateh("47edc338ae59c34cc3ea427c7f6871df")' onclick='gatea("47edc338ae59c34cc3ea427c7f6871df")'  id ="47edc338ae59c34cc3ea427c7f6871df" href= 'gate.php'   				><div class='trackep0 watch bc2'><div class='trackep watchb bc'><div class='watch1 bc c'>Episode</div><div  class= ' watch2 bc ' >28</div><div class='watch1 bc c'>240 d ago</div></div></div></a>
            <a class='c' onmouseover='gateh("0437d20b317e2834760f61c83f8c973a")' onclick='gatea("0437d20b317e2834760f61c83f8c973a")'  id ="0437d20b317e2834760f61c83f8c973a" href= 'gate.php'   				><div class='trackep0 watch bc2'><div class='trackep watchb bc'><div class='watch1 bc c'>Episode</div><div  class= ' watch2 bc ' >27</div><div class='watch1 bc c'>247 d ago</div></div></div></a>
            <a class='c' onmouseover='gateh("aaef295ca750e9bf2d7077cbc27df63d")' onclick='gatea("aaef295ca750e9bf2d7077cbc27df63d")'  id ="aaef295ca750e9bf2d7077cbc27df63d" href= 'gate.php'   				><div class='trackep0 watch bc2'><div class='trackep watchb bc'><div class='watch1 bc c'>Episode</div><div  class= ' watch2 bc ' >26</div><div class='watch1 bc c'>254 d ago</div></div></div></a>
        """.trimIndent()
    }
}
