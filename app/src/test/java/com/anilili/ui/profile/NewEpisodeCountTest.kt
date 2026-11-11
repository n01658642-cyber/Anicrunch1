package com.anilili.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val NOW = 1_700_000_000L
private const val HOUR = 3_600L

private fun card(
    status: String? = "CURRENT",
    progress: Int? = 5,
    nextEpisodeNumber: Int? = 9,
    nextEpisodeAiringAt: Long? = NOW + 2 * HOUR,
) = SavedAnimeCardData(
    id = 1,
    title = "Airing series",
    cover = null,
    format = "TV",
    airingStatus = "RELEASING",
    status = status,
    userScore = null,
    averageScore = null,
    progress = progress,
    totalEpisodes = 12,
    nextEpisodeNumber = nextEpisodeNumber,
    nextEpisodeAiringAt = nextEpisodeAiringAt,
)

class NewEpisodeCountTest {
    @Test
    fun `episode 9 is next and five are watched, so three are waiting`() {
        assertEquals(3, newEpisodeCount(card(), NOW))
    }

    @Test
    fun `a viewer level with the broadcast gets no badge`() {
        assertNull(newEpisodeCount(card(progress = 8), NOW))
    }

    @Test
    fun `a viewer ahead of the broadcast gets no badge`() {
        // Progress can lead the schedule after a simulcast the account counted before AniList did.
        assertNull(newEpisodeCount(card(progress = 9), NOW))
    }

    @Test
    fun `a series with nothing scheduled has nothing new`() {
        assertNull(newEpisodeCount(card(nextEpisodeNumber = null, nextEpisodeAiringAt = null), NOW))
    }

    @Test
    fun `a series whose first episode has not aired yet has nothing new`() {
        val upcoming = card(status = "CURRENT", progress = 0, nextEpisodeNumber = 1)
        assertNull(newEpisodeCount(upcoming, NOW))
    }

    @Test
    fun `re-watching and paused count, completed and dropped do not`() {
        assertEquals(3, newEpisodeCount(card(status = "REPEATING"), NOW))
        assertEquals(3, newEpisodeCount(card(status = "PAUSED"), NOW))
        assertNull(newEpisodeCount(card(status = "COMPLETED"), NOW))
        assertNull(newEpisodeCount(card(status = "DROPPED"), NOW))
    }

    @Test
    fun `a planned title never started is not behind on anything`() {
        assertNull(newEpisodeCount(card(status = "PLANNING"), NOW))
    }

    @Test
    fun `a save known only to this device counts on its local progress`() {
        assertEquals(3, newEpisodeCount(card(status = null), NOW))
        assertNull(newEpisodeCount(card(status = null, progress = 0), NOW))
        assertNull(newEpisodeCount(card(status = null, progress = null), NOW))
    }

    @Test
    fun `a scheduled time already past counts that episode as out`() {
        // The media behind a MyAnimeList list is served from a day-long cache, so "next" can name an
        // episode that has since broadcast.
        val stale = card(nextEpisodeAiringAt = NOW - 2 * HOUR)
        assertEquals(4, newEpisodeCount(stale, NOW))
    }

    @Test
    fun `an episode that has just gone out is not counted until sites can carry it`() {
        val justAired = card(nextEpisodeAiringAt = NOW - 10 * 60L)
        assertEquals(3, newEpisodeCount(justAired, NOW))
    }

    @Test
    fun `a long-runner picked up early reports its whole backlog`() {
        // The badge caps what it prints; the count itself stays honest.
        val onePiece = card(progress = 40, nextEpisodeNumber = 1141)
        assertEquals(1100, newEpisodeCount(onePiece, NOW))
    }
}
