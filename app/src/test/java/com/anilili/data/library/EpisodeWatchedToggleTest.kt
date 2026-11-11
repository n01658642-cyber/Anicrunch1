package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-made mark. Without the season's episode list it never moves which episode Continue
 * Watching points at; with one, a mark at or past the row moves it on the way finishing the episode
 * would, and an immediate unmark puts it back.
 */
class EpisodeWatchedToggleTest {
    private val current = HistoryEntry(
        anilistId = 21,
        title = "One Piece",
        cover = null,
        episodeNumber = 5.0,
        provider = "auto",
        category = "sub",
        positionMs = 300_000,
        durationMs = 1_440_000,
        watchedEpisodes = setOf(1.0, 2.0),
        resumePoints = mapOf(
            "3" to EpisodeResume(120_000, 1_440_000),
            "5" to EpisodeResume(300_000, 1_440_000),
        ),
    )

    @Test
    fun `marking an episode records it without moving the row`() {
        val updated = requireNotNull(historyAfterEpisodeToggled(current, 3.0, watched = true))

        assertEquals(setOf(1.0, 2.0, 3.0), updated.watchedEpisodes)
        assertEquals(5.0, updated.episodeNumber, 0.0)
        // A full bar should not also offer to resume at 2:00.
        assertNull(updated.resumePoints["3"])
        assertEquals(300_000, updated.positionMs)
    }

    @Test
    fun `marking the row's own episode spends its clock too`() {
        val updated = requireNotNull(historyAfterEpisodeToggled(current, 5.0, watched = true))

        assertTrue(5.0 in updated.watchedEpisodes)
        assertEquals(0, updated.positionMs)
        assertEquals(0, updated.durationMs)
        assertNull(updated.resumePoints["5"])
        assertEquals(5.0, updated.episodeNumber, 0.0)
    }

    @Test
    fun `unmarking removes only the mark`() {
        val updated = requireNotNull(historyAfterEpisodeToggled(current, 2.0, watched = false))

        assertEquals(setOf(1.0), updated.watchedEpisodes)
        assertFalse(2.0 in updated.watchedEpisodes)
        assertEquals(current.resumePoints, updated.resumePoints)
    }

    @Test
    fun `a mark that changes nothing is not a write`() {
        assertNull(historyAfterEpisodeToggled(current, 1.0, watched = true))
        assertNull(historyAfterEpisodeToggled(current, 9.0, watched = false))
    }

    @Test
    fun `half episodes keep their own identity`() {
        val updated = requireNotNull(historyAfterEpisodeToggled(current, 6.5, watched = true))

        assertTrue(6.5 in updated.watchedEpisodes)
        assertFalse(6.0 in updated.watchedEpisodes)
    }

    private val clannad = HistoryEntry(
        anilistId = 2167,
        title = "Clannad",
        cover = null,
        episodeNumber = 2.0,
        provider = "auto",
        category = "dub",
        positionMs = 1_400_000,
        durationMs = 1_440_000,
        watchedEpisodes = setOf(1.0),
        fromRemote = true,
    )
    private val aired = (1..23).map { EpisodeStop(it.toDouble(), "Title $it") }

    @Test
    fun `marking up to the episode after the row moves the row past it`() {
        // The reported case: on episode 2, episodes 2 and 3 marked by hand, Home kept offering 2.
        val afterTwo = requireNotNull(historyAfterEpisodeToggled(clannad, 2.0, watched = true, aired))
        assertEquals(3.0, afterTwo.episodeNumber, 0.0)
        val afterThree = requireNotNull(historyAfterEpisodeToggled(afterTwo, 3.0, watched = true, aired))

        assertEquals(4.0, afterThree.episodeNumber, 0.0)
        assertEquals("Title 4", afterThree.episodeTitle)
        assertEquals(0, afterThree.positionMs)
        assertEquals(0, afterThree.durationMs)
        assertFalse(afterThree.fromRemote)
        assertEquals(setOf(1.0, 2.0, 3.0), afterThree.watchedEpisodes)
    }

    @Test
    fun `marking ahead of the row jumps it past the mark`() {
        val updated = requireNotNull(historyAfterEpisodeToggled(clannad, 3.0, watched = true, aired))

        assertEquals(4.0, updated.episodeNumber, 0.0)
    }

    @Test
    fun `the row skips episodes that are already marked`() {
        val gap = clannad.copy(watchedEpisodes = setOf(1.0, 3.0, 4.0))
        val updated = requireNotNull(historyAfterEpisodeToggled(gap, 2.0, watched = true, aired))

        assertEquals(5.0, updated.episodeNumber, 0.0)
    }

    @Test
    fun `the row picks up the clock the next episode was left at`() {
        val started = clannad.copy(resumePoints = mapOf("3" to EpisodeResume(600_000, 1_440_000)))
        val updated = requireNotNull(historyAfterEpisodeToggled(started, 2.0, watched = true, aired))

        assertEquals(3.0, updated.episodeNumber, 0.0)
        assertEquals(600_000, updated.positionMs)
        assertEquals(1_440_000, updated.durationMs)
    }

    @Test
    fun `marking the latest aired episode leaves the row caught up on it`() {
        val airing = aired.take(5)
        val updated = requireNotNull(historyAfterEpisodeToggled(clannad, 5.0, watched = true, airing))

        assertEquals(5.0, updated.episodeNumber, 0.0)
        assertEquals("Title 5", updated.episodeTitle)
        assertEquals(0, updated.positionMs)
    }

    @Test
    fun `filling in a gap behind the row leaves it alone`() {
        val ahead = clannad.copy(episodeNumber = 10.0, watchedEpisodes = setOf(1.0, 2.0))
        val updated = requireNotNull(historyAfterEpisodeToggled(ahead, 5.0, watched = true, aired))

        assertEquals(10.0, updated.episodeNumber, 0.0)
        assertEquals(clannad.positionMs, updated.positionMs)
    }

    @Test
    fun `unmarking straight after a slip puts the row back`() {
        val moved = requireNotNull(historyAfterEpisodeToggled(clannad, 2.0, watched = true, aired))
        val undone = requireNotNull(historyAfterEpisodeToggled(moved, 2.0, watched = false, aired))

        assertEquals(2.0, undone.episodeNumber, 0.0)
        assertEquals("Title 2", undone.episodeTitle)
        assertEquals(setOf(1.0), undone.watchedEpisodes)
    }

    @Test
    fun `unmarking does not pull back a row the viewer has started`() {
        val moved = requireNotNull(historyAfterEpisodeToggled(clannad, 2.0, watched = true, aired))
            .copy(positionMs = 90_000, durationMs = 1_440_000)
        val updated = requireNotNull(historyAfterEpisodeToggled(moved, 2.0, watched = false, aired))

        assertEquals(3.0, updated.episodeNumber, 0.0)
        assertEquals(90_000, updated.positionMs)
    }

    @Test
    fun `unmarking an old episode does not pull the row back`() {
        val far = clannad.copy(episodeNumber = 10.0, positionMs = 0, watchedEpisodes = (1..9).map { it.toDouble() }.toSet())
        val updated = requireNotNull(historyAfterEpisodeToggled(far, 3.0, watched = false, aired))

        assertEquals(10.0, updated.episodeNumber, 0.0)
    }
}
