package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class AniDbAppProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: AniDbAppProvider

    /** Path (without query) → response; anything unlisted is a 404. */
    private val routes = mutableMapOf<String, MockResponse>()
    private val requested = mutableListOf<String>()

    private val deathNote = Media(
        id = 1_535,
        idMal = 1_535,
        title = MediaTitle(english = "Death Note", romaji = "Death Note"),
        episodes = 2,
        seasonYear = 2006,
        format = "TV",
    )

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                synchronized(requested) { requested += path }
                val key = path.substringBefore('?') + (if ("/cat/post" in path) "?" + path.substringAfter('?') else "")
                return routes[key] ?: MockResponse().setResponseCode(404).setBody("""{"message":"Not found."}""")
            }
        }
        val root = server.url("/").toString().removeSuffix("/")
        provider = AniDbAppProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            baseUrl = "$root/site",
            playUrl = "$root/play",
            catalogueUrl = "$root/cat",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun exactWebsiteIdentityStreamsThroughThePlayerApi() {
        routes["/site/search/suggestions"] = html(
            """<a href="/anime/death-note-1199" data-search-item><p class="text-sm font-medium">Death Note</p></a>""",
        )
        routes["/site/anime/death-note-1199"] = html(
            """<a href="https://anilist.co/anime/1535">AniList</a><a href="https://myanimelist.net/anime/1535">MAL</a>""",
        )
        playerApiForDeathNote()

        val availability = provider.episodeAvailability(deathNote, expectedCount = 2)

        assertEquals(setOf(1, 2), availability.sub)
        assertEquals(setOf(1), availability.dub)
        assertEquals("1000001199", provider.cachedPostId(deathNote.id))

        val stream = provider.sources(deathNote, audio = "sub", episode = 2).streams.single()
        assertEquals("https://hls.test/death-note-2/master.m3u8", stream.url)
        assertEquals("hls", stream.type)
        assertEquals("sub", stream.audio)
        assertNull(stream.referer)
        assertTrue("/play/episode/11/jpn/iframe" in requested)
    }

    @Test
    fun websiteInMaintenanceFallsBackToTheCatalogueWithoutRememberingTheGuess() {
        routes["/site/search/suggestions"] = MockResponse().setResponseCode(503).setBody("Under maintenance")
        routes["/cat/search"] = json("""{"posts":[{"id":1000001200},{"id":1000001199}]}""")
        // Same title, wrong year: the sequel must lose on the year before titles are compared.
        routes["/cat/post?id=1000001200"] = json(
            """{"id":1000001200,"type":"TV","title":"Death Note","premiered":"Summer 2007"}""",
        )
        routes["/cat/post?id=1000001199"] = json(
            """{"id":1000001199,"type":"TV","title":"Death Note","premiered":"Fall 2006"}""",
        )
        playerApiForDeathNote()

        val availability = provider.episodeAvailability(deathNote, expectedCount = 2)

        assertEquals(setOf(1, 2), availability.sub)
        assertTrue("/play/anime/1000001199/episodes" in requested)
        assertNull(provider.cachedPostId(deathNote.id))
    }

    @Test
    fun aWebsiteThatRedirectsElsewhereFallsBackToTheCatalogue() {
        // 2026-10-22: every anidb.app path became a 302 to anilab.so, a landing page answering 200
        // with nothing to parse. The same server under another host name stands in for it.
        val landing = server.url("/landing").newBuilder().host("127.0.0.1").build()
        routes["/site/search/suggestions"] = MockResponse().setResponseCode(302).setHeader("Location", landing)
        routes["/landing"] = html("<title>Anilab2</title>")
        routes["/cat/search"] = json("""{"posts":[{"id":1000001199}]}""")
        routes["/cat/post?id=1000001199"] = json(
            """{"id":1000001199,"type":"TV","title":"Death Note","premiered":"Fall 2006"}""",
        )
        playerApiForDeathNote()

        assertEquals(setOf(1, 2), provider.episodeAvailability(deathNote, expectedCount = 2).sub)
        assertTrue("/play/anime/1000001199/episodes" in requested)
    }

    @Test
    fun catalogueToleratesALateDecemberPremiereButPrefersTheExactYear() {
        routes["/site/search/suggestions"] = MockResponse().setResponseCode(503)
        routes["/cat/search"] = json("""{"posts":[{"id":1000002553}]}""")
        // AniList: 2021. Anilab: "Winter 2022".
        routes["/cat/post?id=1000002553"] = json(
            """{"id":1000002553,"type":"Movie","title":"Jujutsu Kaisen 0","premiered":"Winter 2022"}""",
        )
        routes["/play/anime/1000002553/episodes"] = json("""{"list":[{"id":"900","number":"1"}]}""")
        routes["/play/episode/900/servers"] = json("""{"list":[{"id":"900/jpn","lang":"sub"}]}""")
        val movie = Media(
            id = 131_573,
            title = MediaTitle(english = "Jujutsu Kaisen 0", romaji = "Jujutsu Kaisen 0"),
            episodes = 1,
            seasonYear = 2021,
            format = "MOVIE",
        )

        assertEquals(setOf(1), provider.episodeAvailability(movie, expectedCount = 1).sub)

        // Two arcs a year apart, both passing the filter: the exact year wins over the title.
        routes["/cat/search"] = json("""{"posts":[{"id":1000000023},{"id":1000000024}]}""")
        routes["/cat/post?id=1000000023"] = json(
            """{"id":1000000023,"type":"TV","title":"Demon Slayer: Swordsmith Village Arc","premiered":"Spring 2023"}""",
        )
        routes["/cat/post?id=1000000024"] = json(
            """{"id":1000000024,"type":"TV","title":"Demon Slayer: Hashira Training Arc","premiered":"Spring 2024"}""",
        )
        routes["/play/anime/1000000024/episodes"] = json("""{"list":[{"id":"901","number":"1"}]}""")
        routes["/play/episode/901/servers"] = json("""{"list":[{"id":"901/jpn","lang":"sub"}]}""")
        val hashira = Media(
            id = 166_240,
            title = MediaTitle(english = "Demon Slayer: Kimetsu no Yaiba Hashira Training Arc"),
            episodes = 1,
            seasonYear = 2024,
            format = "TV",
        )

        provider.episodeAvailability(hashira, expectedCount = 1)

        assertTrue("/play/anime/1000000024/episodes" in requested)
        assertTrue("/play/anime/1000000023/episodes" !in requested)
    }

    @Test
    fun anAnsweringWebsiteThatNamesAnotherTitleIsFinal() {
        routes["/site/search/suggestions"] = html(
            """<a href="/anime/death-note-rewrite-1200" data-search-item><p class="text-sm">Death Note</p></a>""",
        )
        routes["/site/anime/death-note-rewrite-1200"] = html(
            """<a href="https://anilist.co/anime/2994">AniList</a>""",
        )
        routes["/cat/search"] = json("""{"posts":[{"id":1000001199}]}""")

        val error = runCatching { provider.episodeAvailability(deathNote, expectedCount = 2) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("match not found"))
        assertTrue(requested.none { it.startsWith("/cat/") })
    }

    @Test
    fun aSeededPostIdSkipsTheWebsiteEntirely() {
        provider.seedPostId(deathNote.id, "1000001199")
        playerApiForDeathNote()

        provider.episodeAvailability(deathNote, expectedCount = 2)

        assertTrue(requested.none { it.startsWith("/site/") || it.startsWith("/cat/") })
    }

    @Test
    fun parserHandlesPlayerApiShapesOffsetsAndCloudflarePages() {
        val servers = AniDbAppParser.servers(
            Json.parseToJsonElement(
                """{"list":[{"id":"3062/jpn","lang":"sub","name":"SUB Server #1"},
                    {"id":"3062/jpn","lang":"sub","name":"SUB Server #2"},
                    {"id":"3062/eng","lang":"dub","name":"DUB Server #1"}]}""",
            ),
        )
        assertEquals(listOf("3062/jpn", "3062/eng"), servers.map { it.path })
        assertEquals("3062/eng", AniDbAppParser.serverForAudio(servers, "dub")?.path)
        assertTrue(AniDbAppParser.sameFormatFamily("TV_SHORT", "TV"))
        assertTrue(!AniDbAppParser.sameFormatFamily("TV", "ONA"))
        assertEquals(12, AniDbAppParser.inferOffset(listOf(13, 14, 15, 16), expectedCount = 4))
        assertEquals(0, AniDbAppParser.inferOffset(listOf(1, 2, 3, 4), expectedCount = 4))
        assertTrue(AniDbAppParser.isCloudflareChallenge("<script src=\"https://challenges.cloudflare.com/x\"></script>"))
    }

    @Test
    fun liveDeathNoteCatalogAndDirectHlsResolution() {
        assumeTrue(System.getenv("RUN_LIVE_ANIDBAPP_TESTS") == "1")
        val liveProvider = AniDbAppProvider(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
        val media = deathNote.copy(episodes = 37, status = "FINISHED")

        val availability = liveProvider.episodeAvailability(media, expectedCount = 37)
        assertTrue(1 in availability.sub)
        val source = liveProvider.sources(media, audio = "sub", episode = 1).streams.single()
        assertTrue(source.url.contains(".m3u8"))
    }

    private fun playerApiForDeathNote() {
        routes["/play/anime/1000001199/episodes"] = json(
            """{"list":[{"id":"10","name":"Episode 1","number":"1","filler":false},
                {"id":"11","name":"Episode 2","number":"2","filler":false}]}""",
        )
        routes["/play/episode/10/servers"] = json(
            """{"list":[{"id":"10/jpn","lang":"sub","name":"SUB Server #1"},
                {"id":"10/eng","lang":"dub","name":"DUB Server #1"}]}""",
        )
        routes["/play/episode/11/servers"] = json("""{"list":[{"id":"11/jpn","lang":"sub","name":"SUB Server #1"}]}""")
        routes["/play/episode/11/jpn/iframe"] = json("""{"link":"https:\/\/hls.test\/death-note-2\/master.m3u8"}""")
    }

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html")
        .setBody(body)

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
