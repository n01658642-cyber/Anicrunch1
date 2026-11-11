package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-device Continue Watching. Every case here is a device that has played the title itself,
 * which is exactly the case seeding used to skip — the phone kept offering episode 3 forever after
 * the television had watched through 8.
 */
class RemoteProgressReconcileTest {
    private fun local(
        episode: Double,
        positionMs: Long = 0,
        durationMs: Long = 0,
        updatedAt: Long = 1_000,
        watched: Set<Double> = emptySet(),
    ) = HistoryEntry(
        anilistId = 21,
        title = "One Piece",
        cover = null,
        episodeNumber = episode,
        episodeTitle = "Romance Dawn",
        provider = "kiwi",
        category = "sub",
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = updatedAt,
        watchedEpisodes = watched,
    )

    @Test
    fun `episodes finished on another device move this row forward`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
        )

        assertEquals(9.0, updated?.episodeNumber)
        assertEquals((1..8).map(Int::toDouble).toSet(), updated?.watchedEpisodes)
    }

    @Test
    fun `a row further along than the account is left alone`() {
        // This device is the newer writer; the account catches up on its own next push, and
        // pulling the row backwards would make the viewer rewatch what they just finished.
        assertNull(
            historyAfterRemoteProgress(
                local = local(episode = 12.0, watched = (1..11).map(Int::toDouble).toSet()),
                remoteProgress = 8,
                remoteUpdatedAt = 5_000,
                totalEpisodes = 1000,
            ),
        )
    }

    @Test
    fun `the account still fills in episodes this device never saw watched`() {
        // The row is level with the account, so it does not move — but the episode picker had no
        // idea 1-7 were finished elsewhere and drew them all as unwatched.
        val updated = historyAfterRemoteProgress(
            local = local(episode = 9.0, watched = setOf(8.0)),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
        )

        assertEquals(9.0, updated?.episodeNumber)
        assertEquals((1..8).map(Int::toDouble).toSet(), updated?.watchedEpisodes)
    }

    @Test
    fun `a resume position belonging to a finished episode is dropped`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0, positionMs = 600_000, durationMs = 1_400_000),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
        )

        assertEquals(0L, updated?.positionMs)
        assertEquals(0L, updated?.durationMs)
    }

    @Test
    fun `the row keeps the server and language this device chose`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
        )

        assertEquals("kiwi", updated?.provider)
        assertEquals("sub", updated?.category)
        // The stored title described episode 3 and this device has no catalogue here.
        assertNull(updated?.episodeTitle)
    }

    @Test
    fun `an advanced row is timestamped when the other device watched it`() {
        // Continue Watching orders by updatedAt. Stamping "now" would push a title the viewer last
        // touched days ago on another device to the top of the row.
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0, updatedAt = 1_000),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
        )

        assertEquals(5_000L, updated?.updatedAt)
    }

    @Test
    fun `a stale account timestamp never ages this row backwards`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0, updatedAt = 9_000),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
        )

        assertEquals(9_000L, updated?.updatedAt)
    }

    @Test
    fun `the row is not advanced past the end of the series`() {
        // Removing it is the caller's decision: the account cannot tell a finished series from one
        // being rewatched here, so this only declines to move.
        assertNull(
            historyAfterRemoteProgress(
                local = local(episode = 12.0),
                remoteProgress = 12,
                remoteUpdatedAt = 5_000,
                totalEpisodes = 12,
            ),
        )
    }

    @Test
    fun `an unknown episode count still allows the row to advance`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = null,
        )

        assertEquals(9.0, updated?.episodeNumber)
    }

    @Test
    fun `an account that has watched nothing changes nothing`() {
        assertNull(
            historyAfterRemoteProgress(
                local = local(episode = 3.0),
                remoteProgress = 0,
                remoteUpdatedAt = 5_000,
                totalEpisodes = 1000,
            ),
        )
    }

    /**
     * The airing case that made this bound necessary: 26 episodes ordered, 10 aired, the account
     * caught up on all 10. The planned run says episode 11 is fair game and it is six days away.
     */
    @Test
    fun `a caught-up row on an airing series stays on the last aired episode`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 10.0),
            remoteProgress = 10,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 26,
            airedEpisodes = 10,
        )

        assertEquals(10.0, updated?.episodeNumber)
        assertEquals((1..10).map(Int::toDouble).toSet(), updated?.watchedEpisodes)
    }

    @Test
    fun `the row advances once the next episode has aired`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 10.0),
            remoteProgress = 10,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 26,
            airedEpisodes = 11,
        )

        assertEquals(11.0, updated?.episodeNumber)
    }

    /** A finished series has no `nextAiringEpisode`, so the planned run stays the only bound. */
    @Test
    fun `an unknown aired count leaves the planned run in charge`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 1000,
            airedEpisodes = null,
        )

        assertEquals(9.0, updated?.episodeNumber)
    }

    /**
     * The report: episodes 6-8 watched on Crunchyroll and marked on AniList, 8 the latest aired. The
     * row stayed on "Episode 5" until 9 aired, which read as sync not working at all.
     */
    @Test
    fun `a row behind a caught-up account moves to the latest aired episode`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 5.0, positionMs = 300_000, durationMs = 1_400_000),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 12,
            airedEpisodes = 8,
        )

        assertEquals(8.0, updated?.episodeNumber)
        assertEquals((1..8).map(Int::toDouble).toSet(), updated?.watchedEpisodes)
        assertEquals(0L, updated?.positionMs)
        assertEquals(5_000L, updated?.updatedAt)
    }

    @Test
    fun `the row an account points at is the next episode, the latest aired, or none`() {
        assertEquals(9, remoteRowEpisode(progress = 8, totalEpisodes = 12, airedEpisodes = 10))
        assertEquals(9, remoteRowEpisode(progress = 8, totalEpisodes = null, airedEpisodes = null))
        // Caught up: resting on the latest aired, never on one still to come.
        assertEquals(8, remoteRowEpisode(progress = 8, totalEpisodes = 12, airedEpisodes = 8))
        // An account ahead of a stale aired count still points at something that exists.
        assertEquals(8, remoteRowEpisode(progress = 9, totalEpisodes = 12, airedEpisodes = 8))
        // Finished.
        assertNull(remoteRowEpisode(progress = 12, totalEpisodes = 12, airedEpisodes = null))
    }

    @Test
    fun `only a move off Watching since the last refresh takes a row off`() {
        val previous = mapOf(
            1 to "CURRENT", 2 to "REPEATING", 3 to "CURRENT", 4 to "COMPLETED",
            5 to "CURRENT", 6 to "PLANNING", 7 to "CURRENT",
        )
        val current = mapOf(
            1 to "COMPLETED", 2 to "DROPPED", 3 to "PAUSED",
            // Always finished: could be a rewatch here, so it is left alone.
            4 to "COMPLETED",
            5 to "CURRENT",
            6 to "COMPLETED",
            // 7 deleted from the account: a deletion, not a finish.
        )

        assertEquals(setOf(1, 2, 3), remoteMovesOffWatching(previous, current))
    }

    /** The first refresh after a login has no snapshot to compare with, so it removes nothing. */
    @Test
    fun `a first refresh moves nothing off`() {
        assertEquals(emptySet<Int>(), remoteMovesOffWatching(emptyMap(), mapOf(1 to "COMPLETED")))
    }

    /** Behind the front line, the aired bound must not interfere with an ordinary catch-up. */
    @Test
    fun `a row behind an airing series still catches up to the account`() {
        val updated = historyAfterRemoteProgress(
            local = local(episode = 3.0),
            remoteProgress = 8,
            remoteUpdatedAt = 5_000,
            totalEpisodes = 26,
            airedEpisodes = 10,
        )

        assertEquals(9.0, updated?.episodeNumber)
    }
    /**
     * A finale watched from downloads: the push completed the title on the account and wrote
     * COMPLETED into the snapshot, so no refresh ever saw a move, and the row sat on 13 for good.
     */
    @Test
    fun `a watched final episode on a completed title is a leftover`() {
        val row = local(episode = 13.0, watched = setOf(12.0, 13.0))

        assertTrue(isFinishedSeriesLeftover(row, "COMPLETED", totalEpisodes = 13))
    }

    /** A rewatch of a completed series is on an earlier episode, and keeps its row. */
    @Test
    fun `a rewatch on an earlier episode is not a leftover`() {
        val row = local(episode = 4.0, watched = setOf(1.0, 2.0, 3.0))

        assertFalse(isFinishedSeriesLeftover(row, "COMPLETED", totalEpisodes = 13))
    }

    /** Partway through the finale is still something to continue. */
    @Test
    fun `an unfinished final episode is not a leftover`() {
        val row = local(episode = 13.0, positionMs = 600_000, durationMs = 1_400_000, watched = setOf(12.0))

        assertFalse(isFinishedSeriesLeftover(row, "COMPLETED", totalEpisodes = 13))
    }

    @Test
    fun `only a completed title with a known length can leave one`() {
        val row = local(episode = 13.0, watched = setOf(13.0))

        assertFalse(isFinishedSeriesLeftover(row, "CURRENT", totalEpisodes = 13))
        assertFalse(isFinishedSeriesLeftover(row, "COMPLETED", totalEpisodes = null))
        assertFalse(isFinishedSeriesLeftover(row, "COMPLETED", totalEpisodes = 0))
    }
}
