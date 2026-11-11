package com.anilili.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A row seeded from AniList has no server yet, and the sentinel that says so was being printed
 * where a source name belongs — reported by a viewer as the app showing "auto".
 */
class ContinueWatchingCaptionTest {
    private fun entry(provider: String) = HistoryEntry(
        anilistId = 1,
        title = "Anime",
        cover = null,
        episodeNumber = 6.0,
        provider = provider,
        category = "sub",
    )

    @Test
    fun `a played row still names the server it played from`() {
        assertEquals("EP 6  ·  kiwi", entry("kiwi").continueWatchingCaption())
    }

    @Test
    fun `a row seeded from the list service shows the episode alone`() {
        assertEquals("EP 6", entry(AUTO_PROVIDER).continueWatchingCaption())
    }

    @Test
    fun `a blank provider is not printed as an empty separator`() {
        assertEquals("EP 6", entry("").continueWatchingCaption())
    }
}
