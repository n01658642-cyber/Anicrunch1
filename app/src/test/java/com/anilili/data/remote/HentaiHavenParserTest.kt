package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hentai Haven moved from WordPress to Next.js and took its whole player wrapping with it. What is
 * left to get wrong is the reading of the page and the sitemap, so these cover that without
 * touching the network.
 */
class HentaiHavenParserTest {
    @Test
    fun readsTheOctopusManifestOutOfAnEpisodePage() {
        val html = """<script>self.__next_f.push([1,"{\"src\":\"""" +
            """https://octopusmanifest.org/a2ea2b3d-b7b0-4364-9ddf-325f5c1bb71f/playlist.m3u8\"}"])</script>"""
        assertEquals(
            "https://octopusmanifest.org/a2ea2b3d-b7b0-4364-9ddf-325f5c1bb71f/playlist.m3u8",
            HentaiHavenParser.manifestUrl(html),
        )
    }

    @Test
    fun readsTheOtherBackendsManifestToo() {
        // Roughly a quarter of the library is still served by the older host, and it shares no
        // path structure with the newer one — which is why the match is on `.m3u8` alone.
        val html = """<div data-player="{&quot;url&quot;:&quot;""" +
            """https://master-lengs.org/api/v3/hh/amanee-1/master.m3u8&quot;}"></div>"""
        assertEquals(
            "https://master-lengs.org/api/v3/hh/amanee-1/master.m3u8",
            HentaiHavenParser.manifestUrl(html),
        )
    }

    @Test
    fun anEpisodePageWithoutAManifestIsNotAStream() {
        // Episodes that have been pulled still render a page; answering with a URL-shaped guess
        // would put the viewer in a player that can only fail.
        val html = "<html><body><p>This episode has been removed.</p></body></html>"
        assertNull(HentaiHavenParser.manifestUrl(html))
    }

    @Test
    fun readsSeriesSlugsFromASitemap() {
        val xml = """
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>https://hentaihaven.xxx/watch/enjo-kouhai</loc></url>
              <url><loc>https://hentaihaven.xxx/watch/harem-camp/</loc></url>
            </urlset>
        """
        assertEquals(listOf("enjo-kouhai", "harem-camp"), HentaiHavenParser.sitemapSlugs(xml))
    }

    @Test
    fun sitemapSlugsAreDistinct() {
        // The sitemap repeats a series once per alternate-language link.
        val xml = """
            <url><loc>https://hentaihaven.xxx/watch/furachi</loc>
              <xhtml:link href="https://hentaihaven.xxx/watch/furachi"/>
            </url>
        """
        assertEquals(listOf("furachi"), HentaiHavenParser.sitemapSlugs(xml))
    }

    @Test
    fun collectsEpisodeNumbersFromSeriesPage() {
        val html = """
            <a href="https://hentaihaven.xxx/watch/some-slug/episode-1/">Episode 1</a>
            <a href="https://hentaihaven.xxx/watch/some-slug/episode-2/">Episode 2</a>
            <a href="https://hentaihaven.xxx/watch/some-slug/episode-3/">Episode 3</a>
        """
        assertEquals(setOf(1, 2, 3), HentaiHavenParser.episodeNumbers(html, "some-slug"))
    }

    @Test
    fun episodeNumbersIgnoreOtherSeriesLinkedInSidebar() {
        // A real series page carries "Popular" and "See More" widgets that link straight to other
        // series' episodes. Counting those gave a two-episode title a phantom third episode.
        val html = """
            <a href="https://hentaihaven.xxx/watch/furachi/episode-1/">Episode 1</a>
            <a href="https://hentaihaven.xxx/watch/furachi/episode-2/">Episode 2</a>
            <aside class="widget popular">
              <a href="https://hentaihaven.xxx/watch/onaji-zemi-no-someya-san/episode-3/">Episode 3</a>
              <a href="https://hentaihaven.xxx/watch/nuki-nuki-zupposism/episode-1/">Episode 1</a>
            </aside>
        """
        assertEquals(setOf(1, 2), HentaiHavenParser.episodeNumbers(html, "furachi"))
    }

    @Test
    fun slugMatchesTheAniListRomajiItCameFrom() {
        // How a series is bound to an AniList entry, since Hentai Haven carries no MAL or
        // AniList id. Punctuation is the only thing the two disagree on here.
        val score = titleSimilarity(
            "Onaji Zemi no Someya-san ga Sexy Joyuu Datta Hanashi",
            HentaiHavenProvider.slugAsTitle("onaji-zemi-no-someya-san-ga-sexy-joyuu-datta-hanashi"),
        )
        assertTrue("expected a confident match, got $score", score >= 0.85)
    }

    @Test
    fun unrelatedTitleDoesNotClearTheMatchBar() {
        val score = titleSimilarity(
            "Frieren: Beyond Journey's End",
            HentaiHavenProvider.slugAsTitle("onaji-zemi-no-someya-san-ga-sexy-joyuu-datta-hanashi"),
        )
        assertTrue("a wrong match is worse than none, got $score", score < 0.85)
    }
}
