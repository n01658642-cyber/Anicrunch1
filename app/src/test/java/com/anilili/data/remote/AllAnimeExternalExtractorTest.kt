package com.anilili.data.remote

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AllAnimeExternalExtractorTest {
    @Test
    fun resolvesDoodPassMd5HandshakeWithPlaybackHeaders() {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            server.enqueue(
                MockResponse().setBody(
                    "<html><title>Episode 1 1080p</title><script>var path='/pass_md5/key/token';</script></html>",
                ),
            )
            server.enqueue(MockResponse().setBody("$origin/media/"))
            val extractor = extractor(server, nowMs = { 1234L }, randomToken = { "RANDOM" })

            val stream = extractor.resolve("$origin/dood/e/test", "Dood", "sub").single()

            assertEquals("$origin/media/RANDOM?token=token&expiry=1234", stream.url)
            assertEquals("mp4", stream.type)
            assertEquals("$origin/", stream.referer)
            assertEquals("$origin/", stream.headers["Referer"])
            val pageRequest = server.takeRequest()
            val passRequest = server.takeRequest()
            assertEquals("/dood/e/test", pageRequest.path)
            assertEquals("/pass_md5/key/token", passRequest.path)
            assertEquals("$origin/dood/e/test", passRequest.getHeader("Referer"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resolvesOkruProgressiveQualities() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """<div data-options='{&quot;videos&quot;:[{&quot;name&quot;:&quot;full&quot;,&quot;url&quot;:&quot;https:\/\/cdn.test\/1080.mp4&quot;}]}'>""",
                ),
            )
            val extractor = extractor(server)

            val stream = extractor.resolve(server.url("/ok.ru/video/1").toString(), "Ok", "sub").single()

            assertEquals("https://cdn.test/1080.mp4", stream.url)
            assertEquals("mp4", stream.type)
            assertTrue(stream.quality.orEmpty().contains("1080p"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resolvesUnpackedMp4UploadPlayerSource() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setBody(
                    """<script>player.src({src:"https://cdn.test/video.mp4",type:"video/mp4"});HEIGHT=1080;</script>""",
                ),
            )
            val extractor = extractor(server)

            val stream = extractor.resolve(server.url("/mp4upload.com/embed-test.html").toString(), "Mp4", "dub").single()

            assertEquals("https://cdn.test/video.mp4", stream.url)
            assertEquals("dub", stream.audio)
            assertTrue(stream.quality.orEmpty().contains("1080p"))
            assertEquals("https://mp4upload.com/", server.takeRequest().getHeader("Referer"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun resolvesStreamlareHlsApiResponse() {
        val server = MockWebServer()
        server.start()
        try {
            val origin = server.url("/").toString().removeSuffix("/")
            server.enqueue(
                MockResponse().setBody(
                    """{"type":"hls","file":"https:\/\/cdn.test\/master.m3u8"}""",
                ),
            )
            val extractor = extractor(server)

            val stream = extractor.resolve("https://streamlare.com/e/abc", "Streamlare", "sub").single()

            assertEquals("https://cdn.test/master.m3u8", stream.url)
            assertEquals("hls", stream.type)
            val request = server.takeRequest()
            assertEquals("/api/video/stream/get", request.path)
            assertEquals("POST", request.method)
            assertEquals("{\"id\":\"abc\"}", request.body.readUtf8())
            assertTrue(origin.isNotBlank())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unpacksDeanEdwardsPackedPlayerScript() {
        val packed =
            """eval(function(p,a,c,k,e,d){return p;}('0(\"1\");',2,2,'alert|ready'.split('|'),0,{}))"""

        assertEquals("alert(\"ready\");", AllAnimeJsUnpacker.unpackAndCombine(packed))
    }

    private fun extractor(
        server: MockWebServer,
        nowMs: () -> Long = { 1L },
        randomToken: () -> String = { "TOKEN" },
    ) = AllAnimeExternalExtractor(
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        streamlareApiOrigin = server.url("/").toString().removeSuffix("/"),
        nowMs = nowMs,
        randomToken = randomToken,
    )
}
