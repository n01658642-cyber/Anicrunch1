package com.anilili.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payloads taken from animeonsen.xyz on 2026-09-13, trimmed but not invented: the search hit, the
 * detail object and the video envelope are the shapes the live v4 API answered with for Jujutsu
 * Kaisen (`h9H66opVGvuRLPFH`).
 */
class AnimeOnsenParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(raw: String) = json.parseToJsonElement(raw.trimIndent()) as JsonObject

    @Test
    fun `the search token is read whichever way round the meta tag is written`() {
        val normal = """<meta name="ao-search-token" content="abc123DEF"/>"""
        val reversed = """<meta content="abc123DEF" name="ao-search-token">"""

        assertEquals("abc123DEF", AnimeOnsenParser.searchToken(normal))
        assertEquals("abc123DEF", AnimeOnsenParser.searchToken(reversed))
        assertNull(AnimeOnsenParser.searchToken("<meta name=\"description\" content=\"x\">"))
    }

    @Test
    fun `a search hit keeps every title spelling it publishes`() {
        val hits = AnimeOnsenParser.hits(
            obj(
                """
                {"hits":[
                  {"content_title":"Jujutsu Kaisen","content_title_en":"Jujutsu Kaisen (TV)",
                   "content_title_jp":"呑術","content_id":"h9H66opVGvuRLPFH"},
                  {"content_title":"Jujutsu Kaisen 2nd Season","content_id":"QT3WWZisLZodMrCB"}
                ],"estimatedTotalHits":2}
                """,
            ),
        )

        assertEquals(2, hits.size)
        assertEquals("h9H66opVGvuRLPFH", hits[0].contentId)
        assertEquals(3, hits[0].titles.size)
        assertTrue(hits[0].titles.contains("Jujutsu Kaisen (TV)"))
        // A hit missing the optional spellings is still usable rather than dropped.
        assertEquals(listOf("Jujutsu Kaisen 2nd Season"), hits[1].titles)
    }

    /** A hit with no id cannot be asked about, so it must not reach the confirmation step. */
    @Test
    fun `a search hit without a content id is dropped`() {
        assertTrue(AnimeOnsenParser.hits(obj("""{"hits":[{"content_title":"Orphan"}]}""")).isEmpty())
        assertTrue(AnimeOnsenParser.hits(obj("""{}""")).isEmpty())
    }

    /** The id this provider confirms against, and the count the picker draws its rows from. */
    @Test
    fun `the detail payload yields the MAL id and the episode count`() {
        val detail = AnimeOnsenParser.detail(
            obj("""{"content_id":"h9H66opVGvuRLPFH","total_episodes":24,"mal_id":40748,"is_movie":false}"""),
        )

        assertEquals(40748, detail.malId)
        assertEquals(24, detail.episodes)
    }

    private val videoPayload = """
        {"metadata":{"content_id":"h9H66opVGvuRLPFH","total_episodes":24,
          "episode":[1,
            {"skipIntro_s":"55","skipIntro_e":"145","nextEpisode_s":"1424",
             "contentTitle_episode_en":"Ryomen Sukuna"},
            {"1":{"contentTitle_episode_en":"Ryomen Sukuna"}}]},
         "uri":{"stream":"https://cdn.animeonsen.xyz/video/mp4-dash/h9H66opVGvuRLPFH/1/manifest.mpd",
           "subtitles":{
             "ar-ME":"https://api.animeonsen.xyz/v4/subtitles/h9H66opVGvuRLPFH/ar-ME/1",
             "de-DE":"https://api.animeonsen.xyz/v4/subtitles/h9H66opVGvuRLPFH/de-DE/1",
             "en-US":"https://api.animeonsen.xyz/v4/subtitles/h9H66opVGvuRLPFH/en-US/1",
             "pt-BR":"https://api.animeonsen.xyz/v4/subtitles/h9H66opVGvuRLPFH/pt-BR/1"}}}
    """

    @Test
    fun `the video payload yields a referer-gated dash stream`() {
        val result = AnimeOnsenParser.sources(obj(videoPayload))

        assertEquals(1, result.streams.size)
        val stream = result.streams.single()
        assertEquals(
            "https://cdn.animeonsen.xyz/video/mp4-dash/h9H66opVGvuRLPFH/1/manifest.mpd",
            stream.url,
        )
        assertEquals("dash", stream.type)
        assertTrue(stream.isDash)
        assertEquals("https://www.animeonsen.xyz/", stream.referer)
        assertTrue(stream.isActive)
    }

    /**
     * The whole reason this provider is worth a lane. The MIME type matters as much as the URLs:
     * these have no file extension, so an inferred type would read SubStation Alpha as WebVTT and
     * render nothing at all.
     */
    @Test
    fun `every subtitle language survives with an explicit ASS mime type`() {
        val subs = AnimeOnsenParser.sources(obj(videoPayload)).subtitles

        assertEquals(4, subs.size)
        assertTrue(subs.all { it.mimeType == "text/x-ssa" })
        assertTrue(subs.all { it.referer == "https://www.animeonsen.xyz/" })
        // English leads because the player marks the first track default; the rest are alphabetical
        // so the menu does not reshuffle with the JSON's ordering.
        assertEquals("English", subs.first().label)
        assertEquals("en", subs.first().language)
        assertEquals(listOf("English", "Arabic", "German", "Portuguese (Brazil)"), subs.map { it.label })
        assertEquals(listOf("en", "ar", "de", "pt"), subs.map { it.language })
    }

    @Test
    fun `the intro window comes through as skip times`() {
        val skip = AnimeOnsenParser.sources(obj(videoPayload)).skip

        assertEquals(55.0, skip?.introStart)
        assertEquals(145.0, skip?.introEnd)
        // `nextEpisode_s` is a prompt cue, not a window with a known end, so no outro is claimed.
        assertNull(skip?.outroStart)
        assertNull(skip?.outroEnd)
    }

    /** An episode with no timings, or a zero-length window, must not produce a skip button. */
    @Test
    fun `a missing or empty intro window yields no skip times`() {
        val noTimings = """
            {"metadata":{"episode":[3,{"contentTitle_episode_en":"Girl of Steel"},{}]},
             "uri":{"stream":"https://cdn.animeonsen.xyz/x/manifest.mpd"}}
        """
        val emptyWindow = """
            {"metadata":{"episode":[3,{"skipIntro_s":"90","skipIntro_e":"90"},{}]},
             "uri":{"stream":"https://cdn.animeonsen.xyz/x/manifest.mpd"}}
        """
        val noMetadata = """{"uri":{"stream":"https://cdn.animeonsen.xyz/x/manifest.mpd"}}"""

        assertNull(AnimeOnsenParser.sources(obj(noTimings)).skip)
        assertNull(AnimeOnsenParser.sources(obj(emptyWindow)).skip)
        assertNull(AnimeOnsenParser.sources(obj(noMetadata)).skip)
    }

    /** A payload with no stream is a failure, not an empty source list the picker would show. */
    @Test
    fun `a video payload with no stream is refused`() {
        val thrown = runCatching { AnimeOnsenParser.sources(obj("""{"uri":{"subtitles":{}}}""")) }
        assertTrue(thrown.isFailure)
        assertTrue(runCatching { AnimeOnsenParser.sources(obj("""{"metadata":{}}""")) }.isFailure)
    }

    /** An unknown tag is passed through rather than dropped, so a new language still appears. */
    @Test
    fun `locale tags map to names and primary subtags`() {
        assertEquals("Spanish (Latin America)", AnimeOnsenParser.label("es-LA"))
        assertEquals("es", AnimeOnsenParser.language("es-LA"))
        assertEquals("Russian", AnimeOnsenParser.label("ru-RU"))
        assertEquals("nl-NL", AnimeOnsenParser.label("nl-NL"))
        assertEquals("nl", AnimeOnsenParser.language("nl-NL"))
    }
}
