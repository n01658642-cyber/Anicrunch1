package com.anilili.data.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture is a real record written by `scripts/lili_sources.py` (2026-09-14), including the
 * percent-escaped Chinese directory the site actually serves from.
 */
class KonohaLiliSourcesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an episode is the shared base plus its own entry`() {
        val sources = json.decodeFromString(KonohaLiliSources.serializer(), JUJUTSU)

        assertEquals(
            "https://play.xfvod.pro:8088/Z/Z-%E5%92%92%E6%9C%AF%E5%9B%9E%E6%88%98/01.mp4",
            sources.urlFor(1),
        )
        assertEquals(
            "https://play.xfvod.pro:8088/Z/Z-%E5%92%92%E6%9C%AF%E5%9B%9E%E6%88%98/24.mp4",
            sources.urlFor(24),
        )
    }

    @Test
    fun `an episode the crawl did not reach is absent, not a half-built URL`() {
        // Concatenating the base with nothing would hand the player a directory, which fails in a
        // way that reads as a broken stream rather than a missing one.
        val sources = json.decodeFromString(KonohaLiliSources.serializer(), JUJUTSU)

        assertNull(sources.urlFor(25))
        assertNull(sources.urlFor(0))
    }

    @Test
    fun `entries keep whatever filename the site used, irregularities and all`() {
        // The site's airing staging area numbers inconsistently: this season carries a `z` on its
        // first episodes and drops it partway through. Storing suffixes rather than deriving them
        // from the episode number is what makes that survivable.
        val sources = json.decodeFromString(KonohaLiliSources.serializer(), AIRING)

        assertEquals("https://play.xfvod.pro:8088/temp/2607/%E6%97%A0%E8%81%8C07z.mp4", sources.urlFor(7))
        assertEquals("https://play.xfvod.pro:8088/temp/2607/%E6%97%A0%E8%81%8C08.mp4", sources.urlFor(8))
    }

    @Test
    fun `a staged title is flagged provisional and an archived one is not`() {
        // `temp` is the site's own word for it, and it is the signal that these paths move at the
        // year rollover — the difference between a record to trust and one to heal.
        assertTrue(json.decodeFromString(KonohaLiliSources.serializer(), AIRING).isProvisional)
        assertFalse(json.decodeFromString(KonohaLiliSources.serializer(), JUJUTSU).isProvisional)
    }

    @Test
    fun `a record with no episodes yields nothing rather than failing to parse`() {
        val empty = json.decodeFromString(KonohaLiliSources.serializer(), """{"provider":"xfdm"}""")

        assertNull(empty.urlFor(1))
        assertFalse(empty.isProvisional)
    }

    private companion object {
        const val JUJUTSU = """
{"provider":"xfdm","bangumi":294993,"subject":"/bangumi/1191.html","layout":"archive",
 "crawled":"2026-09-14","base":"https://play.xfvod.pro:8088/Z/Z-%E5%92%92%E6%9C%AF%E5%9B%9E%E6%88%98/",
 "episodes":{"1":"01.mp4","24":"24.mp4"}}
"""

        const val AIRING = """
{"provider":"xfdm","bangumi":501963,"subject":"/bangumi/3541.html","layout":"temp",
 "crawled":"2026-09-14","base":"https://play.xfvod.pro:8088/temp/2607/",
 "episodes":{"7":"%E6%97%A0%E8%81%8C07z.mp4","8":"%E6%97%A0%E8%81%8C08.mp4"}}
"""
    }
}
