package com.anilili.ui.components

import com.anilili.data.library.EpisodeResume
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.historyAfterEpisodeToggled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The eye's filled state and the direction its press moves have to be the same fact.
 *
 * Every episode picker draws its eye from [episodeMarkedWatched], and the press writes through
 * [historyAfterEpisodeToggled], which flips membership of [HistoryEntry.watchedEpisodes] and
 * nothing else. Driven instead by [episodeWatchFraction] — which is the *looser* test, and is what
 * the profile and the in-player list draw with — an episode merely resumed to within a second of
 * its end would show a filled eye, offer to unmark itself, and be dropped as a no-op by the very
 * function that draws the mark. A control that silently does nothing is worse than no control.
 */
class EpisodeWatchedEyeTest {
    private fun row(
        episodeNumber: Double = 5.0,
        watched: Set<Double> = emptySet(),
        resumePoints: Map<String, EpisodeResume> = emptyMap(),
    ) = HistoryEntry(
        anilistId = 21,
        title = "One Piece",
        cover = null,
        episodeNumber = episodeNumber,
        provider = "auto",
        category = "sub",
        watchedEpisodes = watched,
        resumePoints = resumePoints,
    )

    @Test
    fun `a marked episode draws a filled eye`() {
        assertTrue(episodeMarkedWatched(row(watched = setOf(3.0, 5.0)), 3.0))
        assertTrue(episodeMarkedWatched(row(watched = setOf(3.0, 5.0)), 5.0))
    }

    @Test
    fun `an unmarked episode draws an empty one`() {
        assertFalse(episodeMarkedWatched(row(watched = setOf(3.0)), 4.0))
    }

    @Test
    fun `a title with no row yet offers the mark, not its removal`() {
        // Signed out, or a show never opened: the row is created by the first press, so the eye
        // has to read empty for that press to be the one that makes it.
        assertFalse(episodeMarkedWatched(null, 1.0))
    }

    @Test
    fun `an episode played to its last second is still not a mark`() {
        val nearlyDone = row(
            episodeNumber = 7.0,
            resumePoints = mapOf("7" to EpisodeResume(1_439_000, 1_440_000)),
        )

        // The fraction the rest of the UI draws with calls this watched; the mark does not exist
        // until the player crosses the threshold and writes one. The eye follows the mark.
        assertTrue(episodeWatchFraction(nearlyDone, 7.0) >= 0.999f)
        assertFalse(episodeMarkedWatched(nearlyDone, 7.0))
    }

    @Test
    fun `the press the eye offers always lands`() {
        val rows = listOf(
            row(watched = setOf(3.0, 5.0)),
            row(resumePoints = mapOf("7" to EpisodeResume(1_439_000, 1_440_000))),
        )
        val episodes = listOf(3.0, 4.0, 5.0, 7.0)

        rows.forEach { entry ->
            episodes.forEach { episode ->
                val marked = episodeMarkedWatched(entry, episode)
                assertNotNull(
                    "episode $episode of watched=${entry.watchedEpisodes}",
                    historyAfterEpisodeToggled(entry, episode, watched = !marked),
                )
            }
        }
    }

    @Test
    fun `half episodes carry their own mark`() {
        // A recap numbered 6.5 is a different episode from 6, and the catalogues say so.
        assertTrue(episodeMarkedWatched(row(watched = setOf(6.5)), 6.5))
        assertFalse(episodeMarkedWatched(row(watched = setOf(6.5)), 6.0))
    }
}
