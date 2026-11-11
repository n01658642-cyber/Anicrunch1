package com.anilili.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchActivityTest {
    private val current = HistoryEntry(
        anilistId = 1,
        title = "Test series",
        cover = null,
        episodeNumber = 1.0,
        provider = "auto",
        category = "sub",
    )

    @Test
    fun `a completion counts once even if the next episode appears after the first callback`() {
        assertTrue(shouldRecordWatchActivity(current, 1.0))
        val caughtUp = requireNotNull(historyAfterEpisodeWatched(current, 1.0, null, null, false))
        assertFalse(shouldRecordWatchActivity(caughtUp, 1.0))
        val advanced = requireNotNull(historyAfterEpisodeWatched(caughtUp, 1.0, 2.0, null, false))
        assertFalse(shouldRecordWatchActivity(advanced, 1.0))
        assertTrue(shouldRecordWatchActivity(advanced, 2.0))
    }

    @Test
    fun `finishing the last episode counts but a previously marked final episode does not`() {
        assertTrue(shouldRecordWatchActivity(current, 1.0))
        assertFalse(shouldRecordWatchActivity(current.copy(watchedEpisodes = setOf(1.0)), 1.0))
    }
}
