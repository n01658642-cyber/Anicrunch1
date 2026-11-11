package com.anilili.data.remote

import com.anilili.data.model.Media
import com.anilili.data.model.MediaTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The provider end to end against a fake API in the live shapes: matching, layout and the links
 * path, which is where the token and its failure handling live.
 */
class KartoonsProviderTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The tokens each links request carried, in order. */
    private val linkTokens: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** What a links request answers, by token. Default: links for any token. */
    @Volatile private var linksAnswer: (token: String) -> MockResponse = { linksBody() }

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                return when {
                    path == "/tokens.json" -> MockResponse().setResponseCode(404)
                    path == "/api/shows" -> searchFor(url.queryParameter("search").orEmpty())
                    path == "/api/shows/naruto" -> jsonResponse(NARUTO_SHOW)
                    path == "/api/shows/jjk" -> jsonResponse(JJK_SHOW)
                    path.startsWith("/api/shows/naruto/season/") -> {
                        val season = path.substringAfter("/season/").substringBefore('/')
                        val range = mapOf("n1" to 1..52, "n2" to 53..104, "n3" to 105..158, "n4" to 159..220)[season]!!
                        jsonResponse(episodesBody(range, "naruto-"))
                    }
                    path == "/api/shows/jjk/season/j2/all-episodes" -> jsonResponse(episodesBody(25..47, "jjk-"))
                    path.startsWith("/api/cloudstream/episode/") -> {
                        val token = url.queryParameter("token").orEmpty()
                        linkTokens += token
                        linksAnswer(token)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun `naruto episode 105 resolves to season 3's first episode`() {
        val provider = provider()
        val naruto = media(20, "Naruto", episodes = 220, year = 2002)

        assertEquals((1..220).toSet(), provider.episodeAvailability(naruto).dub)
        val sources = provider.sources(naruto, "dub", 105)

        assertEquals("Odelu 1", sources.streams.first().quality)
        assertEquals("Hindi", sources.streams.first().audio)
        val linkPaths = server.requestPaths().filter { it.contains("/cloudstream/episode/") }
        assertTrue(linkPaths.single().startsWith("/api/cloudstream/episode/naruto-105/links"))
    }

    @Test
    fun `jjk season 2 publishes 1-23 though the site numbers it 25-47`() {
        val provider = provider()
        val jjk = media(145064, "JUJUTSU KAISEN Season 2", episodes = 23, year = 2023)

        assertEquals((1..23).toSet(), provider.episodeAvailability(jjk).dub)
        provider.sources(jjk, "dub", 1)
        assertTrue(server.requestPaths().any { it.startsWith("/api/cloudstream/episode/jjk-25/links") })
    }

    @Test
    fun `a refused token hands the request to another and keeps it out`() {
        linksAnswer = { token ->
            if (token == "dead") {
                jsonResponse("""{"success":false,"message":"Authentication required","auth_required":true}""", 401)
            } else {
                linksBody()
            }
        }
        val provider = provider(tokens = listOf("dead", "live"))
        val naruto = media(20, "Naruto", episodes = 220, year = 2002)

        provider.sources(naruto, "dub", 1)
        provider.sources(naruto, "dub", 2)

        assertEquals("the live token answers every request after any refusal", "live", linkTokens.last())
        assertTrue("the dead token is tried at most once", linkTokens.count { it == "dead" } <= 1)
    }

    @Test
    fun `a challenge page is not held against the token`() {
        var blocked = true
        linksAnswer = { if (blocked) MockResponse().setResponseCode(403).setBody("<!DOCTYPE html><title>Just a moment...</title>") else linksBody() }
        val provider = provider(tokens = listOf("only"))
        val naruto = media(20, "Naruto", episodes = 220, year = 2002)

        try {
            provider.sources(naruto, "dub", 1)
            fail("a challenge page has no links")
        } catch (e: KartoonsProvider.KartoonsHttpException) {
            assertEquals(403, e.code)
        }
        blocked = false
        // Were the HTML 403 counted as a refusal, the only token would now be cooling for ten
        // minutes and this would throw "no usable account token".
        provider.sources(naruto, "dub", 1)
        assertEquals(listOf("only", "only"), linkTokens.toList())
    }

    @Test
    fun `no failure message carries the token`() {
        linksAnswer = { MockResponse().setResponseCode(429).setBody("""{"message":"slow down"}""") }
        val provider = provider(tokens = listOf("SECRET-TOKEN-VALUE"))
        val naruto = media(20, "Naruto", episodes = 220, year = 2002)

        try {
            provider.sources(naruto, "dub", 1)
            fail("a rate limit has no links")
        } catch (e: Exception) {
            assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("SECRET"))
            assertFalse(e.message.orEmpty(), e.message.orEmpty().contains("token="))
        }
    }

    @Test
    fun `a slow mirror never holds up a pick`() {
        server.dispatcher.let { inner ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/tokens.json") {
                        MockResponse().setBody("""{"tokens":[{"t":"fromMirror"}]}""").setHeadersDelay(3, TimeUnit.SECONDS)
                    } else {
                        inner.dispatch(request)
                    }
            }
        }
        val pool = pool(listOf("baked"))
        val started = System.nanoTime()
        assertEquals("baked", pool.pick())
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("pick waited ${tookMs}ms on the mirror", tookMs < 1_000)
    }

    @Test
    fun `an exhausted pool asks the mirror in line once`() {
        server.dispatcher.let { inner ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/tokens.json") {
                        jsonResponse("""{"tokens":[{"t":"fromMirror"}]}""")
                    } else {
                        inner.dispatch(request)
                    }
            }
        }
        val pool = pool(listOf("baked"))
        pool.markFailed("baked")
        assertEquals("fromMirror", pool.pick())
    }

    private fun provider(tokens: List<String> = listOf("tok")) =
        KartoonsProvider(OkHttpClient(), json, pool(tokens), bases = listOf(server.url("/api").toString()))

    private fun pool(tokens: List<String>) =
        KartoonsTokenPool(OkHttpClient(), json, scope, tokens, mirrorUrl = server.url("/tokens.json").toString())

    private fun MockWebServer.requestPaths(): List<String> =
        (0 until requestCount).mapNotNull { takeRequest(1, TimeUnit.SECONDS)?.path }

    private fun media(id: Int, title: String, episodes: Int, year: Int) = Media(
        id = id,
        title = MediaTitle(english = title, romaji = title),
        format = "TV",
        status = "FINISHED",
        seasonYear = year,
        episodes = episodes,
    )

    private fun searchFor(query: String): MockResponse = when {
        query.contains("naruto", ignoreCase = true) -> jsonResponse(
            """{"success":true,"data":[
                {"_id":"naruto","title":"Naruto","category":"Anime"},
                {"_id":"shippuden","title":"Naruto Shippūden","category":"Anime"}]}""",
        )
        query.contains("jujutsu", ignoreCase = true) -> jsonResponse(
            """{"success":true,"data":[{"_id":"jjk","title":"JUJUTSU KAISEN","category":"Anime"}]}""",
        )
        else -> jsonResponse("""{"success":true,"data":[]}""")
    }

    private fun episodesBody(range: IntRange, prefix: String): String =
        range.joinToString(",", prefix = """{"success":true,"data":[""", postfix = "]}") {
            """{"_id":"$prefix$it","episodeNumber":$it}"""
        }

    private fun linksBody(): MockResponse = jsonResponse(
        """{"success":true,"data":{"links":[
            {"name":"Odelu 2","url":"https://proxy/2.m3u8"},
            {"name":"Odelu 1","url":"https://proxy/1.m3u8"}]}}""",
    )

    private fun jsonResponse(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        const val NARUTO_SHOW = """{"success":true,"data":{"_id":"naruto","title":"Naruto","startYear":2002,"seasons":[
            {"_id":"n1","seasonNumber":1,"title":"Season 1","releaseYear":2002,"showId":"naruto","episodeCount":52},
            {"_id":"n2","seasonNumber":2,"title":"Season 2","releaseYear":2003,"showId":"naruto","episodeCount":52},
            {"_id":"n3","seasonNumber":3,"title":"Season 3","releaseYear":2004,"showId":"naruto","episodeCount":54},
            {"_id":"n4","seasonNumber":4,"title":"Season 4","releaseYear":2005,"showId":"naruto","episodeCount":62}]}}"""

        const val JJK_SHOW = """{"success":true,"data":{"_id":"jjk","title":"JUJUTSU KAISEN","startYear":2020,"seasons":[
            {"_id":"j1","seasonNumber":1,"title":"Season 1","releaseYear":2020,"showId":"jjk","episodeCount":24},
            {"_id":"j2","seasonNumber":2,"title":"Season 2","releaseYear":2020,"showId":"jjk","episodeCount":23},
            {"_id":"j3","seasonNumber":3,"title":"Season 3","releaseYear":2026,"showId":"jjk","episodeCount":12}]}}"""
    }
}
