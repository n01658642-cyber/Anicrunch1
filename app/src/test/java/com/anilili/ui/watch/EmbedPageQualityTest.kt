package com.anilili.ui.watch

import com.anilili.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ladder an embed page reports over the JavaScript bridge.
 *
 * The bridge is exposed to every frame the page loads, ad slots included, so these cases are as
 * much about what the parser refuses as what it accepts.
 */
class EmbedPageQualityTest {
    @Test
    fun `reads a reported ladder`() {
        val quality = parseEmbedPageQuality(
            """
            {"engine":"jwplayer","auto":true,"current":1,"levels":[
              {"id":0,"label":"1080p","height":1080},
              {"id":1,"label":"720p","height":720},
              {"id":2,"label":"480p","height":480}
            ]}
            """.trimIndent(),
        )

        assertEquals("jwplayer", quality?.engine)
        assertEquals(true, quality?.autoSupported)
        assertEquals(1, quality?.current)
        assertEquals(listOf("1080p", "720p", "480p"), quality?.levels?.map { it.label })
        assertEquals(listOf(1080, 720, 480), quality?.levels?.map { it.height })
    }

    /** -1 is how every engine says "I am choosing", and it must survive the round trip. */
    @Test
    fun `keeps an adaptive selection`() {
        val quality = parseEmbedPageQuality(
            """{"engine":"hlsjs","auto":true,"current":-1,"levels":[{"id":0,"label":"720p","height":720},{"id":1,"label":"360p","height":360}]}""",
        )

        assertEquals(-1, quality?.current)
    }

    @Test
    fun `a selection outside the ladder falls back to adaptive`() {
        val quality = parseEmbedPageQuality(
            """{"engine":"hlsjs","auto":true,"current":9,"levels":[{"id":0,"label":"720p","height":720},{"id":1,"label":"360p","height":360}]}""",
        )

        assertEquals(-1, quality?.current)
    }

    /**
     * The id is handed straight back to the page as the rendition to select, so one that is not its
     * own position is dropped rather than sent to a player that would read it as a different rung.
     */
    @Test
    fun `drops a level whose id is not its position`() {
        val quality = parseEmbedPageQuality(
            """
            {"engine":"hlsjs","auto":true,"current":-1,"levels":[
              {"id":0,"label":"1080p","height":1080},
              {"id":7,"label":"720p","height":720},
              {"id":2,"label":"480p","height":480}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("1080p", "480p"), quality?.levels?.map { it.label })
        assertEquals(listOf(0, 2), quality?.levels?.map { it.id })
    }

    /** One rendition is not a choice, and showing it as one implies the rest were lost. */
    @Test
    fun `a ladder of one reads as no ladder`() {
        assertNull(
            parseEmbedPageQuality("""{"engine":"hlsjs","auto":true,"current":0,"levels":[{"id":0,"label":"720p","height":720}]}"""),
        )
    }

    @Test
    fun `labels a level with no name of its own by its height`() {
        val quality = parseEmbedPageQuality(
            """{"engine":"videojs","auto":true,"current":-1,"levels":[{"id":0,"label":"","height":1080},{"id":1,"label":"","height":360}]}""",
        )

        assertEquals(listOf("1080p", "360p"), quality?.levels?.map { it.label })
    }

    @Test
    fun `an unnamed level with no height cannot be shown`() {
        val quality = parseEmbedPageQuality(
            """
            {"engine":"videojs","auto":false,"current":0,"levels":[
              {"id":0,"label":"1080p","height":1080},
              {"id":1,"label":"","height":0},
              {"id":2,"label":"480p","height":480}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("1080p", "480p"), quality?.levels?.map { it.label })
    }

    @Test
    fun `caps what an untrusted frame can put on screen`() {
        val levels = (0 until 40).joinToString(",") { """{"id":$it,"label":"${"q".repeat(80)}","height":${it + 1}}""" }
        val quality = parseEmbedPageQuality("""{"engine":"hlsjs","auto":true,"current":-1,"levels":[$levels]}""")

        assertEquals(12, quality?.levels?.size)
        assertTrue(quality?.levels?.all { it.label.length <= 24 } == true)
    }

    @Test
    fun `an absent or unreadable report is no ladder`() {
        assertNull(parseEmbedPageQuality(null))
        assertNull(parseEmbedPageQuality(""))
        assertNull(parseEmbedPageQuality("   "))
        assertNull(parseEmbedPageQuality("not json"))
        assertNull(parseEmbedPageQuality("""{"engine":"hlsjs"}"""))
    }

    /**
     * The section used to vanish whole when it had nothing to offer, which is how a viewer on
     * AnimeDekho or ReAnime read a missing control as a missing feature.
     */
    @Test
    fun `an empty ladder is explained rather than hidden`() {
        assertNull(embedQualityMessage(hasLadder = true, hasOtherServers = true))
        assertNull(embedQualityMessage(hasLadder = true, hasOtherServers = false))
        assertTrue(
            embedQualityMessage(hasLadder = false, hasOtherServers = true)
                .orEmpty().contains("Another one below"),
        )
        assertEquals(
            "This server chooses the video quality itself.",
            embedQualityMessage(hasLadder = false, hasOtherServers = false),
        )
    }

    /** Measured on device: four mirrors from `bee`, every one of them labelled "auto". */
    @Test
    fun `unnamed mirrors are named after their host rather than all reading auto`() {
        val labels = embedServerLabels(
            listOf(
                embed("https://vidtube.site/e/one"),
                embed("https://www.emturbovid.com/t/two"),
                embed("https://vault-99.atomic4cdn.top/e/three"),
                embed("https://vidtube.site/e/four"),
            ),
        ).map { (_, label) -> label }

        assertEquals(listOf("Vidtube", "Emturbovid", "Vault", "Vidtube 2"), labels)
    }

    @Test
    fun `a mirror the provider named keeps its name`() {
        val labels = embedServerLabels(
            listOf(
                embed("https://abyssplayer.com/one", quality = "AbyssPlayer"),
                embed("https://flixcloud.cc/two", quality = "HD-2 embed"),
            ),
        ).map { (_, label) -> label }

        assertEquals(listOf("AbyssPlayer", "HD-2 embed"), labels)
    }

    @Test
    fun `a mirror with neither a name nor a host still says which row it is`() {
        val labels = embedServerLabels(listOf(embed("not a url"), embed("also not a url")))
            .map { (_, label) -> label }

        assertEquals(listOf("Server 1", "Server 2"), labels)
    }

    private fun embed(url: String, quality: String? = null) = StreamItem(
        url = url,
        type = "embed",
        quality = quality,
        audio = null,
        referer = null,
        isActive = false,
        width = null,
        height = null,
    )
}
