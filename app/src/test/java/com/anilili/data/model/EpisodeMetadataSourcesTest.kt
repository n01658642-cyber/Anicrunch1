package com.anilili.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Konoha leads and TMDB fills the gaps, and the gaps measured in the library that reported this
 * are rarely whole rows: Akane-banashi has all twelve episode titles there and only nine stills.
 */
class EpisodeMetadataSourcesTest {
    private fun meta(number: Double, title: String? = null, still: String? = null) =
        EpisodeMetadata(number = number, title = title, still = still)

    @Test
    fun `a row keeps its title and gains the picture it was missing`() {
        val merged = mergeEpisodeMetadataSources(
            primary = listOf(meta(10.0, title = "The Rakugo Hall")),
            fallback = listOf(meta(10.0, title = "Episode 10", still = "https://image.tmdb.org/x.jpg")),
        )

        assertEquals("The Rakugo Hall", merged.single().title)
        assertEquals("https://image.tmdb.org/x.jpg", merged.single().still)
    }

    @Test
    fun `a row that already has a picture is left alone`() {
        val merged = mergeEpisodeMetadataSources(
            primary = listOf(meta(1.0, "Konoha title", "https://cdn/konoha.jpg")),
            fallback = listOf(meta(1.0, "TMDB title", "https://image.tmdb.org/tmdb.jpg")),
        )

        assertEquals("Konoha title", merged.single().title)
        assertEquals("https://cdn/konoha.jpg", merged.single().still)
    }

    @Test
    fun `a blank string counts as missing, not as an answer`() {
        val merged = mergeEpisodeMetadataSources(
            primary = listOf(meta(1.0, title = "", still = "")),
            fallback = listOf(meta(1.0, title = "Real", still = "https://image.tmdb.org/x.jpg")),
        )

        assertEquals("Real", merged.single().title)
        assertEquals("https://image.tmdb.org/x.jpg", merged.single().still)
    }

    @Test
    fun `episodes the leading source never heard of are appended`() {
        // Saga of Tanya the Evil's 2026 season is not in Konoha at all, so TMDB carries the list.
        val merged = mergeEpisodeMetadataSources(
            primary = emptyList(),
            fallback = listOf(meta(1.0, "One"), meta(2.0, "Two")),
        )

        assertEquals(listOf(1.0, 2.0), merged.map { it.number })
    }

    @Test
    fun `a longer fallback extends a short leading list without disturbing it`() {
        val merged = mergeEpisodeMetadataSources(
            primary = listOf(meta(1.0, "Konoha one")),
            fallback = listOf(meta(1.0, "TMDB one"), meta(2.0, "TMDB two")),
        )

        assertEquals(listOf(1.0, 2.0), merged.map { it.number })
        assertEquals("Konoha one", merged.first().title)
        assertEquals("TMDB two", merged.last().title)
    }

    @Test
    fun `nothing to fill with leaves the list exactly as it was`() {
        val konoha = listOf(meta(1.0, "One", "https://cdn/1.jpg"))
        assertEquals(konoha, mergeEpisodeMetadataSources(konoha, emptyList()))
        assertNull(mergeEpisodeMetadataSources(listOf(meta(1.0)), emptyList()).single().still)
    }

    @Test
    fun `AniList's streaming rows are numbered from the text of their titles`() {
        assertEquals(1, streamingEpisodeNumber("Episode 1"))
        assertEquals(12, streamingEpisodeNumber("Episode 12 - The Rakugo Hall"))
        assertEquals(3, streamingEpisodeNumber("EP3"))
        assertEquals(4, streamingEpisodeNumber("ep. 4"))
        assertEquals(5, streamingEpisodeNumber("Episode 05"))
    }

    @Test
    fun `a number inside the episode's own name is not read as its position`() {
        // Unanchored matching put Kaiju No. 8's every episode on episode 8, which is worse than
        // leaving the rows blank: it looks like real data and it is wrong.
        assertNull(streamingEpisodeNumber("Kaiju No. 8"))
        assertNull(streamingEpisodeNumber("The 100 Girlfriends Who Really Love You"))
        assertNull(streamingEpisodeNumber(null))
        assertNull(streamingEpisodeNumber(""))
    }

    @Test
    fun `only the thumbnail is taken from AniList, never the listing title`() {
        val rows = streamingEpisodeMetadata(
            listOf(
                StreamingEpisode(
                    title = "Episode 1 - Episode 1",
                    thumbnail = "https://crunchy/1.jpg",
                    site = "Crunchyroll",
                ),
            ),
        )

        // The listing text repeats the number the row already states; letting it fill the title
        // would overwrite a clean TMDB name with "Episode 1 - Episode 1".
        assertNull(rows.single().title)
        assertEquals("https://crunchy/1.jpg", rows.single().still)
        assertEquals(1.0, rows.single().number, 0.0)
    }

    @Test
    fun `rows without a number or without a picture are dropped`() {
        val rows = streamingEpisodeMetadata(
            listOf(
                StreamingEpisode(title = "Episode 1", thumbnail = "https://crunchy/1.jpg"),
                StreamingEpisode(title = "A Special", thumbnail = "https://crunchy/x.jpg"),
                StreamingEpisode(title = "Episode 2", thumbnail = ""),
                StreamingEpisode(title = "Episode 3", thumbnail = null),
            ),
        )

        assertEquals(listOf(1.0), rows.map { it.number })
    }

    @Test
    fun `the first service listing an episode wins`() {
        // AniList returns one row per streaming service, so a simulcast title lists each episode
        // several times over. Without the de-duplication these collide on number in the merge.
        val rows = streamingEpisodeMetadata(
            listOf(
                StreamingEpisode(title = "Episode 1", thumbnail = "https://crunchy/1.jpg", site = "Crunchyroll"),
                StreamingEpisode(title = "Episode 1", thumbnail = "https://hulu/1.jpg", site = "Hulu"),
            ),
        )

        assertEquals("https://crunchy/1.jpg", rows.single().still)
    }

    @Test
    fun `AniList fills only what Konoha and TMDB left blank`() {
        val merged = mergeEpisodeMetadataSources(
            primary = listOf(meta(1.0, "One", "https://cdn/1.jpg"), meta(2.0, "Two")),
            fallback = streamingEpisodeMetadata(
                listOf(
                    StreamingEpisode(title = "Episode 1", thumbnail = "https://crunchy/1.jpg"),
                    StreamingEpisode(title = "Episode 2", thumbnail = "https://crunchy/2.jpg"),
                ),
            ),
        )

        assertEquals("https://cdn/1.jpg", merged.first().still)
        assertEquals("https://crunchy/2.jpg", merged.last().still)
        assertEquals("Two", merged.last().title)
    }
}
