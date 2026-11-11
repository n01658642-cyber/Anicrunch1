package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture trimmed from the live `onepace.net/en/watch` flight payload (`RSC: 1`, 2026-09-24). */
class OnePaceParserTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val payload = listOf(
        """2:I[808477,["/_next/static/chunks/10815odi8j~bu.js"],"default"]""",
        """11:T4dd,magnet:?xt=urn:btih:1854d3a4&dn=%5BOne+Pace%5D""",
        """5:["$","${'$'}L10",null,{"data":{"timeline":{"segments":[""" +
            """{"slug":"romance-dawn","title":"Romance Dawn","special":false,"chapters":"1-7","episodes":"${'$'}undefined",""" +
            """"playlistGroups":[{"sub":"en","dub":"ja","items":[{"id":"LC22RWvq","resolution":480}]}],"parts":[""" +
            """{"slug":"romance-dawn-01","part":1,"title":"ROMANCE DAWN, ~The Dawn of an Adventure~","description":"Luffy dreams.",""" +
            """"duration":"17:57","stills":[{"src":"/_next/static/media/50-still.jpg","width":1920,"height":1080}],"fileGroups":[""" +
            """{"sub_accessibility":"${'$'}undefined","sub":"en","dub":"ja","variant":"extended","items":[{"id":"EXTsub01","resolution":1080}]},""" +
            """{"sub_accessibility":"${'$'}undefined","sub":"en","dub":"ja","variant":"${'$'}undefined","items":[{"id":"RwHyfKZs","resolution":480},{"id":"cbjgKSRu","resolution":1080},{"id":"fVLSZfep","resolution":720}]},""" +
            """{"sub_accessibility":"cc","sub":"en","dub":"en","variant":"${'$'}undefined","items":[{"id":"9p6gERAH","resolution":480}]},""" +
            """{"sub_accessibility":"${'$'}undefined","sub":"${'$'}undefined","dub":"en","variant":"${'$'}undefined","items":[{"id":"PsPFvq59","resolution":480}]}]},""" +
            """{"slug":"romance-dawn-02","part":2,"title":"${'$'}${'$'}100 Bounty","description":"${'$'}undefined","duration":"1:02:10","stills":[],"fileGroups":[""" +
            """{"sub_accessibility":"cc","sub":"en","dub":"en","variant":"${'$'}undefined","items":[{"id":"CConly02","resolution":720}]}]}]},""" +
            """{"slug":"wano","title":"Wano","special":false,"parts":[""" +
            """{"slug":"wano-63","part":63,"title":"Wano 63","description":"${'$'}undefined","chapters":"${'$'}undefined","duration":"${'$'}undefined","stills":[],"fileGroups":[]}]},""" +
            """{"slug":"one-piece-fan-letter","title":"One Piece Fan Letter","special":true,"parts":[""" +
            """{"slug":"one-piece-fan-letter-01","part":1,"title":"One Piece Fan Letter","stills":[],"fileGroups":[""" +
            """{"sub":"en","dub":"ja","items":[{"id":"FanLtr01","resolution":1080}]}]}]}""" +
            """]}}}]""",
        """6:["${'$'}","${'$'}L7",null,{}]""",
    ).joinToString("\n")

    private fun parsed() = OnePaceParser.parseTimeline(json, payload)

    @Test
    fun `every part of every arc comes out flat and in page order`() {
        val episodes = parsed()

        assertEquals(
            listOf("romance-dawn-01", "romance-dawn-02", "wano-63", "one-piece-fan-letter-01"),
            episodes.map(OnePaceEpisode::slug),
        )
        assertEquals(listOf("Romance Dawn", "Romance Dawn", "Wano", "One Piece Fan Letter"), episodes.map(OnePaceEpisode::arc))
    }

    @Test
    fun `flight markers are undone rather than shown`() {
        val second = parsed()[1]

        assertEquals("$100 Bounty", second.title)
        assertNull(second.description)
        assertNull(parsed()[2].duration)
    }

    @Test
    fun `stills are made absolute against the site`() {
        assertEquals("https://onepace.net/_next/static/media/50-still.jpg", parsed()[0].still)
        assertNull(parsed()[1].still)
    }

    @Test
    fun `titles name the arc once`() {
        val episodes = parsed()

        assertEquals("Romance Dawn 01: ROMANCE DAWN, ~The Dawn of an Adventure~", episodes[0].displayTitle)
        // The site titles unreleased slots after themselves.
        assertEquals("Wano 63", episodes[2].displayTitle)
        // A one-part special is its arc.
        assertEquals("One Piece Fan Letter", episodes[3].displayTitle)
    }

    @Test
    fun `runtime rounds to whole minutes and reads hours`() {
        assertEquals(18, parsed()[0].runtimeMinutes)
        assertEquals(62, parsed()[1].runtimeMinutes)
        assertNull(parsed()[2].runtimeMinutes)
    }

    @Test
    fun `sub plays the regular cut, not the extended one listed first`() {
        val group = OnePaceParser.groupFor(parsed()[0], OnePaceAudio.SUB)

        assertEquals("RwHyfKZs", group?.files?.first()?.id)
        assertNull(group?.variant)
    }

    @Test
    fun `dub prefers the plain dub over burned-in captions`() {
        val group = OnePaceParser.groupFor(parsed()[0], OnePaceAudio.DUB)

        assertEquals("PsPFvq59", group?.files?.single()?.id)
        assertFalse(group!!.closedCaptions)
    }

    @Test
    fun `the captioned dub is used when it is the only dub`() {
        val group = OnePaceParser.groupFor(parsed()[1], OnePaceAudio.DUB)

        assertEquals("CConly02", group?.files?.single()?.id)
        assertTrue(group!!.closedCaptions)
        // And it is not mistaken for a Japanese-audio release.
        assertNull(OnePaceParser.groupFor(parsed()[1], OnePaceAudio.SUB))
    }

    @Test
    fun `an unreleased slot has nothing to play`() {
        assertNull(OnePaceParser.groupFor(parsed()[2], OnePaceAudio.SUB))
        assertNull(OnePaceParser.groupFor(parsed()[2], OnePaceAudio.DUB))
    }

    @Test
    fun `streams are header-free pixeldrain files, sharpest first`() {
        val group = OnePaceParser.groupFor(parsed()[0], OnePaceAudio.SUB)!!
        val streams = OnePaceParser.streams(group)

        assertEquals(listOf(1080, 720, 480), streams.map { it.height })
        assertEquals("https://pixeldrain.net/api/file/cbjgKSRu", streams.first().url)
        assertEquals("mp4", streams.first().type)
        assertEquals("1080p", streams.first().quality)
        assertTrue(streams.first().isActive)
        assertTrue(streams.all { it.referer == null })
    }

    @Test
    fun `a payload without a timeline yields nothing`() {
        assertTrue(OnePaceParser.parseTimeline(json, "1:\"\$Sreact.fragment\"\n0:{\"P\":null}").isEmpty())
    }

    @Test
    fun `search finds it under its own name and One Piece's`() {
        assertTrue(matchesOnePaceQuery("One Pace"))
        assertTrue(matchesOnePaceQuery("onepace"))
        assertTrue(matchesOnePaceQuery("one p"))
        assertTrue(matchesOnePaceQuery("One Piece"))
        assertTrue(matchesOnePaceQuery("one piece film red"))
        assertFalse(matchesOnePaceQuery("one"))
        assertFalse(matchesOnePaceQuery("One Punch Man"))
        assertFalse(matchesOnePaceQuery("Naruto"))
    }

    @Test
    fun `its id is catalogue-native but not hanime's`() {
        assertTrue(isOnePaceMediaId(ONE_PACE_MEDIA_ID))
        assertTrue(isCatalogueNativeMediaId(ONE_PACE_MEDIA_ID))
        assertFalse(isHanimeMediaId(ONE_PACE_MEDIA_ID))
        assertTrue(isHanimeMediaId(hanimeMediaId(3412)))
    }

    @Test
    fun `its episode ids survive the native request parser`() {
        val request = NativeProviderParsers.episodeRequest("watch/onepace/$ONE_PACE_MEDIA_ID/dub/onepace-12")

        assertEquals("onepace", request?.provider)
        assertEquals(ONE_PACE_MEDIA_ID, request?.anilistId)
        assertEquals("dub", request?.audio)
        assertEquals(12, request?.episode)
    }
}
