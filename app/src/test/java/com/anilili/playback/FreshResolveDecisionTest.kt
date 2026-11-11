package com.anilili.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dead transfer on a server whose URLs expire goes back to that server once for a fresh URL,
 * rather than straight to a different server — which, for Kartoons, would swap the Hindi audio the
 * viewer chose for whatever the next server speaks.
 */
class FreshResolveDecisionTest {
    private fun metadata(provider: String, freshResolves: Int = 0) = EpisodeDownloadMetadata(
        anilistId = 20,
        seriesTitle = "Naruto",
        episodeNumber = "105",
        provider = provider,
        category = "dub",
        freshResolves = freshResolves,
    )

    @Test
    fun `an expiring server is asked again once`() {
        assertTrue(EpisodeDownloads.shouldResolveAgainOnSameProvider(metadata("kartoons")))
        assertFalse(EpisodeDownloads.shouldResolveAgainOnSameProvider(metadata("kartoons", freshResolves = 1)))
    }

    @Test
    fun `any other server moves on as before`() {
        assertFalse(EpisodeDownloads.shouldResolveAgainOnSameProvider(metadata("anikoto")))
    }
}
