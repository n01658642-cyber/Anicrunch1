package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The season half of the matcher, which the parser tests cannot reach: both behaviours here are
 * decided between the search and the probe, from the AniList name rather than the page.
 */
class AnimeHeavenProviderTest {
    /**
     * Measured against the live site 2026-09-14: all three AniList names for this entry carry
     * "Season 2"/"II", AnimeHeaven's search is literal, and every one of them returned nothing —
     * so the provider recorded a missing title and dropped out for six hours, while the site had
     * the show as "Saga of Tanya the Evil II" the whole time.
     */
    @Test
    fun `a sequel is found by the season-stripped name`() {
        val server = MockWebServer()
        server.start()
        try {
            val queries = mutableListOf<String>()
            server.dispatcher = dispatcher(queries)
            val provider = AnimeHeavenProvider(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

            val availability = provider.episodeAvailability(tanyaSeasonTwo())

            assertEquals((1..10).toSet(), availability.sub)
            assertEquals(
                "the stripped name has to lead, or the literal search answers nothing",
                "Saga of Tanya the Evil",
                queries.first(),
            )
        } finally {
            server.shutdown()
        }
    }

    /**
     * The reason the stripped query cannot ship on its own.
     *
     * Once "Saga of Tanya the Evil" is the query, the site returns season one *and* season two, and
     * on title score alone season one wins — 0.764 against 0.727, because the season marker is two
     * characters against a shared name. Without the season ordering this test would pass the search
     * and then play the wrong show under season two's episode numbers, which is worse than the miss
     * it replaced.
     */
    @Test
    fun `the season asked for outranks the better-scoring base series`() {
        val server = MockWebServer()
        server.start()
        try {
            val probed = mutableListOf<String>()
            server.dispatcher = dispatcher(mutableListOf(), probed)
            val provider = AnimeHeavenProvider(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

            val availability = provider.episodeAvailability(tanyaSeasonTwo())

            assertEquals("season two has ten episodes, season one twelve", (1..10).toSet(), availability.sub)
            assertEquals(listOf("anime.php?tanya2"), probed)
        } finally {
            server.shutdown()
        }
    }

    /** A first-season entry must not be dragged onto the sequel post by the same ordering. */
    @Test
    fun `an entry with no season marker still takes the unnumbered post`() {
        val server = MockWebServer()
        server.start()
        try {
            val probed = mutableListOf<String>()
            server.dispatcher = dispatcher(mutableListOf(), probed)
            val provider = AnimeHeavenProvider(OkHttpClient(), server.url("/").toString().removeSuffix("/"))

            val availability = provider.episodeAvailability(
                Media(id = 32615, episodes = 12, title = MediaTitle(english = "Saga of Tanya the Evil")),
            )

            assertEquals((1..12).toSet(), availability.sub)
            assertEquals(listOf("anime.php?tanya1"), probed)
        } finally {
            server.shutdown()
        }
    }

    private fun tanyaSeasonTwo() = Media(
        id = 135865,
        episodes = 10,
        title = MediaTitle(english = "Saga of Tanya the Evil Season 2", romaji = "Youjo Senki II"),
    )

    /**
     * Answers the stripped query only, the way the live site does: the search is literal, so a
     * query still carrying "Season 2" finds nothing and the provider has to try the next name.
     */
    private fun dispatcher(
        queries: MutableList<String>,
        probed: MutableList<String> = mutableListOf(),
    ) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return MockResponse().setResponseCode(404)
            return when (url.encodedPath) {
                "/search.php" -> {
                    val query = url.queryParameter("s").orEmpty()
                    queries += query
                    if (query.contains("season", ignoreCase = true) || query.endsWith("II")) {
                        html("<div class='info3 bc1'></div>")
                    } else {
                        html(searchPage)
                    }
                }
                "/anime.php" -> {
                    probed += "anime.php?${url.query}"
                    html(if (url.query == "tanya2") episodePage(10) else episodePage(12))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val searchPage = """
        <div class='info3 bc1'>
        <div class='similarimg'><div class='p1'>
        <a href='anime.php?tanya1'><img class='coverimg' src='x' alt='Saga of Tanya the Evil'></a>
        <div class='similarname c'><a href='anime.php?tanya1' class='c'>Saga of Tanya the Evil</a></div>
        </div></div>
        <div class='similarimg'><div class='p1'>
        <a href='anime.php?tanya2'><img class='coverimg' src='y' alt='Saga of Tanya the Evil II'></a>
        <div class='similarname c'><a href='anime.php?tanya2' class='c'>Saga of Tanya the Evil II</a></div>
        </div></div>
        </div>
    """.trimIndent()

    private fun episodePage(count: Int): String = (1..count).joinToString("\n") { episode ->
        """<a onclick='gatea("${"%04x".format(episode)}ab")'><div class='watch2 bc2'>$episode</div></a>"""
    }

    private fun html(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)
}
