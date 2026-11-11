package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToonHubProviderTest {
    @Test
    fun `sources unwrap real players and retain a failed wrapper as fallback`() {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/wp-json/wp/v2/search" -> json(
                        """
                            [{"title":"That Time I Got Reincarnated as a Slime Season 1 Hindi",
                              "url":"$origin/slime-season-1","subtype":"post"}]
                        """.trimIndent(),
                    )
                    "/slime-season-1" -> html(
                        """
                            <p>Episode 01</p>
                            <p>Watch – <a href="https://toon-stream.site/episode/slime-1x1/">Stream</a></p>
                        """.trimIndent(),
                    )
                    "/episode/slime-1x1/" -> html(
                        """
                            <iframe src="/embed/short-player"></iframe>
                            <iframe src="/embed/ruby-player"></iframe>
                            <span class="server">Short</span>
                            <span class="server">Ruby</span>
                        """.trimIndent(),
                    )
                    "/embed/short-player" -> html(
                        """
                            <div class="fake-player-overlay"><div class="play-button"></div></div>
                            <iframe src="https://abyssplayer.com/video-1"></iframe>
                        """.trimIndent(),
                    )
                    "/embed/ruby-player" -> MockResponse().setResponseCode(503)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val rewriteTarget = server.url("/")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val rewrittenUrl = if (request.url.host == "toon-stream.site") {
                    request.url.newBuilder()
                        .scheme(rewriteTarget.scheme)
                        .host(rewriteTarget.host)
                        .port(rewriteTarget.port)
                        .build()
                } else {
                    request.url
                }
                chain.proceed(request.newBuilder().url(rewrittenUrl).build())
            }.build()
            val provider = ToonHubProvider(client, origin)

            val result = provider.sources(
                Media(
                    id = 101280,
                    episodes = 24,
                    title = MediaTitle(english = "That Time I Got Reincarnated as a Slime"),
                ),
                audio = "dub",
                episode = 1,
            )

            assertEquals(
                listOf(
                    "https://abyssplayer.com/video-1",
                    "$origin/embed/ruby-player",
                ),
                result.streams.map { it.url },
            )
            assertEquals(
                listOf(
                    "$origin/embed/short-player",
                    "$origin/episode/slime-1x1/",
                ),
                result.streams.map { it.referer },
            )
            assertEquals(listOf("Short", "Ruby"), result.streams.map { it.quality })
            assertTrue(result.streams.first().isActive)

            val requests = (1..5).mapNotNull { server.takeRequest(1, TimeUnit.SECONDS) }
            val shortRequest = requests.first { it.requestUrl?.encodedPath == "/embed/short-player" }
            assertEquals("$origin/episode/slime-1x1/", shortRequest.getHeader("Referer"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a watch page that answers an interstitial falls back to the post's own mirrors`() {
        // toonstream.net currently serves "Checking your browser..." to anything without a real
        // browser. That used to raise "no ToonStream player mirrors" and lose the episode, even
        // though the post carries mirrors that play.
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/wp-json/wp/v2/search" -> json(
                        """[{"title":"Slime Season 1 Hindi","url":"$origin/slime-season-1","subtype":"post"}]""",
                    )
                    "/slime-season-1" -> html(
                        """
                            <p>Episode 01</p>
                            <p>Watch – <a href="https://toon-stream.site/episode/slime-1x1/">Stream</a></p>
                            <p>1080p – <a href="https://filesforever.link/file/abc123">Mirrors</a></p>
                            <p>720p – <a href="https://gdmirrorbot.nl/file/def456">Mirrors</a></p>
                        """.trimIndent(),
                    )
                    "/episode/slime-1x1/" -> html("<title>Checking your browser...</title>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val rewriteTarget = server.url("/")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val rewritten = if (request.url.host == "toon-stream.site") {
                    request.url.newBuilder()
                        .scheme(rewriteTarget.scheme)
                        .host(rewriteTarget.host)
                        .port(rewriteTarget.port)
                        .build()
                } else {
                    request.url
                }
                chain.proceed(request.newBuilder().url(rewritten).build())
            }.build()

            val result = ToonHubProvider(client, origin).sources(
                Media(id = 101280, episodes = 24, title = MediaTitle(english = "Slime")),
                audio = "dub",
                episode = 1,
            )

            // GDMirror is still withheld: the browser blocks its advertising redirect and is then
            // left with nothing to play.
            assertEquals(listOf("https://filesforever.link/embed/abc123"), result.streams.map { it.url })
            assertEquals(listOf("1080p"), result.streams.map { it.quality })
            assertTrue(result.streams.single().isActive)
        } finally {
            server.shutdown()
        }
    }

    private fun html(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    private fun json(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)
}
