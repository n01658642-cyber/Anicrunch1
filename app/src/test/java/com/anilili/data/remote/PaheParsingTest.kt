package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shapes taken from animepahe.pw on 2026-09-09, not invented: the search and release bodies are
 * trimmed copies of live responses, and the playlist is the one a real Kwik embed unpacked to.
 */
class PaheParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `absolute season numbering resolves Bleach episode seven to release forty seven`() {
        val page = PaheParser.parseRelease(json.parseToJsonElement("""
            {"total":7,"per_page":30,"current_page":1,"last_page":1,
             "data":[{"episode":41,"episode2":0,"session":"first"},
                     {"episode":47,"episode2":0,"session":"seventh"}]}
        """))

        assertEquals(40, page.episodeOffset)
        assertEquals(mapOf(1 to "first", 7 to "seventh"), page.episodes)
        assertNull(page.episodes[2]) // Missing rows must not shift later episode numbers.
    }

    @Test
    fun `later release pages retain the first page season offset`() {
        val body = json.parseToJsonElement("""
            {"total":37,"per_page":30,"current_page":2,"last_page":2,
             "data":[{"episode":71,"session":"thirty-first"},
                     {"episode":77,"session":"thirty-seventh"}]}
        """)
        assertEquals(
            mapOf(31 to "thirty-first", 37 to "thirty-seventh"),
            PaheParser.parseRelease(body, episodeOffset = 40).episodes,
        )
        assertEquals(
            mapOf(71 to "thirty-first", 77 to "thirty-seventh"),
            PaheParser.parseRelease(body, episodeOffset = 0).episodes,
        )
    }

    @Test
    fun `search yields the session a series is keyed by`() {
        val body = """
            {"total":25,"per_page":8,"current_page":1,"last_page":4,
             "data":[{"id":1571,"title":"One Piece","type":"TV","episodes":1177,
                      "session":"39fd216a-38e9-5d79-fe1b-9d50a683bca4"},
                     {"id":99,"title":"One Piece Film: Red","episodes":1,"session":"abc-def"}]}
        """.trimIndent()

        val candidates = PaheParser.parseSearch(json.parseToJsonElement(body))

        assertEquals(2, candidates.size)
        assertEquals("39fd216a-38e9-5d79-fe1b-9d50a683bca4", candidates[0].session)
        assertEquals("One Piece", candidates[0].title)
        assertEquals(1177, candidates[0].episodes)
    }

    @Test
    fun `a release page reports the whole catalogue and the episodes on it`() {
        val body = """
            {"total":1177,"per_page":30,"current_page":1,"last_page":40,
             "data":[{"episode":1,"session":"6ff403be3e1c","title":"","snapshot":"a.jpg","filler":0},
                     {"episode":2,"session":"aa11bb22","title":"Two","snapshot":"b.jpg","filler":0}]}
        """.trimIndent()

        val page = PaheParser.parseRelease(json.parseToJsonElement(body))

        assertEquals(1177, page.total)
        assertEquals(30, page.perPage)
        assertEquals(40, page.lastPage)
        assertEquals("6ff403be3e1c", page.episodes[1])
        assertEquals("aa11bb22", page.episodes[2])
    }

    /**
     * The reason this provider can match exactly instead of guessing: the series page links out to
     * both databases, so a title search only has to *propose* a candidate.
     */
    @Test
    fun `the series page yields the AniList and MAL ids`() {
        val html = """
            <div class="external-links">
              <a href="https://anilist.co/anime/21">AniList</a>
              <a href="https://myanimelist.net/anime/21">MyAnimeList</a>
            </div>
        """.trimIndent()

        assertEquals(21 to 21, PaheParser.parseIds(html))
        assertEquals(null to null, PaheParser.parseIds("<div>nothing here</div>"))
    }

    @Test
    fun `the play page names its kwik embed`() {
        val html = """<script>let url = "https://kwik.cx/e/InzZMv1U52OE";</script>"""

        assertEquals("https://kwik.cx/e/InzZMv1U52OE", PaheParser.kwikUrl(html))
    }

    /** A `let url` that is not an embed must not be mistaken for one. */
    @Test
    fun `an unrelated let url is refused`() {
        assertNull(PaheParser.kwikUrl("""let url = "https://animepahe.pw/anime/x";"""))
        assertNull(PaheParser.kwikUrl("<html>no player here</html>"))
    }

    /**
     * Kwik ships its player packed. The reference client matched `source='...'`, which no longer
     * appears — the current player assigns the playlist elsewhere — so the URL itself is matched
     * and survives that assignment being rewritten again.
     */
    @Test
    fun `a packed kwik player yields its playlist`() {
        val playlist = "https://vault-05.uwucdn.top/stream/05/08/0df7ff5c/uwu.m3u8"
        val packed = "eval(function(p,a,c,k,e,d){}('0=\"1\";',2,2,'src|$playlist'.split('|')))"

        assertTrue(AllAnimeJsUnpacker.detect(packed))
        assertEquals(playlist, PaheParser.playlistFrom(packed))
    }

    /** Packing is a per-deploy choice, so a plain page must still be read. */
    @Test
    fun `an unpacked player is read directly`() {
        val html = """<video data-src="https://vault-01.uwucdn.top/stream/a/uwu.m3u8"></video>"""

        assertEquals("https://vault-01.uwucdn.top/stream/a/uwu.m3u8", PaheParser.playlistFrom(html))
        assertNull(PaheParser.playlistFrom("<html>no playlist</html>"))
    }

    /**
     * Verbatim from the Mushoku Tensei season three play page on 2026-09-13, down to the entity in
     * the label — which is exactly what this must not need to read.
     */
    private val subOnlyMenu = """
        <div class="dropdown-menu" id="resolutionMenu" aria-labelledby="fansubMenu">
          <button type="button" data-src="https://kwik.cx/e/VOGfTT0yc7sD" data-url="https://kwik.cx/e/VOGfTT0yc7sD" data-fansub="SubsPlease" data-resolution="360" data-audio="jpn" data-av1="0" class="dropdown-item">SubsPlease &middot; 360p</button>
          <button type="button" data-src="https://kwik.cx/e/jqv3ZwvT1zdH" data-url="https://kwik.cx/e/jqv3ZwvT1zdH" data-fansub="SubsPlease" data-resolution="720" data-audio="jpn" data-av1="0" class="dropdown-item">SubsPlease &middot; 720p</button>
          <button type="button" data-src="https://kwik.cx/e/DIpj0WDKNjR4" data-url="https://kwik.cx/e/DIpj0WDKNjR4" data-fansub="SubsPlease" data-resolution="1080" data-audio="jpn" data-av1="0" class="dropdown-item active">SubsPlease &middot; 1080p</button>
        </div>
    """.trimIndent()

    /** Solo Leveling's shape: two fansubs, three audio languages, nine buttons. */
    private val multiAudioMenu = """
        <div class="dropdown-menu" id="resolutionMenu">
          <button type="button" data-src="https://kwik.cx/e/a1" data-fansub="FLE" data-resolution="720" data-audio="jpn" data-av1="0" class="dropdown-item">FLE &middot; 720p BD</button>
          <button type="button" data-src="https://kwik.cx/e/a2" data-fansub="FLE" data-resolution="1080" data-audio="jpn" data-av1="1" class="dropdown-item">FLE &middot; 1080p BD</button>
          <button type="button" data-src="https://kwik.cx/e/b1" data-fansub="FLE" data-resolution="1080" data-audio="eng" data-av1="0" class="dropdown-item">FLE &middot; 1080p BD eng</button>
          <button type="button" data-src="https://kwik.cx/e/c1" data-fansub="WAP" data-resolution="1080" data-audio="kor" data-av1="0" class="dropdown-item">WAP &middot; 1080p BD kor</button>
        </div>
    """.trimIndent()

    @Test
    fun `the resolution menu is read from its attributes, not its labels`() {
        val variants = PaheParser.variants(subOnlyMenu)

        assertEquals(3, variants.size)
        assertEquals(listOf(360, 720, 1080), variants.map { it.resolution })
        assertTrue(variants.all { it.fansub == "SubsPlease" })
        assertTrue(variants.all { it.audio == PaheParser.AUDIO_JAPANESE })
        assertTrue(variants.none { it.av1 })
        assertEquals("https://kwik.cx/e/DIpj0WDKNjR4", variants[2].kwikUrl)
        assertEquals("SubsPlease 1080p", variants[2].label)
    }

    /** The download anchors share the page and must not be mistaken for playable entries. */
    @Test
    fun `only buttons with a kwik link become variants`() {
        val html = """
            <div id="pickDownload">
              <a href="https://pahe.win/LYXFh" class="dropdown-item">SubsPlease &middot; 360p (26MB)</a>
            </div>
            <button type="button" class="navbar-toggler">menu</button>
            <button type="button" data-src="https://example.invalid/e/x" data-resolution="1080">not kwik</button>
        """.trimIndent()

        assertTrue(PaheParser.variants(html).isEmpty())
    }

    /** A sub request takes the largest Japanese release and never wanders into a dub. */
    @Test
    fun `sub takes the highest resolution japanese release`() {
        val chosen = PaheParser.variantFor(
            PaheParser.variants(multiAudioMenu),
            audio = "sub",
            avoidAv1 = false,
        )

        assertEquals("https://kwik.cx/e/a2", chosen?.kwikUrl)
        assertEquals(PaheParser.AUDIO_JAPANESE, chosen?.audio)
    }

    /**
     * The codec outranks the resolution: a 1080p AV1 file the box cannot decode is worth less than
     * the 720p h264 one it can.
     */
    @Test
    fun `a television drops to h264 rather than take an av1 encode`() {
        val chosen = PaheParser.variantFor(
            PaheParser.variants(multiAudioMenu),
            audio = "sub",
            avoidAv1 = true,
        )

        assertEquals("https://kwik.cx/e/a1", chosen?.kwikUrl)
        assertEquals(720, chosen?.resolution)
    }

    /** "Dub" means English where English exists; Korean is a fallback, not a surprise. */
    @Test
    fun `dub prefers english over another dubbed language`() {
        val chosen = PaheParser.variantFor(
            PaheParser.variants(multiAudioMenu),
            audio = "dub",
            avoidAv1 = false,
        )

        assertEquals("https://kwik.cx/e/b1", chosen?.kwikUrl)
        assertEquals("FLE 1080p", chosen?.label)
    }

    @Test
    fun `a korean release still answers a dub request when it is the only one`() {
        val koreanOnly = PaheParser.variants(multiAudioMenu).filter { it.audio != "eng" }

        assertEquals("https://kwik.cx/e/c1", PaheParser.variantFor(koreanOnly, "dub", false)?.kwikUrl)
    }

    /** A sub-only series must report no dub rather than a row that can never resolve. */
    @Test
    fun `a sub only page publishes no dub`() {
        assertNull(PaheParser.variantFor(PaheParser.variants(subOnlyMenu), "dub", false))
        assertNull(PaheParser.variantFor(emptyList(), "sub", false))
    }

    /**
     * `audio` on a release row says a dub exists beside the sub, not instead of it — so it names
     * the dubbed episodes and the subtitled set is left whole.
     */
    @Test
    fun `a release page reports which episodes carry a dub`() {
        val page = PaheParser.parseRelease(json.parseToJsonElement("""
            {"total":3,"per_page":30,"current_page":1,"last_page":1,
             "data":[{"episode":1,"session":"one","audio":"eng"},
                     {"episode":2,"session":"two","audio":"jpn"},
                     {"episode":3,"session":"three","audio":""}]}
        """))

        assertEquals(setOf(1), page.dubbed)
        assertEquals(setOf(1, 2, 3), page.episodes.keys)
    }

    /** The dub set is numbered like the episodes are, so a season offset applies to both. */
    @Test
    fun `dubbed episodes follow the season offset`() {
        val page = PaheParser.parseRelease(json.parseToJsonElement("""
            {"total":2,"per_page":30,"current_page":1,"last_page":1,
             "data":[{"episode":41,"session":"first","audio":"eng"},
                     {"episode":47,"session":"seventh","audio":"eng"}]}
        """))

        assertEquals(setOf(1, 7), page.dubbed)
    }

    /** The play page states the ids as metadata rather than as links out. */
    @Test
    fun `the ids are read from meta tags too`() {
        val html = """
            <meta name="anidb" content="18727">
            <meta name="anilist" content="178789">
            <meta name="mal" content="59193">
        """.trimIndent()

        assertEquals(178789 to 59193, PaheParser.parseIds(html))
    }
}
