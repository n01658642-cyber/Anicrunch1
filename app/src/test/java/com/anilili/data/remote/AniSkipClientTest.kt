package com.anilili.data.remote

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AniSkipClientTest {
    @Test
    fun requestsV2TypesAndMatchesTheClosestVideoDuration() {
        lateinit var requestedUrl: HttpUrl
        val client = client(
            body = """
                {
                  "found": true,
                  "results": [
                    {"skipType":"op","episodeLength":1200.0,"interval":{"startTime":10.0,"endTime":100.0}},
                    {"skipType":"op","episodeLength":1440.0,"interval":{"startTime":20.0,"endTime":110.0}},
                    {"skipType":"ed","episodeLength":1440.0,"interval":{"startTime":1300.0,"endTime":1390.0}}
                  ]
                }
            """.trimIndent(),
            onRequest = { requestedUrl = it },
        )

        val skip = AniSkipClient(client, Json).skipTimes(813, 56, 1438.0)

        assertEquals(
            listOf("op", "ed", "mixed-op", "mixed-ed", "recap"),
            requestedUrl.queryParameterValues("types[]"),
        )
        assertEquals("1438.0", requestedUrl.queryParameter("episodeLength"))
        assertEquals(20.0, skip?.introStart)
        assertEquals(110.0, skip?.introEnd)
        assertEquals(1300.0, skip?.outroStart)
        assertEquals(1390.0, skip?.outroEnd)
    }

    @Test
    fun mixedSegmentsFillMissingPlainOpeningAndInvalidIntervalsAreIgnored() {
        val client = client(
            body = """
                {
                  "found": true,
                  "results": [
                    {"skipType":"op","episodeLength":1440.0,"interval":{"startTime":90.0,"endTime":20.0}},
                    {"skipType":"mixed-op","episodeLength":1440.0,"interval":{"startTime":4.5,"endTime":94.5}}
                  ]
                }
            """.trimIndent(),
        )

        val skip = AniSkipClient(client, Json).skipTimes(1, 1, 1440.0)

        assertEquals(4.5, skip?.introStart)
        assertEquals(94.5, skip?.introEnd)
        assertEquals(null, skip?.outroStart)
    }

    @Test
    fun recapWindowsAndSegmentIdsSurviveTheParse() {
        val client = client(
            body = """
                {
                  "found": true,
                  "results": [
                    {"skipType":"recap","skipId":"r-1","episodeLength":1440.0,
                     "interval":{"startTime":0.0,"endTime":62.0}},
                    {"skipType":"op","skipId":"o-1","episodeLength":1440.0,
                     "interval":{"startTime":62.0,"endTime":152.0}}
                  ]
                }
            """.trimIndent(),
        )

        val skip = AniSkipClient(client, Json).skipTimes(20, 1, 1440.0)

        assertEquals(0.0, skip?.recapStart)
        assertEquals(62.0, skip?.recapEnd)
        assertEquals("r-1", skip?.recapSkipId)
        assertEquals("o-1", skip?.introSkipId)
        // Nothing to vote on for a window the response never carried.
        assertEquals(null, skip?.outroSkipId)
    }

    @Test
    fun anEpisodeWithOnlyARecapIsStillWorthReturning() {
        val client = client(
            body = """
                {"found": true, "results": [
                  {"skipType":"recap","skipId":"r-2","episodeLength":1440.0,
                   "interval":{"startTime":5.0,"endTime":70.0}}
                ]}
            """.trimIndent(),
        )

        val skip = AniSkipClient(client, Json).skipTimes(20, 2, 1440.0)

        assertEquals(5.0, skip?.recapStart)
        assertEquals(null, skip?.introStart)
    }

    @Test
    fun relationRulesAreReadAndAnAbsentTableIsEmptyRatherThanAFailure() {
        val withRules = client(
            body = """
                {"statusCode":200,"message":"ok","found":true,"rules":[
                  {"from":{"start":50,"end":59},"to":{"malId":38524,"start":1,"end":10}},
                  {"from":{"start":76},"to":{"malId":48583,"start":1}}
                ]}
            """.trimIndent(),
        )

        val rules = AniSkipClient(withRules, Json).relationRules(16498)

        assertEquals(2, rules.size)
        assertEquals(AniSkipRelationRule(50, 59, 38524, 1, 10), rules[0])
        // An open-ended rule keeps its nulls rather than inventing a bound.
        assertEquals(AniSkipRelationRule(76, null, 48583, 1, null), rules[1])

        val noRules = client(body = """{"statusCode":404,"message":"not found"}""", code = 404)
        assertEquals(emptyList<AniSkipRelationRule>(), AniSkipClient(noRules, Json).relationRules(21))
    }

    @Test
    fun aDownvoteIsPostedAgainstTheSegmentId() {
        lateinit var requestedUrl: HttpUrl
        var body = ""
        var method = ""
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedUrl = request.url
                method = request.method
                body = okio.Buffer().also { request.body?.writeTo(it) }.readUtf8()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body("""{"statusCode":201,"message":"ok"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        AniSkipClient(client, Json).vote("abc-123", upvote = false)

        assertEquals("POST", method)
        assertEquals("/v2/skip-times/vote/abc-123", requestedUrl.encodedPath)
        assertEquals("""{"voteType":"downvote"}""", body)
    }

    @Test
    fun serverErrorsRemainObservableToTheCaller() {
        val client = client(body = "{\"statusCode\":500,\"message\":\"Internal server error\"}", code = 500)

        val error = assertThrows(IOException::class.java) {
            AniSkipClient(client, Json).skipTimes(813, 56, 1440.0)
        }

        assertEquals("AniSkip HTTP 500", error.message)
    }

    private fun client(
        body: String,
        code: Int = 200,
        onRequest: (HttpUrl) -> Unit = {},
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            onRequest(request.url)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Error")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()
}
