package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClampRemoteProgressTest {

    @Test
    fun `AniList episode count remains authoritative when known`() {
        assertEquals(24, remoteEpisodeCeiling(anilistEpisodes = 24, malEpisodes = 48))
    }

    @Test
    fun `MAL episode count fills an absent AniList count`() {
        assertEquals(24, remoteEpisodeCeiling(anilistEpisodes = null, malEpisodes = 24))
        assertEquals(24, remoteEpisodeCeiling(anilistEpisodes = 0, malEpisodes = 24))
    }

    @Test
    fun `invalid episode counts do not become a ceiling`() {
        assertEquals(null, remoteEpisodeCeiling(anilistEpisodes = null, malEpisodes = null))
        assertEquals(null, remoteEpisodeCeiling(anilistEpisodes = 0, malEpisodes = -1))
    }

    @Test
    fun `a count past the finale is held at the finale`() {
        // The report this came from: a 24-episode season showing "Episode 25 of 24, 100%".
        assertEquals(24, clampRemoteProgress(progress = 25, totalEpisodes = 24))
        assertEquals(12, clampRemoteProgress(progress = 300, totalEpisodes = 12))
    }

    @Test
    fun `a count inside the season is left alone`() {
        assertEquals(19, clampRemoteProgress(progress = 19, totalEpisodes = 24))
        assertEquals(24, clampRemoteProgress(progress = 24, totalEpisodes = 24))
        assertEquals(0, clampRemoteProgress(progress = 0, totalEpisodes = 24))
    }

    @Test
    fun `an unknown length cannot be a ceiling`() {
        // Ongoing shows have no length on AniList yet; the reported figure is all there is.
        assertEquals(25, clampRemoteProgress(progress = 25, totalEpisodes = null))
        assertEquals(25, clampRemoteProgress(progress = 25, totalEpisodes = 0))
        assertEquals(25, clampRemoteProgress(progress = 25, totalEpisodes = -1))
    }
}
