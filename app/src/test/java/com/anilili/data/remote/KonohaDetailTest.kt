package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Konoha aggregates AniList onto a static CDN, so a title page can be built from it while AniList
 * is refusing requests. What Konoha does not carry must stay absent rather than be invented.
 */
class KonohaDetailTest {
    private val oneP1ece = KonohaDetail(
        ids = KonohaIds(anilist = 21, mal = 21),
        titles = KonohaTitles(romaji = "ONE PIECE", english = "ONE PIECE", native = "ワンピース"),
        format = "TV",
        status = "RELEASING",
        season = "FALL",
        season_year = 1999,
        episodes = 1168,
        duration = 24,
        description = "Gold Roger was known as the Pirate King.",
        genres = listOf("Action", "Adventure"),
        score_anilist = 87,
        popularity = 725193,
        images = KonohaImages(
            poster = "https://example.invalid/poster.jpg",
            poster_color = "#e49335",
            banner = "https://example.invalid/banner.jpg",
        ),
    )

    @Test
    fun `a detail record maps onto the fields a title page draws`() {
        val media = oneP1ece.toMedia(anilistId = 21)
        assertEquals(21, media.id)
        assertEquals(21, media.idMal)
        assertEquals("ONE PIECE", media.title.preferred)
        assertEquals("RELEASING", media.status)
        assertEquals(1999, media.seasonYear)
        assertEquals(1168, media.episodes)
        assertEquals(87, media.averageScore)
        assertEquals("https://example.invalid/poster.jpg", media.coverImage.best)
        assertEquals("https://example.invalid/banner.jpg", media.bannerImage)
        assertEquals(listOf("Action", "Adventure"), media.genres)
    }

    @Test
    fun `what Konoha does not carry is left empty rather than invented`() {
        val media = oneP1ece.toMedia(anilistId = 21)
        assertNull(media.nextAiringEpisode)
        assertNull(media.trailer)
        assertEquals(emptyList<Any>(), media.relations.edges)
        assertEquals(emptyList<Any>(), media.studios.nodes)
    }

    @Test
    fun `a record missing its own id falls back to the one that was asked for`() {
        val media = KonohaDetail().toMedia(anilistId = 999)
        assertEquals(999, media.id)
        assertEquals("Untitled", media.title.preferred)
    }
}
