package com.anilili.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class OpenSubtitlesClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun normalizesAtMostThreeUniqueLanguageCodes() {
        assertEquals("en,es,fr", normalizeSubtitleLanguages(" EN, es;en fr de "))
    }

    @Test
    fun parsesEveryDownloadableFileWithoutDependingOnUnknownFields() {
        val root = Json.parseToJsonElement(
            """
            {
              "data": [{
                "id": "123",
                "attributes": {
                  "language": "es",
                  "release": "Anime.S01E02.WEB",
                  "download_count": 42,
                  "hearing_impaired": true,
                  "from_trusted": true,
                  "ai_translated": false,
                  "machine_translated": false,
                  "unknown_future_field": "ignored",
                  "files": [
                    {"file_id": 9007199254740991, "file_name": "episode.es.srt"},
                    {"file_id": 44, "file_name": "episode.es.ass"}
                  ]
                }
              }]
            }
            """.trimIndent(),
        ).jsonObject

        val results = parseSearchResults(root)

        assertEquals(2, results.size)
        assertEquals(9_007_199_254_740_991L, results.first().fileId)
        assertEquals("es", results.first().language)
        assertEquals(42, results.first().downloads)
        assertTrue(results.first().hearingImpaired)
        assertTrue(results.first().trusted)
        assertFalse(results.first().aiTranslated)
    }

    @Test
    fun loginTokenIsUsedForMetadataSearch() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"free-token"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        val client = client()

        client.login("viewer", "secret")
        client.search(
            query = "Frieren: Beyond Journey's End",
            languages = "EN, es",
            seasonNumber = 1,
            episodeNumber = 7,
        )

        val login = server.takeRequest()
        assertEquals("/api/v1/login", login.path)
        assertEquals("test-key", login.getHeader("Api-Key"))
        assertEquals("Anilili test", login.getHeader("User-Agent"))
        assertTrue(login.body.readUtf8().contains("\"password\":\"secret\""))

        val search = server.takeRequest()
        assertEquals("Bearer free-token", search.getHeader("Authorization"))
        val url = search.requestUrl
        assertNotNull(url)
        assertEquals("Frieren: Beyond Journey's End", url?.queryParameter("query"))
        assertEquals("en,es", url?.queryParameter("languages"))
        assertEquals("1", url?.queryParameter("season_number"))
        assertEquals("7", url?.queryParameter("episode_number"))
        assertEquals("download_count", url?.queryParameter("order_by"))
    }

    @Test
    fun downloadResponseReportsFreeQuota() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"link":"https://dl.opensubtitles.test/file.srt","file_name":"file.srt","remaining":19}""",
            ),
        )

        val download = client().requestDownload(1234L)

        assertEquals("https://dl.opensubtitles.test/file.srt", download.url)
        assertEquals("file.srt", download.fileName)
        assertEquals(19, download.remainingDownloads)
        val request = server.takeRequest()
        assertEquals("/api/v1/download", request.path)
        assertTrue(request.body.readUtf8().contains("\"file_id\":1234"))
    }

    @Test
    fun rejectedBearerTokenIsClearedSoPersistentAccountCanRenewIt() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"old-token"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"token expired"}"""))
        val client = client()
        client.login("viewer", "secret")

        val failure = runCatching {
            client.search(
                query = "Frieren",
                languages = "en",
                seasonNumber = null,
                episodeNumber = 1,
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(client.isLoggedIn)
    }

    private fun client(): OpenSubtitlesClient = OpenSubtitlesClient(
        client = okhttp3.OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        apiKey = "test-key",
        userAgent = "Anilili test",
        baseUrl = server.url("/api/v1").toString().trimEnd('/'),
    )
}
