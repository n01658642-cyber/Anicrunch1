package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RareAnimesParserTest {
    @Test
    fun parsesSearchResults() {
        // Both `<title>` shapes the live feed mixes: CDATA on some items, bare entity-escaped
        // text on others.
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <title>Search Results for &#8220;naruto&#8221;</title>
              <link>https://www.rareanimes.mov/</link>
              <item>
                <title><![CDATA[Naruto Shippuden Season 02 &#8211; Episodes Hindi Dubbed Download HD]]></title>
                <link>https://www.rareanimes.mov/hindi/naruto-shippuden-season-02-episodes-hindi-dubbed-download-hd/</link>
              </item>
              <item>
                <title>Naruto Shippuden Season 01 &#8211; Episodes Hindi Dubbed Download HD</title>
                <link>https://www.rareanimes.mov/hindi/naruto-shippuden-season-01-episodes-hindi-dubbed-download-hd/</link>
              </item>
            </channel></rss>
        """.trimIndent()

        val results = checkNotNull(RareAnimesParser.parseSearchFeed(feed))

        assertEquals(2, results.size)
        assertEquals(
            "https://www.rareanimes.mov/hindi/naruto-shippuden-season-02-episodes-hindi-dubbed-download-hd/",
            results[0].url,
        )
        assertEquals("Naruto Shippuden Season 02 – Episodes Hindi Dubbed Download HD", results[0].title)
        assertEquals("Naruto Shippuden Season 01 – Episodes Hindi Dubbed Download HD", results[1].title)
    }

    /**
     * The whole saving depends on this split. A page past the last one answers with the 507 KB
     * rendered search page, and reading that as "no results" would both hide the provider for six
     * hours and invite the walker to keep paging into more of them.
     */
    @Test
    fun `an empty feed is a miss but a non-feed body is the end of the pages`() {
        val empty = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Search Results</title>
            <link>https://www.rareanimes.mov/</link></channel></rss>
        """.trimIndent()

        assertEquals(emptyList<RareAnimesParser.SearchResult>(), RareAnimesParser.parseSearchFeed(empty))
        assertNull(RareAnimesParser.parseSearchFeed("<!doctype html><html><body>search page</body></html>"))
        assertNull(RareAnimesParser.parseSearchFeed(""))
    }

    @Test
    fun parsesParagraphStyleEpisodes() {
        val html = """
            <hr>
            <p>Episode 01 – The New Target</p>
            <p><span style="color: #99cc00;">Hindi Uncut </span>– [<a href="https://codedew.com/zipper/?url=HINDI_MQ" target="_blank">WatchMultiQuality</a>] [<a href="https://codedew.com/zipper/?url=HINDI_SB" target="_blank">StreamBeta</a>] [<a href="https://codedew.com/zipper/?url=HINDI_DL" target="_blank">DLBeta</a>]</p>
            <p><span style="color: #ff00ff;">Tamil </span>– [<a href="https://codedew.com/zipper/?url=TAMIL_SB" target="_blank">StreamBeta</a>]</p>
            <p><span style="color: #00ccff;">Telugu </span>– [<a href="https://codedew.com/zipper/?url=TELUGU_SB" target="_blank">StreamBeta</a>]</p>
            <hr>
            <p>Episode 02 – Formation! New Team Kakashi!</p>
            <p><span style="color: #99cc00;">Hindi Uncut </span>– [<a href="https://codedew.com/zipper/?url=EP2_MQ" target="_blank">WatchMultiQuality</a>]</p>
            <hr>
        """.trimIndent()

        val episodes = RareAnimesParser.parseEpisodes(html)

        assertEquals(listOf(1, 2), episodes.map { it.number })
        val ep1 = episodes[0]
        assertEquals(setOf("hindi", "tamil", "telugu"), ep1.languages.keys)
        assertEquals(3, ep1.languages.getValue("hindi").size)
        assertEquals(
            "https://codedew.com/zipper/?url=HINDI_MQ",
            RareAnimesParser.preferred(ep1.languages.getValue("hindi"))!!.url,
        )
        assertEquals(
            "https://codedew.com/zipper/?url=TAMIL_SB",
            RareAnimesParser.preferred(ep1.languages.getValue("tamil"))!!.url,
        )
        assertEquals(setOf("hindi"), episodes[1].languages.keys)
    }

    @Test
    fun parsesSpanStyleEpisodes() {
        // Older markup generation: ra-ep-title / ra-l-* spans instead of plain paragraphs.
        val html = """
            <span class="ra-ep-title"><strong>Episode 01 – The Beginning</strong></span>
            <span class="ra-l-hindi">Hindi</span>
            [<a href="https://codedew.com/zipper/?url=SPAN_MQ">WatchMultQuality</a>]
            [<a href="https://codedew.com/zipper/?url=SPAN_SB">StreamBeta</a>]
        """.trimIndent()

        val episodes = RareAnimesParser.parseEpisodes(html)

        assertEquals(1, episodes.size)
        assertEquals(
            "https://codedew.com/zipper/?url=SPAN_MQ",
            RareAnimesParser.preferred(episodes[0].languages.getValue("hindi"))!!.url,
        )
    }

    @Test
    fun skipsEpisodesWithoutLinks() {
        val html = """
            <p>Episode 01 – Real</p>
            <p><span>Hindi </span>– [<a href="https://codedew.com/zipper/?url=OK">WatchMultiQuality</a>]</p>
            <p>Episode 02 – Empty</p>
            <p>No links here yet.</p>
        """.trimIndent()

        assertEquals(listOf(1), RareAnimesParser.parseEpisodes(html).map { it.number })
    }

    @Test
    fun `single-language posts label rows by dub source, not language`() {
        // Jujutsu Kaisen Season 1's markup: the post is Hindi-only, so its rows are headed by the
        // dub studio. Requiring a Hindi/Tamil/Telugu word here dropped 23 of its 24 episodes.
        val html = """
            <p>Episode 01 – Ryomen Sukuna</p>
            <p><span style="color: #ff0000;">New Sony Dub – </span>[<a href="https://codedew.com/zipper/?url=EP1_MQ" target="_blank">WatchMultQuality</a>] [<a href="https://codedew.com/zipper/?url=EP1_MF">MediaFire</a>]</p>
            <p><span style="color: #99cc00;">Crunchyroll</span> – [<a href="https://codedew.com/zipper/?url=EP1_CR">WatchMultQuality</a>]</p>
            <hr>
            <p>Episode 02 – For Myself</p>
            <p><span>New Sony Dub – </span>[<a href="https://codedew.com/zipper/?url=EP2_MQ">WatchMultQuality</a>]</p>
        """.trimIndent()

        val episodes = RareAnimesParser.parseEpisodes(html)

        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals(setOf("hindi"), episodes[0].languages.keys)
        // Every server in the block is collected, not just the first row's.
        assertEquals(3, episodes[0].languages.getValue("hindi").size)
        assertEquals(
            "https://codedew.com/zipper/?url=EP1_MQ",
            RareAnimesParser.preferred(episodes[0].languages.getValue("hindi"))!!.url,
        )
    }

    @Test
    fun `preferred accepts both MultiQuality spellings the site uses`() {
        val multi = RareAnimesParser.ServerLink("u1", "WatchMultiQuality")
        val mult = RareAnimesParser.ServerLink("u2", "WatchMultQuality")
        val beta = RareAnimesParser.ServerLink("u3", "StreamBeta")
        assertEquals("u1", RareAnimesParser.preferred(listOf(beta, multi))!!.url)
        assertEquals("u2", RareAnimesParser.preferred(listOf(beta, mult))!!.url)
        // The decodable server wins even when another "Watch…" row comes first.
        assertEquals(
            "u2",
            RareAnimesParser.preferred(
                listOf(RareAnimesParser.ServerLink("u4", "Watch Online"), mult),
            )!!.url,
        )
        assertNull(RareAnimesParser.preferred(emptyList()))
    }

    @Test
    fun `series title drops the site's stock suffix`() {
        assertEquals(
            "Demon Slayer",
            RareAnimesParser.seriesTitle("Demon Slayer Season 1 – Episodes Hindi Dubbed Download HD Jio Cinema"),
        )
        assertEquals(
            "Naruto Shippuden",
            RareAnimesParser.seriesTitle("Naruto Shippuden Season 02 – Episodes Hindi Dubbed Download HD"),
        )
        // Nothing to strip.
        assertEquals("Death Note", RareAnimesParser.seriesTitle("Death Note"))
    }

    @Test
    fun `season number is read from the post title`() {
        assertEquals(4, RareAnimesParser.seasonNumber("Demon Slayer Season 4 (Hashira Training Arc) Hindi"))
        assertEquals(2, RareAnimesParser.seasonNumber("Naruto Shippuden Season 02 – Episodes"))
        assertEquals(2, RareAnimesParser.seasonNumber("That Time I Got Reincarnated as a Slime 2nd Season"))
        assertEquals(3, RareAnimesParser.seasonNumber("Kaguya-sama: Love Is War III"))
        assertEquals(2, RareAnimesParser.seasonNumber("The Misfit of Demon King Academy 2"))
        assertNull(RareAnimesParser.seasonNumber("Demon Slayer Mugen Train Movie Hindi Dubbed"))
    }

    @Test
    fun `a title that ends in a number is not a season of itself`() {
        // Read as season 8, this asked for a season the site has never posted, so both real posts
        // ranked as the wrong season and the tie went to the newest — season 2's episodes under
        // season 1's numbers.
        assertNull(RareAnimesParser.seasonNumber("Kaiju No. 8"))
        assertNull(RareAnimesParser.seasonNumber("Mob Psycho 100"))
        assertEquals(2, RareAnimesParser.seasonNumber("Gintama 2"))
    }

    @Test
    fun `a sequel name reduces to the series the site files it under`() {
        assertEquals(
            "Solo Leveling",
            RareAnimesParser.baseSeriesTitle("Solo Leveling Season 2 -Arise from the Shadow-"),
        )
        assertEquals("Attack on Titan", RareAnimesParser.baseSeriesTitle("Attack on Titan Final Season"))
        assertEquals("Jujutsu Kaisen", RareAnimesParser.baseSeriesTitle("Jujutsu Kaisen 2nd Season"))
        // No season marker, nothing to strip — and so no extra search request either.
        assertEquals("Death Note", RareAnimesParser.baseSeriesTitle("Death Note"))
    }

    @Test
    fun `equally scored season posts settle on the earliest, not the newest`() {
        // "Ranma ½" normalises to "ranma" and the post to "ranma12", so the exact-name check that
        // would have asked for season one does not fire and every post scores the same.
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("Ranma ½"),
            results = listOf(
                RareAnimesParser.SearchResult("season-2", "Ranma1/2 Season 2 Hindi Dubbed Episodes Download HD"),
                RareAnimesParser.SearchResult("season-1", "Ranma1/2 Season 1 Hindi Dubbed Episodes Download HD"),
            ),
            episodic = true,
        )

        assertEquals("season-1", ranked.first().url)
    }

    @Test
    fun `a post that merely shares a word is not this title`() {
        // WordPress answers a name it does not carry with whatever shares a word; those score
        // around 0.3, and accepting one is how a request for one anime came back as another.
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("Sakamoto Days"),
            results = listOf(
                RareAnimesParser.SearchResult(
                    "wedding",
                    "365 Days to the Wedding Season 1 Hindi Dubbed Episodes Download HD",
                ),
            ),
            episodic = true,
            minScore = 0.5,
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `base-series request ranks season one ahead of newest wordpress post`() {
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("That Time I Got Reincarnated as a Slime"),
            results = slimeSearchResults(),
            episodic = true,
        )

        assertTrue(ranked.first().title.contains("Season 1"))
    }

    @Test
    fun `explicit sequel request ranks its season first`() {
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("That Time I Got Reincarnated as a Slime 2nd Season"),
            results = slimeSearchResults(),
            episodic = true,
        )

        assertTrue(ranked.first().title.contains("Season 2"))
    }

    @Test
    fun `a final season asks for the last one the site has posted`() {
        // AniList numbers this one in words; the site numbers it 4. With nothing mapping the two,
        // every Attack on Titan post tied and the earliest won.
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("Attack on Titan Final Season", "Shingeki no Kyojin: The Final Season"),
            results = listOf(
                RareAnimesParser.SearchResult("s1", "Attack on Titan Season 1 Hindi Dubbed Episodes Download HD"),
                RareAnimesParser.SearchResult("oad", "Attack on Titan OAD Hindi Dubbed Episodes Download HD"),
                RareAnimesParser.SearchResult("s4", "Attack on Titan Season 4 Hindi Dubbed Episodes Download HD"),
                RareAnimesParser.SearchResult("s3", "Attack on Titan Season 3 Hindi Dubbed Episodes Download HD"),
            ),
            episodic = true,
        )

        assertEquals("s4", ranked.first().url)
    }

    @Test
    fun `a numbered season still wins over the final-season reading`() {
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("That Time I Got Reincarnated as a Slime 2nd Season"),
            results = slimeSearchResults(),
            episodic = true,
        )

        assertTrue(ranked.first().title.contains("Season 2"))
    }

    @Test
    fun `named sequel arc is not forced onto season one`() {
        val ranked = RareAnimesParser.rankSearchResults(
            titles = listOf("Demon Slayer: Kimetsu no Yaiba - Hashira Training Arc"),
            results = listOf(
                RareAnimesParser.SearchResult("season-1", "Demon Slayer Season 1 Hindi Dubbed Episodes Download HD"),
                RareAnimesParser.SearchResult(
                    "season-4",
                    "Demon Slayer Season 4 (Hashira Training Arc) Hindi Dubbed Episodes Download HD",
                ),
            ),
            episodic = true,
        )

        assertEquals("season-4", ranked.first().url)
    }

    private fun slimeSearchResults() = listOf(
        RareAnimesParser.SearchResult(
            "season-4",
            "That Time I Got Reincarnated as a Slime Season 4 Hindi Dubbed Episodes Download HD",
        ),
        RareAnimesParser.SearchResult(
            "movie",
            "That Time I Got Reincarnated as a Slime the Movie: Scarlet Bond Movie Hindi Dubbed Download HD",
        ),
        RareAnimesParser.SearchResult(
            "season-3",
            "That Time I Got Reincarnated as a Slime Season 3 Hindi Dubbed Episodes Download HD",
        ),
        RareAnimesParser.SearchResult(
            "season-2",
            "That Time I Got Reincarnated as a Slime Season 2 Hindi Dubbed Episodes Download HD",
        ),
        RareAnimesParser.SearchResult(
            "season-1",
            "That Time I Got Reincarnated as a Slime Season 1 Hindi Dubbed Episodes Download HD",
        ),
    )

    @Test
    fun `duplicate episode headings collapse to one entry`() {
        val html = """
            <p>Episode 01 – Real</p>
            <p><span>Hindi </span>– [<a href="https://codedew.com/zipper/?url=A">WatchMultiQuality</a>]</p>
            <p>Episode 01 – Repeated in a footer index</p>
            <p><span>Hindi </span>– [<a href="https://codedew.com/zipper/?url=B">WatchMultiQuality</a>]</p>
        """.trimIndent()
        assertEquals(listOf(1), RareAnimesParser.parseEpisodes(html).map { it.number })
    }

    @Test
    fun `movie posts have no episode heading and parse as one episode`() {
        // Grave of the Fireflies, trimmed. Nothing here says "Episode", which is why parseEpisodes
        // finds nothing and every film on the site was reported unavailable.
        val html = movieHtml(
            body = """
                <p>Download Grave of the Fireflies Hindi Full Movie.</p>
                <hr>
                <h4 style="text-align: center;"><span style="color: #00ff00;">Hindi (FanDub)</span></h4>
                <h4 style="text-align: center;"><span style="color: #ff00ff;">Hindi – Download</span></h4>
                <h3 style="text-align: center;"><a href="https://codedew.com/zipper/?url=MOVIE_MQ" target="_blank" rel="noopener noreferrer">WatchMultiQuality</a></h3>
                <h3 style="text-align: center;"><a href="https://codedew.com/zipper/?url=MOVIE_MEGA" target="_blank" rel="noopener noreferrer">Mega</a></h3>
            """,
        )

        assertTrue(RareAnimesParser.parseEpisodes(html).isEmpty())
        val movie = RareAnimesParser.parseMovie(html)!!
        assertEquals(1, movie.number)
        assertEquals(setOf("hindi"), movie.languages.keys)
        assertEquals(
            "https://codedew.com/zipper/?url=MOVIE_MQ",
            RareAnimesParser.preferred(movie.languages.getValue("hindi"))!!.url,
        )
    }

    @Test
    fun `movie parse ignores prose links a rewriter turned into codedew URLs`() {
        // The 2019-era posts run their info table through a link rewriter, so the words "Japanese"
        // and "Hepburn" are codedew URLs that go nowhere. They sit in the post body, ahead of the
        // real servers, and preferred()'s last-ditch "first link" would have handed one to the
        // player on any post whose server row was missing.
        val html = movieHtml(
            body = """
                <p>This movie is now available in Hindi (Unofficial Dubbed).</p>
                <h5>Movie Info</h5>
                <p>Full Name: Grave of the Fireflies
                  (<a title="Japanese language" href="https://codedew.com/zipper/?url=JUNK_JA">Japanese</a>:
                  <a title="Hepburn romanization" href="https://codedew.com/zipper/?url=JUNK_HEP">Hepburn</a>)</p>
                <h4>Hindi – Download</h4>
                <h3><a href="https://codedew.com/zipper/?url=REAL_MQ">WatchMultiQuality</a></h3>
            """,
        )

        val links = RareAnimesParser.parseMovie(html)!!.languages.getValue("hindi")
        assertEquals(listOf("https://codedew.com/zipper/?url=REAL_MQ"), links.map { it.url })
    }

    @Test
    fun `movie parse keeps each language section`() {
        // Demon Slayer Infinity Castle's layout: one server block per language.
        val html = movieHtml(
            body = """
                <p>Available in Hindi, Tamil and Telugu.</p>
                <h4>Hindi – Download</h4>
                <h3><a href="https://codedew.com/zipper/?url=HI_MQ">WatchMultiQuality</a></h3>
                <h3><a href="https://codedew.com/zipper/?url=HI_SB">StreamBeta</a></h3>
                <h4>Tamil – Download</h4>
                <h3><a href="https://codedew.com/zipper/?url=TA_MQ">WatchMultiQuality</a></h3>
                <h4>Telugu – Download</h4>
                <h3><a href="https://codedew.com/zipper/?url=TE_MQ">WatchMultiQuality</a></h3>
            """,
        )

        val movie = RareAnimesParser.parseMovie(html)!!
        assertEquals(setOf("hindi", "tamil", "telugu"), movie.languages.keys)
        assertEquals(
            "https://codedew.com/zipper/?url=TA_MQ",
            RareAnimesParser.preferred(movie.languages.getValue("tamil"))!!.url,
        )
        // The "Hindi" in the blurb opens a section of its own; its links belong to the real one.
        assertEquals(
            listOf("https://codedew.com/zipper/?url=HI_MQ"),
            movie.languages.getValue("hindi").map { it.url },
        )
    }

    @Test
    fun `movie parse reads the post body, not the rest of the page`() {
        // Related-posts strips carry other titles' servers, and there is no episode heading here
        // to bound the search with instead.
        val html = movieHtml(
            body = """
                <h4>Hindi – Download</h4>
                <h3><a href="https://codedew.com/zipper/?url=MINE">WatchMultiQuality</a></h3>
            """,
            after = """
                <div class="herald-related">
                  <h4>Hindi</h4>
                  <a href="https://codedew.com/zipper/?url=SOMEONE_ELSE">WatchMultiQuality</a>
                </div>
            """,
        )

        val links = RareAnimesParser.parseMovie(html)!!.languages.getValue("hindi")
        assertEquals(listOf("https://codedew.com/zipper/?url=MINE"), links.map { it.url })
    }

    @Test
    fun `a post with no decodable server is not a movie`() {
        // Stub pages are the reason the zero-episode probe exists; reading posts whole must not
        // start accepting them.
        val html = movieHtml(body = "<p>Hindi dub coming soon.</p>")
        assertNull(RareAnimesParser.parseMovie(html))
    }

    /** The theme's single-post shell, which is what bounds [RareAnimesParser.parseMovie]. */
    private fun movieHtml(body: String, after: String = ""): String = """
        <header class="entry-header"><h1 class="entry-title h1">Movie Hindi Dubbed Download HD</h1></header>
        <div class="entry-content herald-entry-content">
        ${body.trimIndent()}
        </div>
        ${after.trimIndent()}
    """.trimIndent()

    @Test
    fun findsEmbedUrl() {
        val html = """
            <iframe src="https://argon.razorshell.space/embed/5GQAmPt6K445Vk7" referrerpolicy="origin" allowfullscreen></iframe>
        """.trimIndent()
        assertEquals(
            "https://argon.razorshell.space/embed/5GQAmPt6K445Vk7",
            RareAnimesParser.embedUrl(html),
        )
        assertNull(RareAnimesParser.embedUrl("<p>nothing</p>"))
    }
}
