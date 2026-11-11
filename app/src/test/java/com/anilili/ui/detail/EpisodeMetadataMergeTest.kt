package com.anilili.ui.detail

import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Providers differ in how they say "no title": some leave it null, others fill it with the episode
 * number ("Episode 5"), which looked identical on screen but silently outranked the real title the
 * Konoha overlay supplies — the AniKoto episode list read "Episode 5" while the Anime page showed
 * "Well Fed, Well Regarded" for the same episode.
 */
class EpisodeMetadataMergeTest {
    private fun episode(number: Double, title: String?, image: String? = null) =
        EpisodeItem(pipeId = "p:$number", number = number, title = title, image = image, filler = false)

    private fun meta(number: Double, title: String?, still: String? = null) =
        EpisodeMetadata(number = number, title = title, still = still, airDate = null)

    @Test
    fun `a title that only echoes the number counts as absent`() {
        assertNull(episode(5.0, "Episode 5").distinctTitle)
        assertNull(episode(5.0, "episode 5").distinctTitle)
        assertNull(episode(5.0, "EP 5").distinctTitle)
        assertNull(episode(5.0, "5").distinctTitle)
        assertNull(episode(5.0, "   ").distinctTitle)
        assertNull(episode(5.0, null).distinctTitle)
        assertEquals("Well Fed, Well Regarded", episode(5.0, "Well Fed, Well Regarded").distinctTitle)
        // Near misses stay real titles - only an exact echo is a placeholder.
        assertEquals("Episode 5: Well Fed", episode(5.0, "Episode 5: Well Fed").distinctTitle)
        assertEquals("Episode 6", episode(5.0, "Episode 6").distinctTitle)
    }

    @Test
    fun `the overlay wins over a placeholder title`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded", still = "https://cdn/still5.jpg")),
            animeId = 117612,
        )

        assertEquals("Well Fed, Well Regarded", merged.single().title)
        assertEquals("https://cdn/still5.jpg", merged.single().image)
    }

    @Test
    fun `a real title from the provider is kept`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "The Provider's Own Title")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded")),
            animeId = 117612,
        )

        assertEquals("The Provider's Own Title", merged.single().title)
    }

    @Test
    fun `a title a provider put on the wrong episode gives way to the catalogue`() {
        // Miruro's Frieren list: episode 7's title on episode 1, 1-6 shifted down one, then aligned.
        val merged = mergeEpisodeMetadata(
            base = listOf(
                episode(1.0, "Like a Fairy Tale"),
                episode(2.0, "The Journey's End"),
                episode(3.0, "It Didn't Have to Be Magic.."),
                episode(8.0, "Frieren the Slayer"),
                episode(9.0, "Its Own Wording"),
            ),
            meta = listOf(
                meta(1.0, "The Journey's End"),
                meta(2.0, "It Didn't Have to Be Magic"),
                meta(3.0, "Killing Magic"),
                meta(7.0, "Like a Fairy Tale"),
                meta(8.0, "Frieren the Slayer"),
                meta(9.0, "Aura the Guillotine"),
            ),
            animeId = 154587,
        )

        assertEquals(
            listOf("The Journey's End", "It Didn't Have to Be Magic", "Killing Magic", "Frieren the Slayer", "Its Own Wording"),
            merged.map { it.title },
        )
    }

    @Test
    fun `a title the catalogue does not know is not called misplaced`() {
        val numbers = catalogueNumberByTitle(listOf(meta(1.0, "The Journey's End")))
        assertFalse(titleBelongsElsewhere(episode(2.0, "Its Own Wording"), numbers))
        assertFalse(titleBelongsElsewhere(episode(1.0, "The Journey's End"), numbers))
        assertTrue(titleBelongsElsewhere(episode(2.0, "The Journey's End"), numbers))
    }

    @Test
    fun `a placeholder survives when the overlay has nothing better`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5")),
            meta = listOf(meta(5.0, null, still = "https://cdn/still5.jpg")),
            animeId = 117612,
        )

        // Nothing to upgrade to, so the row keeps rendering as before rather than losing its label.
        assertEquals("Episode 5", merged.single().title)
        assertEquals("https://cdn/still5.jpg", merged.single().image)
    }

    /**
     * The overlay becomes the catalogue itself when AniList supplied no count, and Konoha answers
     * a multi-part title with its numbering restarted per part — AniList 179068 comes back as
     * 1,2,3,4,5,6,1,1,1,1,1. Every one of those became the same synthetic pipe id, and a title
     * page keyed on it died with `Key "anilist:179068:1" was already used`.
     */
    @Test
    fun `a catalogue built from the overlay has one row per episode number`() {
        val built = mergeEpisodeMetadata(
            base = emptyList(),
            meta = listOf(
                meta(1.0, "Part 1"),
                meta(2.0, "Part 2"),
                meta(3.0, "Part 3"),
                meta(1.0, "Part 1"),
                meta(1.0, "Part 3"),
                meta(1.0, "Epilogue"),
            ),
            animeId = 179068,
        )

        assertEquals(listOf(1.0, 2.0, 3.0), built.map { it.number })
        assertEquals(built.map { it.pipeId }.distinct(), built.map { it.pipeId })
        // The first row for a number wins, so the part that actually carries it keeps its title.
        assertEquals("Part 1", built.first().title)
    }

    @Test
    fun `a half-numbered recap keeps an id of its own`() {
        val built = mergeEpisodeMetadata(
            base = emptyList(),
            meta = listOf(meta(1.0, "Episode 1"), meta(1.5, "Recap"), meta(2.0, "Episode 2")),
            animeId = 179068,
        )

        // Truncating to an Int put the recap on episode 1's id and crashed the same way.
        assertEquals(
            listOf("anilist:179068:1", "anilist:179068:1.5", "anilist:179068:2"),
            built.map { it.pipeId },
        )
    }

    @Test
    fun `episodes the overlay does not cover are untouched`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5"), episode(99.0, "Episode 99")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded")),
            animeId = 117612,
        )

        assertEquals("Well Fed, Well Regarded", merged[0].title)
        assertEquals("Episode 99", merged[1].title)
    }

    /**
     * The provider's thumbnail belongs to whichever server is selected, so the same episode used to
     * change picture when the viewer switched servers, and servers that ship no art left rows blank
     * beside a perfectly good catalogue still.
     */
    @Test
    fun `the catalogue still wins over the provider's thumbnail`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(5.0, "Episode 5", image = "https://server-of-the-day/5.jpg")),
            meta = listOf(meta(5.0, "Well Fed, Well Regarded", still = "https://image.tmdb.org/5.jpg")),
            animeId = 117612,
        )

        assertEquals("https://image.tmdb.org/5.jpg", merged.single().image)
    }

    @Test
    fun `the provider's thumbnail is kept where no catalogue could place the episode`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(
                episode(1.0, null, image = "https://server-of-the-day/1.jpg"),
                episode(2.0, null, image = "https://server-of-the-day/2.jpg"),
            ),
            // The airing season Konoha has not backfilled and TMDB cannot map: episode 2 is simply
            // not there, and episode 1 is there without a picture.
            meta = listOf(meta(1.0, "One", still = null)),
            animeId = 117612,
        )

        assertEquals("https://server-of-the-day/1.jpg", merged[0].image)
        assertEquals("https://server-of-the-day/2.jpg", merged[1].image)
    }

    @Test
    fun `a blank catalogue still does not blank out the row`() {
        val merged = mergeEpisodeMetadata(
            base = listOf(episode(1.0, null, image = "https://server-of-the-day/1.jpg")),
            meta = listOf(meta(1.0, "One", still = "")),
            animeId = 117612,
        )

        assertEquals("https://server-of-the-day/1.jpg", merged.single().image)
    }

    @Test
    fun `filler enrichment marks integer episodes and preserves provider flags`() {
        val episodes = listOf(
            episode(1.0, null),
            episode(2.0, null),
            episode(2.5, "Recap"),
            episode(4.0, null).copy(filler = true),
        )

        val marked = mergeEpisodeFillerMarks(episodes, setOf(2, 4))

        assertFalse(marked[0].filler)
        assertTrue(marked[1].filler)
        assertFalse("decimal recaps are not matched to an integer MAL episode", marked[2].filler)
        assertTrue("a provider-supplied flag is retained", marked[3].filler)
    }
}
