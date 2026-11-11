package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutroWindowPolicyTest {
    private val episodeDurationMs = 24 * 60_000L + 5_000L

    @Test
    fun `credits that run to the end of the file are believed`() {
        assertTrue(
            outroWindowIsCredits(
                outroStartMs = 21 * 60_000L,
                outroEndMs = episodeDurationMs,
                durationMs = episodeDurationMs,
            ),
        )
    }

    @Test
    fun `credits followed by a preview are still credits`() {
        // The case measured timings exist for in the first place: the ending stops well short of
        // the last frame because the source pads with a next-episode preview.
        assertTrue(
            outroWindowIsCredits(
                outroStartMs = 20 * 60_000L,
                outroEndMs = 21 * 60_000L,
                durationMs = episodeDurationMs,
            ),
        )
    }

    @Test
    fun `the window AniSkip files for Bleach 274 is refused`() {
        // ed 4:46-6:16 of a 24:05 encode, which is what put "Next Episode" over the fifth minute.
        assertFalse(
            outroWindowIsCredits(
                outroStartMs = 286_665L,
                outroEndMs = 376_665L,
                durationMs = episodeDurationMs,
            ),
        )
    }

    @Test
    fun `a window from the middle of the episode is refused`() {
        assertFalse(
            outroWindowIsCredits(
                outroStartMs = 12 * 60_000L,
                outroEndMs = 13 * 60_000L,
                durationMs = episodeDurationMs,
            ),
        )
    }

    @Test
    fun `a half-formed or backwards window is refused`() {
        assertFalse(outroWindowIsCredits(null, episodeDurationMs, episodeDurationMs))
        assertFalse(outroWindowIsCredits(21 * 60_000L, null, episodeDurationMs))
        assertFalse(outroWindowIsCredits(-1L, episodeDurationMs, episodeDurationMs))
        assertFalse(outroWindowIsCredits(21 * 60_000L, 21 * 60_000L, episodeDurationMs))
        assertFalse(outroWindowIsCredits(21 * 60_000L, 20 * 60_000L, episodeDurationMs))
    }

    @Test
    fun `nothing is judged against an unknown duration`() {
        // A stream that has not loaded yet. The players re-read this once the duration arrives.
        assertFalse(outroWindowIsCredits(21 * 60_000L, episodeDurationMs, durationMs = 0L))
        assertFalse(outroWindowIsCredits(21 * 60_000L, episodeDurationMs, durationMs = -1L))
    }

    @Test
    fun `the threshold is measured against this file, not a fixed length`() {
        // A 12-minute short: an ending at 9:30 is its credits, and the same timestamp on a
        // full-length episode is not.
        val shortEpisodeMs = 12 * 60_000L
        assertTrue(outroWindowIsCredits(9 * 60_000L, 570_000L, shortEpisodeMs))
        assertFalse(outroWindowIsCredits(9 * 60_000L, 570_000L, episodeDurationMs))
    }

    @Test
    fun `skip outro lands on whatever follows the credits`() {
        // The highlighted outro ends a minute before the file does: the button lands on the preview.
        assertEquals(23 * 60_000L, outroSkipTargetMs(outroEndMs = 23 * 60_000L, durationMs = episodeDurationMs))
    }

    @Test
    fun `skip outro never seeks past the end of the file`() {
        // Credits to the last frame end the episode; a window filed past it is held to the end.
        assertEquals(episodeDurationMs, outroSkipTargetMs(episodeDurationMs, episodeDurationMs))
        assertEquals(episodeDurationMs, outroSkipTargetMs(episodeDurationMs + 4_000L, episodeDurationMs))
    }
}
