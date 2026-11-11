package com.anilili.data.remote

import com.anilili.data.model.hasUsableWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed from live gateway responses captured on 2026-10-06 while probing
 * `/library/search`, `/library/episodes/668552ccd418eb2ad21e3501` and
 * `/library/episode/sources` for One Piece episode 1 — the same payloads the provider parses.
 */
class RiftParserTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(text: String) = json.parseToJsonElement(text) as JsonObject

    // ---- AniList join ----------------------------------------------------------------------

    @Test
    fun `the AniList id comes out of the cover filename in every shape AniList has used`() {
        // There is no AniList or MAL id field anywhere in this API, so this URL is the only exact
        // join to the app's own catalogue. All five filename forms carry the same number.
        assertEquals(21, RiftParser.anilistId(obj(cover("large/bx21-ELSYx3yMPcKM.jpg"))))
        assertEquals(6702, RiftParser.anilistId(obj(cover("medium/b6702-KI4qgSMyI8Pm.png"))))
        assertEquals(10033, RiftParser.anilistId(obj(cover("large/nx10033-V7xnlgAVtaVR.jpg"))))
        assertEquals(106572, RiftParser.anilistId(obj(cover("medium/n106572-k1gqIsDcqGaV.jpg"))))
        assertEquals(15323, RiftParser.anilistId(obj(cover("medium/15323.jpg"))))
    }

    @Test
    fun `a proxied cover yields no id, which is why medium_picture is the field that is read`() {
        // `main_picture` is a copy under covers.embrassme.top whose path is a hash. Reading it
        // instead of medium_picture is the mistake this pins: 0 of 150 home items resolved.
        val item = """{"main_picture":"https://covers.embrassme.top/5c6e3db3ac830d53a50e67d16d8dcb34/v1/cover-0.jpg"}"""

        assertNull(RiftParser.anilistId(obj(item)))
    }

    @Test
    fun `a MyAnimeList image URL is not mistaken for an AniList id`() {
        // The fallback cover for entries with no AniList art is a MAL image whose path number is an
        // image id, not a media id. Reading it as one would join the wrong show.
        val item = """{"medium_picture":"https://cdn.myanimelist.net/images/anime/1877/160134.jpg"}"""

        assertNull(RiftParser.anilistId(obj(item)))
    }

    // ---- Matching --------------------------------------------------------------------------

    @Test
    fun `an exact AniList id wins over a better title match`() {
        val chosen = RiftParser.best(
            candidates = listOf(
                candidate("c1", "sousou no frieren", 2023, anilistId = null),
                candidate("c2", "frieren: beyond journey's end", 2023, anilistId = 154587),
            ),
            titles = listOf("sousou no frieren"),
            year = 2023,
            anilistId = 154587,
        )

        assertEquals("c2", chosen?.id)
    }

    @Test
    fun `a candidate identifying as a different AniList entry loses a tie`() {
        // Their dataset is not perfect, so a wrong id discounts a candidate rather than removing it
        // — but it must not be able to beat an equally named entry that claims no id at all.
        val chosen = RiftParser.best(
            candidates = listOf(
                candidate("right", "one piece", 1999, anilistId = null),
                candidate("wrong", "one piece", 1999, anilistId = 999_999),
            ),
            titles = listOf("one piece"),
            year = 1999,
            anilistId = 21,
        )

        assertEquals("right", chosen?.id)
    }

    @Test
    fun `a wrong season cannot take the right season's place`() {
        val titles = listOf("black clover 2nd season")
        val candidates = listOf(candidate("season1", "black clover", 2017, anilistId = null))

        // Left alone the title alone scores ~0.70, which clears the floor; the year does not match,
        // and a viewer sent to season 1 under a season 2 header is the failure this prevents.
        assertNull(RiftParser.best(candidates, titles, year = 2026, anilistId = 999_999))
        assertEquals("season1", RiftParser.best(candidates, titles, year = 2017, anilistId = 999_999)?.id)
    }

    @Test
    fun `nothing close enough is refused rather than guessed`() {
        assertNull(
            RiftParser.best(
                candidates = listOf(candidate("x", "bleach", 2004, anilistId = null)),
                titles = listOf("naruto"),
                year = 2002,
                anilistId = 20,
            ),
        )
    }

    // ---- Episodes and skip windows ---------------------------------------------------------

    @Test
    fun `episodes are ordered and carry Rift's own intro and outro windows`() {
        val episodes = RiftParser.episodes(obj(EPISODES))

        assertEquals(listOf(1, 2), episodes.map { it.number })
        assertEquals("668552cdd418eb2ad21e3503", episodes[0].id)
        val skip = RiftParser.skipTimes(episodes[0])
        assertEquals(31.0, skip?.introStart)
        assertEquals(111.0, skip?.introEnd)
        assertEquals(1376.0, skip?.outroStart)
        assertEquals(1447.0, skip?.outroEnd)
        assertTrue(skip!!.hasUsableWindow())
    }

    @Test
    fun `an episode with no published window offers no skip rather than a zero-length one`() {
        val second = RiftParser.episodes(obj(EPISODES))[1]

        assertNull(RiftParser.skipTimes(second))
    }

    @Test
    fun `a row without an id or a number is dropped instead of becoming episode zero`() {
        val episodes = RiftParser.episodes(
            obj("""{"items":[{"episode_number":1},{"_id":"a","episode_number":0},{"_id":"b","episode_number":7}]}"""),
        )

        assertEquals(listOf(7), episodes.map { it.number })
    }

    // ---- Hosts -----------------------------------------------------------------------------

    @Test
    fun `an Arabic subtitle request keeps only Arabic subtitle rows`() {
        val hosts = RiftParser.hosts(obj(SOURCES), "ar_sub")

        assertTrue(hosts.isNotEmpty())
        assertTrue("a dub row was offered as a sub: $hosts", hosts.none { it.audio.startsWith("ar_dub") })
        assertTrue("a non-Arabic row was offered: $hosts", hosts.none { it.audio.startsWith("it_") })
        assertTrue(hosts.none { it.audio.startsWith("en_") })
    }

    @Test
    fun `an Arabic dub request keeps only dub rows`() {
        val hosts = RiftParser.hosts(obj(SOURCES), "ar_dub")

        assertEquals(1, hosts.size)
        assertEquals("6a6613318d5e7d182de80679", hosts.first().id)
        assertEquals("cr2", hosts.first().provider)
    }

    @Test
    fun `hosts are ordered by the priority the service publishes, with ticket hosts last`() {
        // The service's own `configs` is the maintained ranking, so it orders every host that hands
        // back a URL. streamtape answers with a ticket and a 5s wait instead, and from the app its
        // API is behind a Cloudflare challenge, so it goes last even though the service ranks it at
        // 3, above rift-streamer at 5. Ranked at 3 it was reached before rift-streamer and ran One
        // Piece 1100's 8s source budget out (2026-10-06).
        val hosts = RiftParser.hosts(obj(SOURCES), "ar_sub")

        assertEquals(listOf("cr2", "rift-streamer", "streamtape"), hosts.map { it.provider }.distinct())
        // One row per host, so cr2 and streamtape each appear twice; within a rank the 1080p row
        // comes before the 720p one, and a tagged row before the untagged one at the same rank.
        assertEquals(
            listOf(
                "6a621ed18d5e7d182ddc10b5",
                "6a621ed28d5e7d182ddc10b9",
                "69e80ee80e0d7d5e12307b70",
                "6a3b97298d5e7d182d723f16",
                "6a3b97298d5e7d182d723f18",
            ),
            hosts.map { it.id },
        )
    }

    @Test
    fun `a host offering 1080p leads one offering only 240p at the same priority`() {
        val cr2 = RiftParser.hosts(obj(SOURCES), "ar_sub").filter { it.provider == "cr2" }

        assertEquals(listOf("6a621ed18d5e7d182ddc10b5", "6a621ed28d5e7d182ddc10b9"), cr2.map { it.id })
    }

    @Test
    fun `an untagged row is taken for the subtitle track and ranked behind a tagged one`() {
        val hosts = RiftParser.hosts(obj(SOURCES), "ar_sub")

        val untagged = hosts.firstOrNull { it.audio.isBlank() }
        assertEquals("6a3b97298d5e7d182d723f18", untagged?.id)
        assertTrue(
            "an untagged row was placed ahead of a tagged one of the same rank: $hosts",
            hosts.indexOfFirst { it.audio.isBlank() } > hosts.indexOfFirst { it.audio == "ar_sub" },
        )
    }

    @Test
    fun `an untagged row is never offered as a dub`() {
        // A blank tag says nothing about audio, and the extension's reading of it as Arabic is about
        // subtitles. Offering one under a dub header would mislabel the track the viewer asked for.
        assertTrue(RiftParser.hosts(obj(SOURCES), "ar_dub").none { it.audio.isBlank() })
    }

    @Test
    fun `the server ranking is read from the configs array`() {
        val priorities = RiftParser.priorities(obj(SOURCES))

        assertEquals(2, priorities["cr2"])
        assertEquals(3, priorities["streamtape"])
        assertEquals(5, priorities["rift-streamer"])
        // Present in `items` but absent from `configs`, so it must fall to the unranked tail.
        assertNull(priorities["animeworld_realtime"])
    }

    // ---- Qualities -------------------------------------------------------------------------

    @Test
    fun `qualities are asked for highest first and the label is normalised`() {
        assertEquals(listOf("1080P", "720P", "360P", "240P"), RiftParser.qualityOrder(listOf("240P", "1080P", "360P", "720P")))
        assertEquals("1080p", RiftParser.qualityLabel("1080P"))
        assertEquals(1080, RiftParser.qualityHeight("1080P"))
        assertEquals(720, RiftParser.qualityHeight("720p"))
    }

    @Test
    fun `a host publishing no qualities is asked for 720P and a label with no number survives`() {
        assertEquals(listOf("720P"), RiftParser.qualityOrder(emptyList()))
        assertEquals("auto", RiftParser.qualityLabel("auto"))
        assertNull(RiftParser.qualityHeight("auto"))
    }

    // ---- Resolve shapes --------------------------------------------------------------------

    @Test
    fun `a url_response host resolves to a playable URL with the headers it asked for`() {
        val link = RiftParser.link(obj(HLS_LINK)) { null }

        assertTrue(link.url!!.startsWith("https://media-1.rift-content.com/video/hls/"))
        // The playlist is refused without these two: 404 and an HTML page bare, mpegurl with them.
        assertEquals("in-app", link.headers["x-player"])
        assertTrue(link.headers["x-device-id"]!!.isNotBlank())
    }

    @Test
    fun `an MP4 host resolves with no headers at all`() {
        val link = RiftParser.link(obj(MP4_LINK)) { null }

        assertTrue(link.url!!.endsWith("master.mp4"))
        assertTrue("an MP4 host needs no headers", link.headers.isEmpty())
    }

    @Test
    fun `a ticket_response host is resolved through the exchange, and fails closed`() {
        val resolved = RiftParser.link(obj(TICKET_LINK)) { "https://cdn.streamtape.com/file/x" }
        assertEquals("https://cdn.streamtape.com/file/x", resolved.url)

        // A ticket exchange that yields nothing must not produce a stream pointing at null.
        assertNull(RiftParser.link(obj(TICKET_LINK)) { null }.url)
    }

    @Test
    fun `a response with neither shape resolves to nothing`() {
        assertNull(RiftParser.link(obj("""{"hostId":"x","player_properties":[]}""")) { "https://ignored" }.url)
    }

    @Test
    fun `subtitle tracks are carried with a language code`() {
        val link = RiftParser.link(
            obj("""{"url_response":true,"videoUrl":"https://m/x.m3u8","tracks":[{"file":"https://m/ar.vtt","label":"Arabic"}]}"""),
        ) { null }

        assertEquals(1, link.tracks.size)
        assertEquals("ar", link.tracks.first().language)
        assertEquals("Arabic", link.tracks.first().label)
    }

    @Test
    fun `a device fingerprint is a JSON string with the build id the headers later echo`() {
        val (buildId, info) = RiftParser.syntheticDevice()

        assertTrue(buildId.startsWith("AP2A.240805."))
        val parsed = obj(info)
        assertEquals(buildId, parsed["buildId"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(RiftParser.fcmToken().startsWith("APA91b"))
        assertEquals(86, RiftParser.fcmToken().length)
        assertFalse(RiftParser.deviceTimezone().isBlank())
    }

    private fun cover(path: String) =
        """{"medium_picture":"https://s4.anilist.co/file/anilistcdn/media/anime/cover/$path"}"""

    private fun candidate(id: String, title: String, year: Int?, anilistId: Int?) =
        RiftParser.RiftCandidate(id, title, null, year, anilistId)

    private companion object {
        /** `/library/episodes/668552ccd418eb2ad21e3501` (One Piece), first two rows. */
        val EPISODES = """
            {"items":[
              {"_id":"668552cdd418eb2ad21e3503","episode_number":1,"is_filler":false,
               "intro":{"start":31,"end":111},"outro":{"start":1376,"end":1447},
               "title_translations":{"ar":"أنا لوفي!"}},
              {"_id":"668552e4d418eb2ad21e3505","episode_number":2,"is_filler":false,
               "intro":null,"outro":null}
            ],"arcs":[],"favorites":[],"watchHistory":[],"assign_arc":true}
        """.trimIndent()

        /**
         * `/library/episode/sources` for One Piece episode 1, rows trimmed to one per case that
         * matters: two cr2 subtitle rows at different qualities, a cr2 dub row, a streamtape row, a
         * rift-streamer row with four qualities, an untagged row, and the Italian and English rows
         * that must be filtered out.
         */
        val SOURCES = """
            {"items":[
              {"_id":"695afa1001497ecb87d3d5bc","provider":"animeworld_realtime","server_name":"ANW","sub_title":"it_dub","qualities":[]},
              {"_id":"6a6613318d5e7d182de80679","provider":"cr2","server_name":"CR2","sub_title":"ar_dub","qualities":["1080P"]},
              {"_id":"6a621ed28d5e7d182ddc10b9","provider":"cr2","server_name":"CR2","sub_title":"ar_sub","qualities":["720P"]},
              {"_id":"6a621ed18d5e7d182ddc10b5","provider":"cr2","server_name":"CR2","sub_title":"ar_sub","qualities":["1080P"]},
              {"_id":"6a3b97298d5e7d182d723f18","provider":"streamtape","server_name":"STP","sub_title":"","qualities":["360P"]},
              {"_id":"6a6160458d5e7d182dda5fd9","provider":"streamtape","server_name":"STP","sub_title":"en_sub","qualities":["240P"]},
              {"_id":"6a3b97298d5e7d182d723f16","provider":"streamtape","server_name":"STP","sub_title":"ar_sub","qualities":["240P"]},
              {"_id":"69e80ee80e0d7d5e12307b70","provider":"rift-streamer","server_name":"VRV","sub_title":"ar_sub","qualities":["240P","360P","720P","1080P"]}
            ],
            "configs":[
              {"server_key":"bkbz","priority":1,"with_multi_qualities":true},
              {"server_key":"anineko_realtime","priority":1},
              {"server_key":"cr2","priority":2,"with_multi_qualities":true,"in_app_download_disabled":false},
              {"server_key":"streamtape","priority":3,"with_multi_qualities":true},
              {"server_key":"videa_realtime","priority":4},
              {"server_key":"rift-streamer","priority":5,"with_multi_qualities":true,"in_app_download_disabled":true},
              {"server_key":"idrivee2","priority":6},
              {"server_key":"animeworld_realtime","with_multi_qualities":false}
            ],
            "supported_languages":["ar","en","it"],"episode_watched":false}
        """.trimIndent()

        /** `direct_link` for the rift-streamer host: an HLS URL plus the two headers it wants. */
        val HLS_LINK = """
            {"url_response":true,
             "videoUrl":"https://media-1.rift-content.com/video/hls/g0emYssuPv0s/one_piece_ep_1_1080p_ar_sub.m3u8?t=-Ey25K0eZSDo",
             "http_headers":{"x-player":"in-app","x-device-id":"eKKkd4sPLVMhBgU7TAlAzU:APA91bm770sW3zHn"},
             "player_properties":[],"hostId":"69e80ee80e0d7d5e12307b70","tracks":[]}
        """.trimIndent()

        /** `direct_link` for a cr2 host: a plain MP4 and no headers of any kind. */
        val MP4_LINK = """
            {"url_response":true,
             "videoUrl":"https://media.spacetoonfanboy.xyz/64ef05de6bbe22f1d5851e765831add0/1/ar_dub/1080p/v1/master.mp4",
             "videoSize":450392713,"player_properties":[],"hostId":"6a6613318d5e7d182de80679","tracks":[]}
        """.trimIndent()

        /** `direct_link` for a streamtape host: a file id and a ticket, and a 5s wait. */
        val TICKET_LINK = """
            {"fileId":"28bqyMeWdMsZ4X1","ticket_response":true,
             "ticket":"28bqyMeWdMsZ4X1~d056ff820f93a060d9cf~1791272185~n~~1~-BKkx8ZqXsgzT_Vy",
             "wait_time":5,"hostId":"69e81da90e0d7d5e12309cb1","tracks":[]}
        """.trimIndent()
    }
}
