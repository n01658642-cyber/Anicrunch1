package com.anilili.data.remote

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule files are written by `scripts/konoha_build.py` and read here, and the two halves are
 * in different languages in different repositories — so the wire shape is pinned by a test on this
 * side rather than trusted. The sample below is a verbatim row from a generated `2026-09.json`.
 */
class KonohaAiringTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Note the absent `native` on the first row: the generator writes it only when a title has
    // nothing above it in the app's fallback, because it is a full Japanese string on every row and
    // carrying it unconditionally cost 28% of the compressed month. The third row is the case that
    // earns it.
    private val published = """
        [{"id":21,"episode":1176,"airingAt":1788099360,"english":"ONE PIECE","romaji":"ONE PIECE",
          "cover":"https://s4.anilist.co/cover/bx21.jpg","format":"TV","year":1999},
         {"id":999,"episode":3,"airingAt":1788185760,"romaji":"Nightly Thing","adult":true},
         {"id":1000,"episode":7,"airingAt":1788272160,"native":"ぼっち・ざ・ろっく"}]
    """.trimIndent()

    @Test
    fun `a published month decodes into airings`() {
        val rows = json.decodeFromString(ListSerializer(KonohaAiring.serializer()), published)

        assertEquals(3, rows.size)
        assertEquals(21, rows[0].id)
        assertEquals(1176, rows[0].episode)
        assertEquals(1788099360L, rows[0].airingAt)
    }

    @Test
    fun `a title with nothing but a native name still names itself`() {
        val rows = json.decodeFromString(ListSerializer(KonohaAiring.serializer()), published)

        // The one row the conditional `native` exists for. If the generator ever stopped writing it
        // for this case, the title would render as "Untitled" rather than merely untranslated.
        assertEquals("ぼっち・ざ・ろっく", rows[2].toAiringSchedule().media?.title?.preferred)
    }

    @Test
    fun `the fields the generator drops come back as their defaults`() {
        val rows = json.decodeFromString(ListSerializer(KonohaAiring.serializer()), published)

        // The generator writes no key at all for a null, and none for a non-adult row — which is
        // almost every row and almost every field, so the defaults here are the common path.
        assertEquals(null, rows[1].english)
        assertEquals(null, rows[1].cover)
        assertEquals(null, rows[1].year)
        assertFalse(rows[0].adult)
        assertTrue(rows[1].adult)
    }

    @Test
    fun `an airing maps onto what a schedule card draws`() {
        val row = json.decodeFromString(ListSerializer(KonohaAiring.serializer()), published).first()

        val airing = row.toAiringSchedule()

        assertEquals(1176, airing.episode)
        assertEquals(1788099360L, airing.airingAt)
        assertEquals(21, airing.media?.id)
        assertEquals("ONE PIECE", airing.media?.title?.preferred)
        // `best` prefers extraLarge and falls back to large, so the one URL the file carries has to
        // come back out whichever slot it was put in.
        assertEquals("https://s4.anilist.co/cover/bx21.jpg", airing.media?.coverImage?.best)
        assertEquals("TV", airing.media?.format)
        assertEquals(1999, airing.media?.seasonYear)
        assertFalse(airing.media?.isAdult == true)
    }

    @Test
    fun `an adult row stays flagged so the viewer's own setting can drop it`() {
        val rows = json.decodeFromString(ListSerializer(KonohaAiring.serializer()), published)

        // Carried unfiltered on purpose: filtering in the generator would bake one audience's
        // answer into a file every install reads.
        assertTrue(rows[1].toAiringSchedule().media?.isAdult == true)
    }

    @Test
    fun `a title with no english still names itself`() {
        val rows = json.decodeFromString(ListSerializer(KonohaAiring.serializer()), published)

        assertEquals("Nightly Thing", rows[1].toAiringSchedule().media?.title?.preferred)
    }
}
