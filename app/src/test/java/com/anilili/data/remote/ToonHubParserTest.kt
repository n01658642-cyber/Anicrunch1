package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToonHubParserTest {
    @Test
    fun `search parser reads the REST result and decodes its title`() {
        val body = """
            [
              {"id":21397,"title":"Dandadan Season 2 Multi Audio [Hindi-Tamil] 480p, 720p &#038; 1080p",
               "url":"https://toonhub4u.co/dandadan-season-2/","type":"post","subtype":"post"},
              {"id":19621,"title":"Dandadan Season 1 Episodes Multi Audio",
               "url":"https://toonhub4u.co/dandadan-season-1/","type":"post","subtype":"post"}
            ]
        """.trimIndent()

        val results = checkNotNull(ToonHubParser.parseSearchJson(body))

        assertEquals(
            listOf(
                "https://toonhub4u.co/dandadan-season-2/",
                "https://toonhub4u.co/dandadan-season-1/",
            ),
            results.map { it.url },
        )
        assertEquals(
            "Dandadan Season 2 Multi Audio [Hindi-Tamil] 480p, 720p & 1080p",
            results.first().title,
        )
    }

    /** Pages match plenty of queries and carry no episodes, so they must not take a probe slot. */
    @Test
    fun `search parser drops everything that is not a post`() {
        val body = """
            [
              {"title":"DMCA","url":"https://toonhub4u.co/dmca/","type":"post","subtype":"page"},
              {"title":"Naruto Season 9","url":"https://toonhub4u.co/naruto-season-9/","subtype":"post"}
            ]
        """.trimIndent()

        assertEquals(
            listOf("https://toonhub4u.co/naruto-season-9/"),
            checkNotNull(ToonHubParser.parseSearchJson(body)).map { it.url },
        )
    }

    /**
     * A miss must come from an empty array, never from a body that failed to parse: the provider
     * caches "no entry" for six hours, so an HTML error page read as "no results" would take
     * ToonHub out of the title until the process restarts.
     */
    @Test
    fun `search parser separates an empty result from a body it could not read`() {
        assertEquals(emptyList<ToonHubParser.SearchResult>(), ToonHubParser.parseSearchJson("[]"))
        assertNull(ToonHubParser.parseSearchJson("<html>502 Bad Gateway</html>"))
        assertNull(ToonHubParser.parseSearchJson(""))
        assertNull(ToonHubParser.parseSearchJson("""{"code":"rest_forbidden"}"""))
    }

    @Test
    fun `episode blocks become quality-specific embed links`() {
        val html = """
            <p><strong>Episode 01: The Journey's End</strong></p>
            <p><strong><span>WatchOnline</span> – <a href="https://toonstream.one/episode/frieren-1x1/">Stream</a></strong></p>
            <p><strong>480p – <a href="https://gdmirrorbot.nl/file/first480">Mirrors</a></strong></p>
            <p><strong>1080p – <a href="https://gdmirrorbot.nl/file/first1080">Mirrors</a></strong></p>
            <hr>
            <p><strong>Episode 02: Magic</strong></p>
            <p><strong><span>WatchOnline</span> – <a href="https://toonstream.one/episode/frieren-1x1/">Stream</a></strong></p>
            <p><strong>720p – <a href="/embed/second720">Mirrors</a></strong></p>
            <hr>
        """.trimIndent()

        val episodes = ToonHubParser.parseEpisodes(html, "https://toonhub4u.co")

        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals(
            listOf(
                "https://toonstream.one/episode/frieren-1x1/",
                "https://gdmirrorbot.nl/embed/first480",
                "https://gdmirrorbot.nl/embed/first1080",
            ),
            episodes[0].links.map { it.url },
        )
        assertEquals(listOf("Hindi Stream", "480p", "1080p"), episodes[0].links.map { it.label })
        assertEquals(listOf(null, 480, 1080), episodes[0].links.map { it.height })
        assertEquals(
            listOf(
                "https://toonstream.one/episode/frieren-1x2/",
                "https://toonhub4u.co/embed/second720",
            ),
            episodes[1].links.map { it.url },
        )
    }

    @Test
    fun `post title normalization preserves the series identity`() {
        assertEquals(
            "Frieren: Beyond Journey’s End",
            ToonHubParser.seriesTitle(
                "Frieren: Beyond Journey’s End Season 1 Episodes Hindi Multi Audio Download 1080p",
            ),
        )
        assertEquals(2, ToonHubParser.seasonNumber("Frieren Season 2 Hindi Multi Audio"))
        assertTrue(ToonHubParser.parseLinks("<p>No mirrors yet</p>", "https://toonhub4u.co").isEmpty())
    }

    @Test
    fun `a paragraph without a quality labels every one of its mirrors from its own text`() {
        // The label is derived from the paragraph's stripped text, which used to be recomputed for
        // each anchor. It is now computed once and lazily, so it must still reach a second mirror
        // in the same paragraph and still lose to an explicit quality.
        val unlabelled = ToonHubParser.parseLinks(
            """<p>Hindi Dubbed – <a href="/embed/one">One</a> <a href="/embed/two">Two</a></p>""",
            "https://toonhub4u.co",
        )
        assertEquals(listOf("Hindi Dubbed", "Hindi Dubbed"), unlabelled.map { it.label })
        assertEquals(
            listOf("https://toonhub4u.co/embed/one", "https://toonhub4u.co/embed/two"),
            unlabelled.map { it.url },
        )

        val qualified = ToonHubParser.parseLinks(
            """<p>Frieren 1080p – <a href="/file/one">One</a> <a href="/file/two">Two</a></p>""",
            "https://toonhub4u.co",
        )
        assertEquals(listOf("1080p", "1080p"), qualified.map { it.label })
        assertEquals(listOf(1080, 1080), qualified.map { it.height })
        // /file/ is rewritten to /embed/ so the guarded browser gets a player, not a landing page.
        assertEquals(
            listOf("https://toonhub4u.co/embed/one", "https://toonhub4u.co/embed/two"),
            qualified.map { it.url },
        )
    }

    @Test
    fun `toonstream episode chrome becomes its inner player mirrors`() {
        val html = """
            <div class="video"><iframe src="/embed/ruby-player"></iframe></div>
            <div class="video"><iframe data-src="/embed/moly-player"></iframe></div>
            <aside class="video-options">
              <span class="server">Ruby</span>
              <span class="server">Moly</span>
            </aside>
            <iframe src="https://www.youtube.com/embed/trailer"></iframe>
        """.trimIndent()

        val players = ToonHubParser.parsePlayerEmbeds(
            html,
            "https://toon-stream.site/episode/frieren-1x1/",
        )

        assertEquals(
            listOf(
                "https://toon-stream.site/embed/moly-player",
                "https://toon-stream.site/embed/ruby-player",
            ),
            players.map { it.url },
        )
        assertEquals(listOf("Moly", "Ruby"), players.map { it.label })
    }

    @Test
    fun `toonstream embed wrapper exposes the real player iframe`() {
        val html = """
            <div class="fake-player-overlay" onclick="redirectToAd()">
              <div class="play-button"></div>
            </div>
            <div class="Video">
              <iframe src="https://abyssplayer.com/1vGX4aCXu" allowfullscreen></iframe>
            </div>
        """.trimIndent()

        assertEquals(
            "https://abyssplayer.com/1vGX4aCXu",
            ToonHubParser.parseWrappedPlayer(
                html,
                "https://toon-stream.site/embed/8b1788c217f32ae0",
            ),
        )
    }

    @Test
    fun `wrapped player parser accepts relative https players but rejects unsafe sources`() {
        assertEquals(
            "https://toon-stream.site/player/episode-1",
            ToonHubParser.parseWrappedPlayer(
                """<iframe data-src="/player/episode-1"></iframe>""",
                "https://toon-stream.site/embed/wrapper",
            ),
        )
        assertEquals(
            null,
            ToonHubParser.parseWrappedPlayer(
                """<iframe src="javascript:window.open('ad')"></iframe>""",
                "https://toon-stream.site/embed/wrapper",
            ),
        )
    }

    @Test
    fun `players iframed straight onto the episode page are mirrors too`() {
        // The live shape as of 2026-09-13: ToonStream dropped its per-server wrapper pages and
        // iframes the players themselves. A same-host filter saw none of these and the provider
        // reported "no ToonStream player mirrors" for every episode.
        val html = """
            <div class="video"><iframe src="https://rubystm.com/e/mvybg38ttztw.html"></iframe></div>
            <div class="video"><iframe data-src="https://filesforever.link/embed/hdoyuqt"></iframe></div>
            <div class="video"><iframe data-src="https://cloudy.upns.one/#krga1o"></iframe></div>
            <div class="video"><iframe data-src="https://abyssplayer.com/LR0L1Adhn"></iframe></div>
            <aside class="video-options">
              <span class="server">Ruby</span>
              <span class="server">Watch/DL</span>
              <span class="server">cloudy</span>
              <span class="server">Short</span>
            </aside>
            <iframe width="560" src="https://www.youtube.com/embed/HkIKAnwLZCw"></iframe>
        """.trimIndent()

        val players = ToonHubParser.parsePlayerEmbeds(
            html,
            "https://toonstream.vip/episode/solo-leveling-1x1/",
        )

        // playerRank order: Short (AbyssPlayer), Ruby, cloudy, then the download host last.
        assertEquals(listOf("Short", "Ruby", "cloudy", "Watch/DL"), players.map { it.label })
        assertEquals(
            listOf(
                "https://abyssplayer.com/LR0L1Adhn",
                "https://rubystm.com/e/mvybg38ttztw.html",
                "https://cloudy.upns.one/#krga1o",
                "https://filesforever.link/embed/hdoyuqt",
            ),
            players.map { it.url },
        )
        // Only the same-host chrome is worth another request; a real player is already the player.
        assertTrue(
            players.none {
                ToonHubParser.isWrappedPlayer(it.url, "https://toonstream.vip/episode/solo-leveling-1x1/")
            },
        )
        assertTrue(
            ToonHubParser.isWrappedPlayer(
                "https://toonstream.vip/embed/8b1788c2",
                "https://toonstream.vip/episode/solo-leveling-1x1/",
            ),
        )
    }

    @Test
    fun `a watch link wrapped in ToonHub's own redirector still reads as one`() {
        // https://toonhub4u.co/redirect/main1.php?url=<base64 of the toonstream episode URL>
        val html = """
            <p><strong>Episode 03: Still a Long Way to Go</strong></p>
            <p><strong><span>WatchOnline</span> – <a href="https://toonhub4u.co/redirect/main1.php?url=aHR0cHM6Ly90b29uc3RyZWFtLm5ldC9lcGlzb2RlL3NvbG8tbGV2ZWxpbmctMXgxLw==">Stream</a></strong></p>
            <p><strong>1080p – <a href="https://filesforever.link/file/qr8e0a0">Mirrors</a></strong></p>
        """.trimIndent()

        val episode = ToonHubParser.parseEpisodes(html, "https://toonhub4u.co").single()

        assertEquals(3, episode.number)
        // Decoded, then repaired to the episode its own heading names.
        assertEquals("https://toonstream.net/episode/solo-leveling-1x3/", episode.links.first().url)
        assertEquals("Hindi Stream", episode.links.first().label)
        assertTrue(ToonHubParser.isWatchLink(episode.links.first().url))
    }

    @Test
    fun `the redirector is left alone when it carries nothing readable`() {
        assertEquals(
            "https://toonstream.net/episode/x-1x1/",
            ToonHubParser.followRedirect("https://toonstream.net/episode/x-1x1/"),
        )
        assertEquals(
            "https://toonhub4u.co/redirect/main1.php?url=%%%",
            ToonHubParser.followRedirect("https://toonhub4u.co/redirect/main1.php?url=%%%"),
        )
        // Anything that does not decode to an absolute URL is not a destination.
        assertEquals(
            "https://toonhub4u.co/redirect/main1.php?url=aGVsbG8",
            ToonHubParser.followRedirect("https://toonhub4u.co/redirect/main1.php?url=aGVsbG8"),
        )
    }
}
