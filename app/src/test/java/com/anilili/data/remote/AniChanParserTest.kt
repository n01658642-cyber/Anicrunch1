package com.anilili.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are trimmed from live `/api/watch/…` responses (Frieren ep1, 2026-09-13). */
class AniChanParserTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun obj(text: String) = json.parseToJsonElement(text) as JsonObject
    private fun servers(text: String) = obj(text)["servers"] as JsonArray

    @Test
    fun `episode count and the dub flag become the two availability sets`() {
        val availability = AniChanParser.availability(
            obj("""{"episodes":28,"sources":[{"name":"anichan"}],"dubAvailable":true}"""),
        )

        assertEquals((1..28).toSet(), availability.sub)
        assertEquals((1..28).toSet(), availability.dub)
    }

    @Test
    fun `a title with no dub publishes none`() {
        val availability = AniChanParser.availability(obj("""{"episodes":12,"dubAvailable":null}"""))

        assertEquals((1..12).toSet(), availability.sub)
        assertTrue(availability.dub.isEmpty())
    }

    @Test
    fun `a title AniChan does not carry is an error, not an empty catalogue`() {
        // The API answers 200 with a zero count rather than 404, so a silent empty result here
        // would register as "this provider has the title but no episodes" and stop the picker
        // from moving on.
        val thrown = runCatching {
            AniChanParser.availability(obj("""{"episodes":0,"sources":[],"dubAvailable":null}"""))
        }.exceptionOrNull()

        assertTrue("expected a failure, got $thrown", thrown is IllegalStateException)
    }

    @Test
    fun `servers are ranked, absolutised and stripped of the star in the label`() {
        val result = AniChanParser.sources(servers(SERVERS))

        assertEquals(listOf("AniChan", "zoko"), result.streams.map { it.quality })
        assertEquals(
            "https://anichan.to/api/watch/m3u8?sh=154587%2F1%2Fsub%2Fmaster.m3u8&sig=whANI&exp=1",
            result.streams[0].url,
        )
        // Already absolute, so it must be left alone rather than prefixed.
        assertTrue(result.streams[1].url.startsWith("https://c.toroplay.cymru/"))
        assertTrue("the first server must be the active one", result.streams[0].isActive)
        assertTrue(result.streams.all { it.isHls })
    }

    @Test
    fun `the backends we already reach elsewhere are dropped`() {
        // megaplay_hd1 is anikoto's upstream and vidhawk re-hosts the same thing; keeping either
        // would put one backend in the picker under three names.
        val names = AniChanParser.sources(servers(SERVERS)).streams.mapNotNull { it.quality }

        assertTrue("megaplay must not appear: $names", names.none { it.contains("HD-1") })
        assertTrue("vidhawk must not appear: $names", names.none { it.contains("vidHawk", true) })
    }

    @Test
    fun `streams name no referer, which is what keeps a cast off the proxy`() {
        AniChanParser.sources(servers(SERVERS)).streams.forEach { assertNull(it.referer) }
    }

    @Test
    fun `subtitles come from the leading server only, as vtt, with language codes`() {
        val result = AniChanParser.sources(servers(SERVERS))

        assertEquals(listOf("English", "Spanish (Latin American)"), result.subtitles.map { it.label })
        assertEquals(listOf("en", "es"), result.subtitles.map { it.language })
        // The `.ass` sibling of each track is deliberately not used; nothing reads SSA/ASS.
        assertTrue(result.subtitles.all { it.url.endsWith(".vtt") || it.url.contains(".vtt?") })
        // zoko's own English track must not add a second "English" row.
        assertEquals(2, result.subtitles.size)
    }

    @Test
    fun `the space AniChan leaves in a track filename is percent-encoded`() {
        // The live API names tracks after the language, so the English one really is
        // `English (CR)0.vtt` — with a space. `Uri.parse` does not encode it, a space is not legal
        // in a request target, and the fetch is refused before it leaves the device: the track
        // appears in the menu, the viewer selects it, and nothing is ever drawn. Measured against
        // the CDN, `%20` answers 200 and `+` answers 403, so this cannot be a form encoding.
        val result = AniChanParser.sources(servers(SERVERS_WITH_SPACES))

        assertEquals(
            listOf(
                "https://s1.toroplay.click/196187/1/sub/subs/English%20(CR)0.vtt?md5=a&expires=1",
                "https://s1.toroplay.click/196187/1/sub/subs/Spanish%20(Latin%20America%20CR)2.vtt?md5=c&expires=1",
            ),
            result.subtitles.map { it.url },
        )
    }

    @Test
    fun `an already-encoded stream URL is not encoded a second time`() {
        // absolute() runs over stream URLs too, and those carry percent-escapes of their own. A
        // general-purpose escaper here would turn every `%2F` into `%252F` and break playback.
        val result = AniChanParser.sources(servers(SERVERS_WITH_SPACES))

        assertTrue(
            "stream URL was re-encoded: ${result.streams.first().url}",
            result.streams.first().url.contains("sh=196187%2F1%2Fsub%2Fmaster.m3u8"),
        )
    }

    @Test
    fun `an episode whose servers are all duplicates fails rather than returning nothing`() {
        val thrown = runCatching {
            AniChanParser.sources(
                servers("""{"servers":[{"name":"megaplay_hd1","type":"hls","stream":"https://x/","rank":4}]}"""),
            )
        }.exceptionOrNull()

        assertTrue("expected a failure, got $thrown", thrown is IllegalStateException)
    }

    private companion object {
        val SERVERS = """
            {"servers":[
              {"name":"vidhawk","label":"vidHawk","type":"embed","rank":8,"stream":null},
              {"name":"megaplay_hd1","label":"HD-1","type":"hls","rank":4,
               "stream":"https://c.toroplay.cymru/api/watch/m3u8?url=https%3A%2F%2Fmegap.mikora.top%2Fa",
               "subtitles":[{"lang":"English","default":true,"url":"https://s/mp-eng.vtt"}]},
              {"name":"aniwatchtv","label":"zoko","type":"hls","rank":3,
               "stream":"https://c.toroplay.cymru/api/watch/m3u8?url=https%3A%2F%2Fhls2.aniwatchtv.uk%2Fv",
               "subtitles":[{"lang":"English","default":true,"url":"https://s/zoko-eng.vtt"}]},
              {"name":"anichan","label":"★ AniChan","type":"hls","rank":1,
               "stream":"/api/watch/m3u8?sh=154587%2F1%2Fsub%2Fmaster.m3u8&sig=whANI&exp=1",
               "subtitles":[
                 {"lang":"English","default":true,
                  "url":"https://s1.toroplay.click/154587/1/sub/subs/eng0.vtt?md5=a&expires=1",
                  "ass":"https://s1.toroplay.click/154587/1/sub/subs/eng0.ass?md5=b&expires=1"},
                 {"lang":"Spanish (Latin American)","default":false,
                  "url":"https://s1.toroplay.click/154587/1/sub/subs/spa1.vtt?md5=c&expires=1",
                  "ass":"https://s1.toroplay.click/154587/1/sub/subs/spa1.ass?md5=d&expires=1"}
               ]}
            ]}
        """.trimIndent()

        /** Track names exactly as `/api/watch/servers` returns them, spaces and all (2026-09-14). */
        val SERVERS_WITH_SPACES = """
            {"servers":[
              {"name":"anichan","label":"★ AniChan","type":"hls","rank":1,
               "stream":"/api/watch/m3u8?sh=196187%2F1%2Fsub%2Fmaster.m3u8&sig=whANI&exp=1",
               "subtitles":[
                 {"lang":"English","default":true,
                  "url":"https://s1.toroplay.click/196187/1/sub/subs/English (CR)0.vtt?md5=a&expires=1"},
                 {"lang":"Spanish (Latin America)","default":false,
                  "url":"https://s1.toroplay.click/196187/1/sub/subs/Spanish (Latin America CR)2.vtt?md5=c&expires=1"}
               ]}
            ]}
        """.trimIndent()
    }
}
