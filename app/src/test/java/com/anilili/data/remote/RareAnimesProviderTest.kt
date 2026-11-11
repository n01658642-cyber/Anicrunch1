package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RareAnimesProviderTest {
    @Test
    fun `catalog fetches requested season instead of newest wordpress result`() {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            server.enqueue(MockResponse().setBody(searchPage(origin)))
            server.enqueue(MockResponse().setBody(episodePage(24)))
            val provider = RareAnimesProvider(OkHttpClient(), origin)

            val availability = provider.episodeAvailability(
                Media(
                    id = 101280,
                    episodes = 24,
                    title = MediaTitle(english = "That Time I Got Reincarnated as a Slime"),
                ),
            )

            assertEquals((1..24).toSet(), availability.dub)
            assertEquals(2, server.requestCount)
            server.takeRequest() // WordPress search.
            assertEquals("/season-1", server.takeRequest().requestUrl?.encodedPath)
        } finally {
            server.shutdown()
        }
    }

    /**
     * The regression this file existed without: a pass that never got an answer must not be
     * remembered as "this site has no such title".
     *
     * The provider keeps a six-hour miss cache, and a catalogue pass that runs out of budget
     * interrupts the worker mid-request. If that lands in the miss cache, one slow moment takes
     * RareAnimes out of *every* title until the process restarts — which is what a tester means by
     * "the server isn't there any more". The second lookup below is the whole assertion: it has to
     * reach the network again rather than answer from the miss it just recorded.
     */
    @Test
    fun `a transport failure is not remembered as a missing title`() {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            var refusing = true
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    // The real failure this guards against is a socket that never answers, because
                    // that is what a catalogue pass running out of budget looks like from here.
                    refusing -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    request.requestUrl?.encodedPath?.startsWith("/search/") == true ->
                        MockResponse().setBody(searchPage(origin))
                    else -> MockResponse().setBody(episodePage(24))
                }
            }
            val client = OkHttpClient.Builder()
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            val provider = RareAnimesProvider(client, origin)
            val media = Media(
                id = 101280,
                episodes = 24,
                title = MediaTitle(english = "That Time I Got Reincarnated as a Slime"),
            )

            assertTrue(runCatching { provider.episodeAvailability(media) }.isFailure)

            refusing = false
            val requestsBefore = server.requestCount
            val availability = provider.episodeAvailability(media)

            assertEquals((1..24).toSet(), availability.dub)
            assertTrue(
                "the retry answered from the miss cache instead of asking the site",
                server.requestCount > requestsBefore,
            )
        } finally {
            server.shutdown()
        }
    }

    /** Newest first, the order the feed answers in — the order the ranker has to see past. */
    private fun searchPage(origin: String): String = listOf(4, 3, 2, 1).joinToString(
        separator = "\n",
        prefix = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>""",
        postfix = "</channel></rss>",
    ) { season ->
        """
            <item>
              <title>That Time I Got Reincarnated as a Slime Season $season Hindi Dubbed Episodes Download HD</title>
              <link>$origin/season-$season</link>
            </item>
        """.trimIndent()
    }

    private fun episodePage(count: Int): String = (1..count).joinToString("\n") { episode ->
        """
            <p>Episode $episode</p>
            <p>Hindi – <a href="https://codedew.com/zipper/?url=EP$episode">WatchMultiQuality</a></p>
        """.trimIndent()
    }
}
