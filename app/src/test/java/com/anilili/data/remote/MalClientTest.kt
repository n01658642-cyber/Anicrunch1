package com.anilili.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MalClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MalClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = MalClient(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            apiBaseUrl = server.url("/v2").toString(),
            accessTokenProvider = { "test-token" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `progress patch is authenticated and server result is returned`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"watching","num_episodes_watched":1,"is_rewatching":false}""",
                ),
        )

        val confirmed = client.updateListStatus(
            malId = 62331,
            progress = 1,
            status = "watching",
        )

        assertEquals(1, confirmed.numEpisodesWatched)
        assertEquals("watching", confirmed.status)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v2/anime/62331/my_list_status", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val form = request.body.readUtf8()
        assertTrue(form.contains("num_watched_episodes=1"))
        assertTrue(form.contains("status=watching"))
    }

    @Test
    fun `a score is sent on its own and checked against what MAL kept`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","score":8,"num_episodes_watched":12}"""),
        )

        val confirmed = client.updateListStatus(malId = 62331, score = 8)

        assertEquals(8, confirmed.score)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v2/anime/62331/my_list_status", request.path)
        // Only the score: a rating must never move the entry's status or episode count.
        assertEquals("score=8", request.body.readUtf8())
    }

    @Test
    fun `a score MAL did not keep is a failure, not a success`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","score":0,"num_episodes_watched":12}"""),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { client.updateListStatus(malId = 62331, score = 8) }
        }

        assertTrue(error.message.orEmpty().contains("confirmed score 0"))
    }

    @Test
    fun `a hand-set lower count is sent alone, leaving the status untouched`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","num_episodes_watched":3,"is_rewatching":false}"""),
        )

        val confirmed = client.updateListStatus(malId = 62331, progress = 3)

        assertEquals(3, confirmed.numEpisodesWatched)
        val form = server.takeRequest().body.readUtf8()
        assertEquals("num_watched_episodes=3", form)
    }

    @Test
    fun `progress patch fails when MAL confirms a different episode`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"watching","num_episodes_watched":0,"is_rewatching":false}""",
                ),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                client.updateListStatus(malId = 62331, progress = 1, status = "watching")
            }
        }

        assertTrue(error.message.orEmpty().contains("confirmed 0 watched episodes"))
    }

    @Test
    fun `finishing a rewatch clears the flag and sends the new count, checked against what MAL kept`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"completed","num_episodes_watched":12,"is_rewatching":false,"num_times_rewatched":2}""",
                ),
        )

        val confirmed = client.updateListStatus(
            malId = 62331,
            progress = 12,
            status = "completed",
            isRewatching = false,
            numTimesRewatched = 2,
        )

        assertEquals(2, confirmed.numTimesRewatched)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("num_times_rewatched=2"))
        assertTrue(form.contains("is_rewatching=false"))
        // No start or finish date goes out: a rewatch belongs to the original watch period.
        assertFalse(form.contains("start_date"))
        assertFalse(form.contains("finish_date"))
    }

    @Test
    fun `a rewatch count MAL did not keep is a failure`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","num_episodes_watched":12,"num_times_rewatched":1}"""),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { client.updateListStatus(malId = 62331, status = "completed", numTimesRewatched = 2) }
        }

        assertTrue(error.message.orEmpty().contains("num_times_rewatched=1"))
    }

    @Test
    fun `a rewatch episode count MAL holds at the full count is not a failure`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"completed","num_episodes_watched":12,"is_rewatching":true}"""),
        )

        val confirmed = client.updateListStatus(malId = 62331, progress = 3, strictProgress = false)

        assertEquals(12, confirmed.numEpisodesWatched)
        assertEquals("num_watched_episodes=3", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a rewatch is completed and flagged on MAL, never watching`() {
        assertEquals("completed", MalClient.malStatus("REPEATING"))
        assertEquals(
            "REPEATING",
            MalClient.anilistStatus(MalListStatus(status = "completed", isRewatching = true)),
        )
        // What builds before this one wrote reads back as a rewatch too, so finishing it repairs it.
        assertEquals(
            "REPEATING",
            MalClient.anilistStatus(MalListStatus(status = "watching", isRewatching = true)),
        )
    }

    @Test
    fun `on hold list selection is mapped and confirmed`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"status":"on_hold","num_episodes_watched":1,"is_rewatching":false}""",
                ),
        )

        val confirmed = client.updateListStatus(
            malId = 62331,
            status = MalClient.malStatus("PAUSED"),
            isRewatching = false,
        )

        assertEquals("on_hold", confirmed.status)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("status=on_hold"))
        assertTrue(form.contains("is_rewatching=false"))
    }

    @Test
    fun `anime status reads the viewer entry and MAL episode count from one response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"num_episodes":12,"my_list_status":{"status":"watching",""" +
                        """"num_episodes_watched":3,"is_rewatching":false}}""",
                ),
        )

        val status = client.animeStatus(62331)

        assertEquals(3, status.listStatus?.numEpisodesWatched)
        assertEquals(12, status.totalEpisodes)
        val request = server.takeRequest()
        // Tags are not a default field, so every list-status field is named for them to arrive.
        assertEquals(
            "/v2/anime/62331?fields=my_list_status{status,score,num_episodes_watched,is_rewatching,num_times_rewatched,updated_at,tags},num_episodes",
            request.path,
        )
    }

    @Test
    fun `a tag write sends the whole tag set as one comma-separated field`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"plan_to_watch","tags":["Faves","Private"]}"""),
        )

        val confirmed = client.updateListStatus(62331, status = "plan_to_watch", tags = listOf("Faves", "Private"))

        assertEquals(listOf("Faves", "Private"), confirmed.tags)
        val form = server.takeRequest().body.readUtf8()
        assertTrue(form, form.contains("tags=Faves%2CPrivate"))
        assertTrue(form.contains("status=plan_to_watch"))
    }

    @Test
    fun `anime status treats an airing series zero episode count as unknown`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"num_episodes":0,"my_list_status":{"status":"watching",""" +
                        """"num_episodes_watched":3,"is_rewatching":false}}""",
                ),
        )

        assertEquals(null, client.animeStatus(62331).totalEpisodes)
    }

    @Test
    fun `MAL timestamps parse with a numeric offset or Z and junk falls back to zero`() {
        // MAL stamps updated_at as 2018-04-25T22:23:11+00:00; Instant.parse refuses the offset on
        // the desugared runtime below API 26, which used to sink every MAL row to updatedAt = 0.
        assertEquals(1524694991L, malEpochSeconds("2018-04-25T22:23:11+00:00"))
        assertEquals(1524694991L, malEpochSeconds("2018-04-25T22:23:11Z"))
        assertEquals(1524694991L, malEpochSeconds("2018-04-25T16:23:11-06:00"))
        assertEquals(0L, malEpochSeconds(null))
        assertEquals(0L, malEpochSeconds(""))
        assertEquals(0L, malEpochSeconds("not a timestamp"))
    }

    @Test
    fun `MAL no-op reasons distinguish completed and already synced entries`() {
        assertEquals(
            MalProgressSyncSkipReason.REMOTE_COMPLETED,
            malProgressSkipReason("COMPLETED", currentProgress = 12, targetProgress = 13),
        )
        assertEquals(
            MalProgressSyncSkipReason.ALREADY_AT_OR_AHEAD,
            malProgressSkipReason("CURRENT", currentProgress = 1, targetProgress = 1),
        )
        assertEquals(
            MalProgressSyncSkipReason.NO_CHANGE,
            malProgressSkipReason("CURRENT", currentProgress = 0, targetProgress = 1),
        )
    }
}
