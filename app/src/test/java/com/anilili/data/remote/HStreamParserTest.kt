package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hstream.moe binds a series by name and reaches a stream through one page read and one API call.
 * Everything that can go wrong without the network — the sitemap, the page, the API's answer and
 * the title match — is pinned here, with fragments taken from the live site on 2026-10-03.
 */
class HStreamParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sitemapSeparatesSeriesFromTheirEpisodes() {
        val xml = """
            <url><loc>https://hstream.moe</loc></url>
            <url><loc>https://hstream.moe/hentai/furachi</loc></url>
            <url><loc>https://hstream.moe/hentai/furachi-1</loc></url>
            <url><loc>https://hstream.moe/hentai/furachi-2</loc></url>
            <url><loc>https://hstream.moe/hentai/kokuhaku</loc></url>
            <url><loc>https://hstream.moe/hentai/kokuhaku-4</loc></url>
            <url><loc>https://hstream.moe/hentai/kokuhaku-1</loc></url>
            <url><loc>https://hstream.moe/search</loc></url>
        """
        assertEquals(
            mapOf("furachi" to listOf(1, 2), "kokuhaku" to listOf(1, 4)),
            HStreamParser.catalogue(xml),
        )
    }

    @Test
    fun aSeriesWhoseNameEndsInANumberKeepsItsOwnEpisodes() {
        // "Ends in -{n}" alone would file `tsuma-netori-3` as episode 3 of `tsuma-netori`. An entry
        // only counts as an episode when the series it would belong to is itself listed.
        val xml = """
            <url><loc>https://hstream.moe/hentai/tsuma-netori-3</loc></url>
            <url><loc>https://hstream.moe/hentai/tsuma-netori-3-1</loc></url>
            <url><loc>https://hstream.moe/hentai/tsuma-netori-3-2</loc></url>
        """
        assertEquals(mapOf("tsuma-netori-3" to listOf(1, 2)), HStreamParser.catalogue(xml))
    }

    @Test
    fun readsTheEpisodeIdAndTokenOffAnEpisodePage() {
        val html = """
            <input id="e_id" type="hidden" value="2023" />
            <div data-cfasync="false" data-csrf="z34kogZLsSefznhpnHU5uRhVaUfnk8i9Uq4RkpyW"></div>
        """
        assertEquals("2023", HStreamParser.episodeId(html))
        assertEquals("z34kogZLsSefznhpnHU5uRhVaUfnk8i9Uq4RkpyW", HStreamParser.csrfToken(html))
    }

    @Test
    fun fallsBackToTheMetaTagForTheToken() {
        val html = """<meta name="csrf-token" content="abc123">"""
        assertEquals("abc123", HStreamParser.csrfToken(html))
        assertNull(HStreamParser.episodeId(html))
    }

    @Test
    fun carriesTheSessionAcrossAsACookieHeader() {
        // Without these the API refuses the token with 419, so attributes must be dropped and every
        // name=value kept.
        val header = HStreamParser.cookieHeader(
            listOf(
                "XSRF-TOKEN=eyJpdiI6; expires=Sat, 03 Oct 2026 14:00:00 GMT; Max-Age=7200; path=/; samesite=lax",
                "hstream_session=eyJpdiI6Ik; expires=Sat, 03 Oct 2026 14:00:00 GMT; path=/; httponly",
            ),
        )
        assertEquals("XSRF-TOKEN=eyJpdiI6; hstream_session=eyJpdiI6Ik", header)
    }

    @Test
    fun readsThePlayerApiAnswer() {
        val player = HStreamParser.player(json, PLAYER_WITH_EXTRAS)!!
        assertEquals("2026/Furachi/E01", player.streamPath)
        assertEquals(2, player.mirrors.size)
        assertEquals(mapOf("de" to "German", "hi" to "Hindi"), player.extraSubtitles)
    }

    @Test
    fun anEmptyTranslationSetArrivesAsAnArray() {
        val player = HStreamParser.player(json, PLAYER_NO_EXTRAS)!!
        assertTrue(player.extraSubtitles.isEmpty())
    }

    @Test
    fun anAnswerWithoutAPathOrMirrorIsNotAStream() {
        assertNull(HStreamParser.player(json, """{"title":"x","stream_domains":["https://a.example"]}"""))
        assertNull(HStreamParser.player(json, """{"stream_url":"2026/X/E01","stream_domains":[]}"""))
        assertNull(HStreamParser.player(json, "<html>419 Page Expired</html>"))
    }

    @Test
    fun h264LeadsAndEachStreamUsesADifferentMirror() {
        val player = HStreamParser.player(json, PLAYER_NO_EXTRAS)!!
        val result = HStreamParser.sources(player, preferAv1 = false, mirrorStart = 0)
        assertEquals(
            listOf(
                "https://imoto-str.ane-h.xyz/2026/Furachi/E01/720/manifest.mpd",
                "https://chibi-str.imoto-h.xyz/2026/Furachi/E01/x264.720p.mp4",
            ),
            result.streams.map { it.url },
        )
        assertTrue(result.streams[0].isDash)
        assertTrue(result.streams[1].isMediaFile)
        assertEquals(listOf(true, false), result.streams.map { it.isActive })
        assertEquals(listOf("https://imoto-str.ane-h.xyz/2026/Furachi/E01/eng.vtt"), result.subtitles.map { it.url })
    }

    @Test
    fun av1LeadsOnlyWhenTheDeviceCanDecodeIt() {
        val player = HStreamParser.player(json, PLAYER_NO_EXTRAS)!!
        val result = HStreamParser.sources(player, preferAv1 = true, mirrorStart = 1)
        assertEquals(
            listOf(
                // Starting at the second of two mirrors: every next attempt is on the other host.
                "https://chibi-str.imoto-h.xyz/2026/Furachi/E01/1080/manifest.mpd",
                "https://imoto-str.ane-h.xyz/2026/Furachi/E01/720/manifest.mpd",
                "https://chibi-str.imoto-h.xyz/2026/Furachi/E01/x264.720p.mp4",
            ),
            result.streams.map { it.url },
        )
        assertEquals(listOf(true, false, false), result.streams.map { it.isActive })
    }

    @Test
    fun machineTranslationsFollowEnglishAsSsa() {
        val player = HStreamParser.player(json, PLAYER_WITH_EXTRAS)!!
        val subtitles = HStreamParser.sources(player, preferAv1 = false, mirrorStart = 0).subtitles
        assertEquals(listOf("en", "de", "hi"), subtitles.map { it.language })
        assertEquals("https://a.example/2026/Furachi/E01/autotrans/de.ass", subtitles[1].url)
        assertEquals("German (auto-translated)", subtitles[1].label)
        assertEquals("text/x-ssa", subtitles[1].mimeType)
    }

    @Test
    fun exactRomajiSlugsMatch() {
        assertTrue(score("Furachi", "furachi") >= 0.85)
        assertTrue(score("Bitch Gakuen ga Seijun na Hazu ga Nai!!? The Animation", "bitch-gakuen-ga-seijun-na-hazu-ga-nai-the-animation") >= 0.85)
    }

    @Test
    fun theSpellingsThatMissedNowMatch() {
        // Each of these was a measured miss against AniList before its variant was added.
        assertTrue(score("Ito wo Yoru", "ito-o-yoru") >= 0.85)
        assertTrue(score("Imouto Paradise! 2", "imouto-paradise-s2") >= 0.85)
        assertTrue(score("Yarichin Kateikyoushi Netori Houkoku: Dosukebe Kyonyuu Oyakodon", "yarichin-kateikyoushi-netori-houkoku") >= 0.85)
    }

    @Test
    fun aDifferentSeasonNeverMatches() {
        // 0.93 on bigrams alone — over the bar — which is why numbers have to agree.
        assertTrue(score("Imouto Paradise!", "imouto-paradise-s2") < 0.85)
        assertFalse(HStreamProvider.numbersAgree("Kokuhaku", "kokuhaku 2"))
        assertTrue(HStreamProvider.numbersAgree("Tsuma Netori 3: Kairaku", "tsuma netori 3 kairaku"))
    }

    @Test
    fun anUnrelatedTitleDoesNotClearTheBar() {
        assertTrue(score("Frieren: Beyond Journey's End", "furachi") < 0.85)
        assertTrue(score("Iinari! Saimin Kanojo", "kanojo-saimin") < 0.85)
    }

    private fun score(aniList: String, slug: String) = HStreamProvider.seriesScore(listOf(aniList), slug)

    private companion object {
        const val PLAYER_NO_EXTRAS = """{"title":"Furachi - 1","poster":"/images/hentai/furachi/gallery-ep-1-0.webp",
            "interpolated":1,"interpolated_uhd":0,"stream_url":"2026/Furachi/E01",
            "stream_domains":["https://imoto-str.ane-h.xyz","https://chibi-str.imoto-h.xyz"],
            "asia_stream_domains":["https://komako-b-str.musume-h.xyz"],"extra_subtitles":[]}"""

        const val PLAYER_WITH_EXTRAS = """{"stream_url":"/2026/Furachi/E01/",
            "stream_domains":["https://a.example/","https://b.example"],
            "extra_subtitles":{"hi":"Hindi","de":"German"}}"""
    }
}
