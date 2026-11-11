package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPlaybackReturnsTest {
    private val session = ExternalPlaybackSession(anilistId = 21, episodeNumber = 5.0, durationMsHint = 1_440_000L)

    @Test
    fun `a position past the threshold counts as watched`() {
        val outcome = externalPlaybackOutcome(session, 1_300_000L, 1_420_000L, completed = false, thresholdPercent = 85)
        assertEquals(ExternalPlaybackOutcome(1_300_000L, 1_420_000L, watched = true), outcome)
    }

    @Test
    fun `stopping early records the position without marking the episode`() {
        val outcome = externalPlaybackOutcome(session, 600_000L, 1_420_000L, completed = false, thresholdPercent = 85)
        assertFalse(outcome!!.watched)
        assertEquals(600_000L, outcome.positionMs)
    }

    @Test
    fun `a player that reports a position alone is judged against the measured length`() {
        val outcome = externalPlaybackOutcome(session, 1_400_000L, null, completed = false, thresholdPercent = 85)
        assertEquals(1_440_000L, outcome!!.durationMs)
        assertTrue(outcome.watched)
    }

    @Test
    fun `running out of episode is watched even with no position`() {
        val outcome = externalPlaybackOutcome(session, null, null, completed = true, thresholdPercent = 85)
        assertEquals(ExternalPlaybackOutcome(1_440_000L, 1_440_000L, watched = true), outcome)
        val unmeasured = externalPlaybackOutcome(session.copy(durationMsHint = 0L), null, null, true, 85)
        assertTrue(unmeasured!!.watched)
    }

    @Test
    fun `nothing to judge without a position or a length`() {
        assertNull(externalPlaybackOutcome(session, null, 1_420_000L, completed = false, thresholdPercent = 85))
        assertNull(externalPlaybackOutcome(session.copy(durationMsHint = 0L), 900_000L, null, false, 85))
    }

    @Test
    fun `a half episode is never counted`() {
        val half = session.copy(episodeNumber = 5.5)
        assertFalse(externalPlaybackOutcome(half, 1_400_000L, 1_420_000L, false, 85)!!.watched)
    }
}
