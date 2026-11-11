package com.anilili.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniPmParserTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `availability reads each track separately from the package inventory`() {
        // Shape of /_packages for Otome Game S2 on 2026-09-23: dub stops at episode 10.
        val inventory = obj(
            """{"anilistId":159309,"configured":true,"episodes":{
              "1":{"key":"a159309e1","sub":true,"dub":true},
              "11":{"key":"a159309e11","sub":true,"dub":false},
              "12":{"key":"a159309e12","sub":true},
              "0":{"key":"x","sub":true,"dub":true},
              "bad":{"sub":true}}}""",
        )
        val availability = AniPmParser.availability(inventory)
        assertEquals(setOf(1, 11, 12), availability.sub)
        assertEquals(setOf(1), availability.dub)
    }

    @Test
    fun `an unknown title is an empty inventory, not an error`() {
        val availability = AniPmParser.availability(obj("""{"anilistId":1,"configured":true,"episodes":{}}"""))
        assertEquals(emptySet<Int>(), availability.sub)
        assertEquals(emptySet<Int>(), availability.dub)
    }

    @Test
    fun `session maps to a header-free stream with normalised subtitle languages`() {
        val session = obj(
            """{"source":"https://media.settlar.io/v1/object/v2.a.b.c","kind":"hls","audioLang":"ja",
              "subtitles":[
                {"url":"https://media.settlar.io/v1/object/v2.th","label":"Thai","srclang":"tha","default":false},
                {"url":"https://media.settlar.io/v1/object/v2.en","label":"English","srclang":"en","default":true},
                {"url":"https://media.settlar.io/v1/object/v2.id","label":"Indonesian","srclang":"ind"},
                {"url":"http://insecure/x.vtt","label":"Bad","srclang":"en"}]}""",
        )
        val result = AniPmParser.sources(session, skip = null)
        val stream = result.streams.single()
        assertEquals("https://media.settlar.io/v1/object/v2.a.b.c", stream.url)
        assertNull("no Referer, so Cast plays it directly", stream.referer)
        assertEquals(listOf("en", "th", "id"), result.subtitles.map { it.language })
        assertEquals("English", result.subtitles.first().label)
    }

    @Test
    fun `skip windows come from the bootstrap and an empty one defers to AniSkip`() {
        val skip = AniPmParser.skip(
            obj("""{"skip":{"op":{"start":2.3,"end":89.34},"ed":{"start":1233.18,"end":1320.48}}}"""),
        )
        assertEquals(2.3, skip?.introStart)
        assertEquals(89.34, skip?.introEnd)
        assertEquals(1233.18, skip?.outroStart)
        assertNull(AniPmParser.skip(obj("""{"skip":null}""")))
    }
}
