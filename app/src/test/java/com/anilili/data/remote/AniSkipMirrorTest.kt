package com.anilili.data.remote

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AniSkipMirrorTest {
    private val client = AniSkipClient(OkHttpClient(), Json)

    /** The mirror keeps every candidate so the one matching the stream's length can still win. */
    @Test
    fun `the candidate closest to the playing stream's length is chosen`() {
        val candidates = listOf(
            AniSkipClient.AniSkipSegment("op", 10.0, 100.0, 1420.0, "a"),
            AniSkipClient.AniSkipSegment("op", 30.0, 120.0, 1500.0, "b"),
            AniSkipClient.AniSkipSegment("ed", 1300.0, 1390.0, 1420.0, "c"),
        )
        val picked = client.skipTimesFrom(candidates, episodeLengthSeconds = 1498.0)
        assertEquals(30.0, picked?.introStart)
        assertEquals("b", picked?.introSkipId)
        assertEquals(1300.0, picked?.outroStart)
    }

    @Test
    fun `a mirrored file decodes in the shape the crawler writes`() {
        val raw = """{"v":1,"mal":21,"checked":2,"episodes":{"1":[["op",13.9,123.8,1430.7,"x"]]}}"""
        val file = Json { ignoreUnknownKeys = true }.decodeFromString(KonohaSkipFile.serializer(), raw)
        assertEquals(21, file.mal)
        assertEquals(1, file.episodes["1"]?.size)
    }
}
