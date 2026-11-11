package com.anilili.ui.watch

import com.anilili.data.library.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedEpisodePolicyTest {

    private val history = HistoryEntry(
        anilistId = 1,
        title = "Cowboy Bebop",
        cover = null,
        episodeNumber = 3.0,
        provider = "pewe",
        category = "sub",
        positionMs = 420_000L,
        durationMs = 1_440_000L,
    )

    @Test
    fun `offline progress accepts the current stream`() {
        assertTrue(isExpectedOfflineProgressStream("content://episode-2", "content://episode-2"))
    }

    @Test
    fun `offline progress rejects an outgoing stream after episode selection changes`() {
        assertFalse(isExpectedOfflineProgressStream("content://episode-2", "content://episode-1"))
    }

    @Test
    fun `offline progress is rejected when no episode is playable`() {
        assertFalse(isExpectedOfflineProgressStream(null, "content://episode-1"))
    }

    @Test
    fun `offline playback resumes the matching unfinished episode`() {
        assertEquals(420_000L, offlineResumePosition(history, anilistId = 1, episodeNumber = 3.0))
    }

    @Test
    fun `offline playback does not borrow another episode position`() {
        assertEquals(0L, offlineResumePosition(history, anilistId = 1, episodeNumber = 4.0))
        assertEquals(0L, offlineResumePosition(history, anilistId = 2, episodeNumber = 3.0))
    }

    @Test
    fun `watched offline episodes replay from the beginning`() {
        val watched = history.copy(watchedEpisodes = setOf(3.0))

        assertEquals(0L, offlineResumePosition(watched, anilistId = 1, episodeNumber = 3.0))
    }

    @Test
    fun `offline clock in the ending window restarts instead of reopening credits`() {
        val ending = history.copy(positionMs = history.durationMs - 5_000L)

        assertEquals(0L, offlineResumePosition(ending, anilistId = 1, episodeNumber = 3.0))
    }

    @Test
    fun `playing video draws no back arrow of its own`() {
        assertFalse(showsDownloadBackControl(hasPlayable = true, hasPlaybackError = false))
    }

    @Test
    fun `a screen with no player keeps a visible way out`() {
        assertTrue(showsDownloadBackControl(hasPlayable = false, hasPlaybackError = false))
        assertTrue(showsDownloadBackControl(hasPlayable = true, hasPlaybackError = true))
    }
}
