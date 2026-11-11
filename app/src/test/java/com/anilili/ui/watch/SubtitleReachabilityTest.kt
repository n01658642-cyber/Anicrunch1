package com.anilili.ui.watch

import com.anilili.data.model.SubtitleItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SubtitleReachabilityTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    /**
     * The whole rule. Anything else — a host that refuses HEAD, rate-limits it, or gates it on
     * headers this probe cannot reproduce — says nothing about whether the track plays, and taking
     * a working subtitle away to avoid a broken one is the worse mistake.
     */
    @Test
    fun `only a missing file counts`() {
        assertTrue(SubtitleReachability.isMissing(404))
        assertTrue(SubtitleReachability.isMissing(410))
        assertFalse(SubtitleReachability.isMissing(200))
        assertFalse(SubtitleReachability.isMissing(206))
        assertFalse(SubtitleReachability.isMissing(302))
        assertFalse(SubtitleReachability.isMissing(403))
        assertFalse(SubtitleReachability.isMissing(405))
        assertFalse(SubtitleReachability.isMissing(429))
        assertFalse(SubtitleReachability.isMissing(500))
        assertFalse(SubtitleReachability.isMissing(503))
    }

    /** A file already on this device, and one that carries its own bytes, have nothing to probe. */
    @Test
    fun `local and inline tracks are not probed`() {
        val tracks = listOf(
            track("file:///storage/emulated/0/Download/en.srt"),
            track("content://downloads/17"),
            track("data:text/vtt;base64,V0VCVlRU"),
            track("https://cdn.example/en.vtt"),
            track("https://cdn.example/en.vtt"),
        )

        assertEquals(
            listOf("https://cdn.example/en.vtt"),
            SubtitleReachability.probable(tracks).map(SubtitleItem::url),
        )
    }

    @Test
    fun `a track the host does not have is reported`() = runBlocking {
        // Answered by path, not in order: the tracks are probed at the same time.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setResponseCode(if (request.path == "/gone.vtt") 404 else 200)
        }
        val missingUrl = server.url("/gone.vtt").toString()
        val liveUrl = server.url("/en.vtt").toString()

        val missing = SubtitleReachability.missingTracks(
            client = OkHttpClient(),
            tracks = listOf(track(missingUrl), track(liveUrl)),
            streamReferer = "https://megaplay.buzz/",
        )

        assertEquals(setOf(missingUrl), missing)
    }

    /** The probe must send what the player will: the stream's referer, unless the track names one. */
    @Test
    fun `the stream referer is sent, and the track's own wins`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        val inherited = server.url("/a.vtt").toString()
        val own = server.url("/b.vtt").toString()

        SubtitleReachability.missingTracks(
            client = OkHttpClient(),
            tracks = listOf(
                track(inherited),
                track(own).copy(referer = "https://subs.example/player"),
            ),
            streamReferer = "https://megaplay.buzz/",
        )

        val sent = (1..2).associate { _ ->
            val request = server.takeRequest()
            request.path.orEmpty() to request.getHeader("Referer")
        }
        assertEquals("https://megaplay.buzz/", sent["/a.vtt"])
        assertEquals("https://subs.example/player", sent["/b.vtt"])
    }

    /**
     * A slow host must not hold the sweep open: the answer is only ever used to remove a track, so
     * giving up means keeping everything, which is the state the episode is already playing in.
     */
    @Test
    fun `an unanswered probe drops nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS))

        val missing = SubtitleReachability.missingTracks(
            client = OkHttpClient(),
            tracks = listOf(track(server.url("/slow.vtt").toString())),
            streamReferer = null,
            budgetMs = 250L,
        )

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `nothing to probe costs no requests`() = runBlocking {
        val missing = SubtitleReachability.missingTracks(
            client = OkHttpClient(),
            tracks = listOf(track("file:///tmp/en.srt")),
            streamReferer = null,
        )

        assertTrue(missing.isEmpty())
        assertEquals(0, server.requestCount)
    }

    private fun track(url: String) = SubtitleItem(url = url, label = "English", language = "en")
}
