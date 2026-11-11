package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeKaiProviderTest {
    @Test
    fun parsesSearchCardsAndMalIdentity() {
        val html = """
            <div class="aitem">
              <a class="poster" href="/watch/jujutsu-kaisen-2nd-season/ep-1"></a>
              <a class="title d-title" data-en="Jujutsu Kaisen 2nd Season">JJK 2</a>
            </div>
            <a href="https://myanimelist.net/anime/51009">MAL</a>
        """.trimIndent()

        val card = AnimeKaiParser.parseCards(html).single()

        assertEquals("jujutsu-kaisen-2nd-season", card.slug)
        assertEquals("Jujutsu Kaisen 2nd Season", card.title)
        assertEquals(51009, AnimeKaiParser.malId(html))
    }

    @Test
    fun parsesLanguageServersAndPlayerAssets() {
        val subtitle = "https%3A%2F%2Fcdn.test%2Fenglish.vtt"
        val html = """
            <div class="server-items lang-group" data-id="hsub">
              <span class="server-video" data-video="https://vivibebe.site/e/abc?sub=$subtitle&amp;sub_1=English">Vid</span>
            </div>
            <div class="server-items lang-group" data-id="dub">
              <span class="server-video" data-video="https://vivibebe.site/e/xyz">Vid</span>
            </div>
        """.trimIndent()

        val servers = AnimeKaiParser.parseServers(html)
        val subtitles = AnimeKaiParser.embedSubtitles(servers.first().videoUrl)

        assertEquals(listOf("hsub", "dub"), servers.map { it.language })
        assertEquals("https://cdn.test/english.vtt", subtitles.single().url)
        assertEquals("en", subtitles.single().language)
        assertEquals(
            "https://cdn.test/master.m3u8?token=abc",
            AnimeKaiParser.findHls("const src = \"https:\\/\\/cdn.test\\/master.m3u8?token=abc\";"),
        )
    }

    /**
     * The interrupt is the whole reason this class exists: it is the only signal a blocking
     * provider gets that its caller has stopped waiting, and the caller's real budget — 8s or 15s —
     * arrives no other way. The wall clock beside it is only a backstop.
     */
    @Test
    fun `deadline expires on the caller's interrupt as well as the clock`() {
        var clock = 0L
        val onTheClock = ProviderDeadline(budgetMs = 100) { clock }
        assertFalse(onTheClock.expired())
        clock = 100
        assertTrue(onTheClock.expired())

        val stillFresh = ProviderDeadline(budgetMs = 100) { 0 }
        Thread.currentThread().interrupt()
        try {
            assertTrue(stillFresh.expired())
        } finally {
            // Clearing it here rather than in the assertion keeps a failure from leaking an
            // interrupted thread into whatever JUnit runs next on it.
            Thread.interrupted()
        }
    }

    /**
     * The reported stall: five mirrors that all hang, fifteen requests at the client's full call
     * timeout apiece, and a provider still working three minutes into a fifteen second budget.
     */
    @Test
    fun `a search that outlives its budget stops instead of walking every mirror`() {
        withServer { server, mirrors ->
            var clock = 0L
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    clock += 15_000 // Every mirror hangs to the client's call timeout.
                    return MockResponse().setBody("<html></html>")
                }
            }
            val provider = AnimeKaiProvider(OkHttpClient(), mirrors) { clock }

            val failure = assertThrows(IllegalStateException::class.java) {
                provider.episodeAvailability(media())
            }

            assertEquals("AnimeKai match search ran out of time", failure.message)
            // Two requests, not the fifteen the unbounded walk would have made.
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a confident first title answers without searching the rest`() {
        withServer { server, mirrors ->
            server.enqueue(MockResponse().setBody(searchPage("frieren", "Frieren")))
            server.enqueue(MockResponse().setBody(watchPage()))

            val availability = AnimeKaiProvider(OkHttpClient(), mirrors).episodeAvailability(media())

            assertEquals(setOf(1, 2), availability.sub)
            // One search and the watch page it settled on. The romaji and native titles used to be
            // searched too, unconditionally, before anything was scored.
            assertEquals(2, server.requestCount)
            assertEquals("/m0/browser", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/m0/watch/frieren", server.takeRequest().requestUrl?.encodedPath)
        }
    }

    /**
     * Capping the fan-out at two mirrors would otherwise put the last three out of reach forever,
     * since a cold search always started at the first.
     */
    @Test
    fun `a search that finds nothing leaves the next one at fresh mirrors`() {
        withServer { server, mirrors ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("<html></html>")
            }
            val provider = AnimeKaiProvider(OkHttpClient(), mirrors)

            repeat(2) { runCatching { provider.episodeAvailability(media()) } }

            val visited = (1..server.requestCount)
                .map { server.takeRequest().requestUrl?.encodedPath.orEmpty().substringBefore("/browser") }
            assertEquals(listOf("/m0", "/m1", "/m2", "/m3"), visited.distinct())
        }
    }

    private fun withServer(block: (MockWebServer, List<String>) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            block(server, List(5) { "$origin/m$it" })
        } finally {
            server.shutdown()
        }
    }

    /** No MAL id, so the match is decided on name score alone and no card is opened to check one. */
    private fun media() = Media(
        id = 154587,
        title = MediaTitle(
            english = "Frieren",
            romaji = "Sousou no Frieren",
            native = "葬送のフリーレン",
        ),
    )

    private fun searchPage(slug: String, title: String) = """
        <div class="aitem">
          <a class="poster" href="/watch/$slug"></a>
          <a class="title d-title" data-en="$title">$title</a>
        </div>
    """.trimIndent()

    private fun watchPage() = """
        <a data-num="1" data-sub="1"></a>
        <a data-num="2" data-sub="1"></a>
    """.trimIndent()
}
