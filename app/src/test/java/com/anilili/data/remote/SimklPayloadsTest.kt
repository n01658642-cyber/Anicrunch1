package com.anilili.data.remote

import com.anilili.data.auth.isSimklRedirect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklPayloadsTest {
    private fun parse(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `ids carry the AniList id and a known MAL id`() {
        assertEquals(parse("""{"anilist":21,"mal":21}"""), SimklPayloads.ids(21, 21))
    }

    @Test
    fun `ids leave out a missing or placeholder MAL id`() {
        assertEquals(parse("""{"anilist":185736}"""), SimklPayloads.ids(185736, null))
        assertEquals(parse("""{"anilist":185736}"""), SimklPayloads.ids(185736, 0))
    }

    @Test
    fun `history marks every episode up to the progress, flat and seasonless`() {
        val body = SimklPayloads.history(SimklPayloads.ids(20958, 25777), 3)
        assertEquals(
            parse(
                """{"anime":[{"ids":{"anilist":20958,"mal":25777},""" +
                    """"episodes":[{"number":1},{"number":2},{"number":3}]}]}""",
            ),
            body,
        )
    }

    @Test
    fun `history is capped so a corrupt progress cannot become a huge request`() {
        val body = SimklPayloads.history(SimklPayloads.ids(1, null), 1_000_000).toString()
        assertTrue(body.contains("""{"number":${SimklPayloads.MAX_HISTORY_EPISODES}}"""))
        assertFalse(body.contains("""{"number":${SimklPayloads.MAX_HISTORY_EPISODES + 1}}"""))
    }

    @Test
    fun `completed marks the whole series without listing episodes`() {
        assertEquals(
            parse("""{"anime":[{"ids":{"anilist":1},"status":"completed"}]}"""),
            SimklPayloads.completed(SimklPayloads.ids(1, null)),
        )
    }

    @Test
    fun `a list move carries its destination per item`() {
        assertEquals(
            parse("""{"anime":[{"ids":{"anilist":1},"to":"hold"}]}"""),
            SimklPayloads.addToList(SimklPayloads.ids(1, null), "hold"),
        )
    }

    @Test
    fun `AniList statuses map onto Simkl watchlists`() {
        assertEquals("watching", simklListStatus("CURRENT"))
        assertEquals("watching", simklListStatus("REPEATING"))
        assertEquals("plantowatch", simklListStatus("PLANNING"))
        assertEquals("hold", simklListStatus("PAUSED"))
        assertEquals("dropped", simklListStatus("DROPPED"))
        // Not a list move: completing marks the episodes too, through history.
        assertNull(simklListStatus("COMPLETED"))
    }

    @Test
    fun `not_found is read from any of its arrays`() {
        assertFalse(SimklPayloads.notFound(parse("""{"added":{"shows":1},"not_found":{"movies":[],"shows":[]}}""")))
        assertTrue(SimklPayloads.notFound(parse("""{"added":{"shows":0},"not_found":{"movies":[],"shows":[{"ids":{"anilist":1}}]}}""")))
        assertTrue(SimklPayloads.notFound(parse("""{"not_found":{"anime":[{"ids":{"anilist":1}}]}}""")))
        assertFalse(SimklPayloads.notFound(parse("""{"added":{"shows":1}}""")))
    }

    @Test
    fun `a save only adds a title Simkl does not track yet`() {
        assertEquals(SimklSavedAction.ADD_PLANNED, simklSavedAction(saved = true, list = null, watchedBefore = false))
        assertEquals(SimklSavedAction.NONE, simklSavedAction(saved = true, list = "watching", watchedBefore = true))
        assertEquals(SimklSavedAction.NONE, simklSavedAction(saved = true, list = "plantowatch", watchedBefore = false))
        // Watched once and taken off every list since: saving must not erase that by re-planning it.
        assertEquals(SimklSavedAction.NONE, simklSavedAction(saved = true, list = null, watchedBefore = true))
    }

    @Test
    fun `an unsave only removes a title that is still just a plan`() {
        assertEquals(SimklSavedAction.REMOVE, simklSavedAction(saved = false, list = "plantowatch", watchedBefore = false))
        assertEquals(SimklSavedAction.NONE, simklSavedAction(saved = false, list = "plantowatch", watchedBefore = true))
        assertEquals(SimklSavedAction.NONE, simklSavedAction(saved = false, list = "completed", watchedBefore = true))
        assertEquals(SimklSavedAction.NONE, simklSavedAction(saved = false, list = null, watchedBefore = false))
    }

    @Test
    fun `the sign-in callback is recognised with or without its answer`() {
        assertTrue(isSimklRedirect("anilili://auth/simkl?code=abc&state=xyz&iss=https%3A%2F%2Fsimkl.com"))
        assertTrue(isSimklRedirect("anilili://auth/simkl"))
        assertTrue(isSimklRedirect("ANILILI://auth/simkl/?error=access_denied"))
        assertFalse(isSimklRedirect("anilili://anime/21"))
        assertFalse(isSimklRedirect("anilili://auth/simklx?code=1"))
        assertFalse(isSimklRedirect("https://simkl.com/oauth2/authorize?redirect_uri=anilili://auth/simkl"))
    }
}
