package com.anilili.ui.nav

import com.anilili.parity.ParityCorpus
import com.anilili.parity.ParityCorpus.cases
import com.anilili.parity.ParityCorpus.double
import com.anilili.parity.ParityCorpus.string
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `anilili://` cases both apps must answer alike: the parity corpus's `links.json`. */
class AnililiLinkTest {

    private val corpus = ParityCorpus.load("links.json")

    @Test
    fun `reads every form the desktop reads`() {
        for (case in corpus.cases("read")) {
            val text = case.string("link")
            assertEquals(text, AnililiLink.Reading.Read(linkOf(case)), AnililiLink.read(text))
        }
    }

    @Test
    fun `refuses what the desktop refuses`() {
        val cases = corpus["refuse"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        assertTrue("the corpus's \"refuse\" section is empty", cases.isNotEmpty())
        for (text in cases) {
            assertTrue(text, AnililiLink.read(text) is AnililiLink.Reading.Refused)
        }
    }

    @Test
    fun `writes the canonical form and reads it back`() {
        for (case in corpus.cases("write")) {
            val link = linkOf(case)
            assertEquals(case.string("link"), link.toUrl())
            // A written `t` is floored, so a fractional start reads back as a different link.
            if (!(link is AnililiLink.Watch && link.startSeconds != null)) {
                assertEquals(link, AnililiLink.parse(link.toUrl()))
            }
        }
    }

    @Test
    fun `query values decode the way a form does`() {
        assertEquals(
            AnililiLink.Search("100% & more"),
            AnililiLink.parse("anilili://search?q=100%+%26+more"),
        )
        assertEquals(AnililiLink.Search("ü"), AnililiLink.parse("anilili://search?q=%C3%BC"))
        assertEquals(AnililiLink.Search("x"), AnililiLink.parse("anilili://search?from=a&q=x&q=y#frag"))
    }

    @Test
    fun `numbers follow the desktop's grammar, not Kotlin's`() {
        // Kotlin's own parsers would take each of these.
        for (text in listOf("anilili://watch/1/1d", "anilili://watch/1/0x10", "anilili://anime/١٢", "anilili://watch/1/NaN")) {
            assertTrue(text, AnililiLink.read(text) is AnililiLink.Reading.Refused)
        }
        assertEquals(AnililiLink.Watch(1, 5.0), AnililiLink.parse("anilili://watch/1/5."))
        assertEquals(AnililiLink.Watch(1, 0.0), AnililiLink.parse("anilili://watch/1/-0"))
        assertEquals(AnililiLink.Anime(4), AnililiLink.parse("anilili://anime/+4"))
        // The one deliberate difference: routes here carry an Int id, the desktop's an i64. No
        // AniList id is anywhere near the boundary.
        assertEquals(null, AnililiLink.parse("anilili://anime/4294967296"))
    }

    @Test
    fun `an episode label is the number as both apps write it`() {
        assertEquals("6.5", AnililiLink.Watch(1, 6.5).episodeLabel)
        assertEquals("3", AnililiLink.Watch(1, 3.0).episodeLabel)
        assertEquals("anilili://watch/1/12.25?t=9", AnililiLink.Watch(1, 12.25, 9.99).toUrl())
    }

    private fun linkOf(case: kotlinx.serialization.json.JsonObject): AnililiLink {
        val id = case["id"]?.jsonPrimitive?.int ?: 0
        return when (val page = case.string("page")) {
            "anime" -> AnililiLink.Anime(id)
            "watch" -> AnililiLink.Watch(
                id = id,
                episode = case.double("episode"),
                startSeconds = if ("start" in case) case.double("start") else null,
            )
            "search" -> AnililiLink.Search(case.string("query"))
            else -> error("unknown page $page")
        }
    }
}
