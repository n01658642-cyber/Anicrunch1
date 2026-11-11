package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

/** The seek bar's middle band: how much of the episode has been fetched. */
class BufferedFractionTest {
    @Test
    fun halfAFetchedEpisodeIsHalfTheBar() {
        assertEquals(0.5f, bufferedFraction(600_000, 1_200_000), 0.0001f)
    }

    @Test
    fun anUnknownLengthDrawsNoBand() {
        // A live stream, or an episode whose duration has not arrived yet.
        assertEquals(0f, bufferedFraction(600_000, 0), 0f)
        assertEquals(0f, bufferedFraction(600_000, -1), 0f)
    }

    @Test
    fun aPlayerThatHasNotReportedABufferDrawsNoBand() {
        assertEquals(0f, bufferedFraction(0, 1_200_000), 0f)
    }

    @Test
    fun aBufferPastTheEndStopsAtTheEnd() {
        // Media3 can report a buffered position slightly beyond the reported duration.
        assertEquals(1f, bufferedFraction(1_300_000, 1_200_000), 0f)
    }
}
