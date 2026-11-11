package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDoubleTapSeekTest {
    @Test
    fun `the outer thirds seek and the middle keeps toggling playback`() {
        val width = 1000f

        assertEquals(PlayerDoubleTapZone.Rewind, playerDoubleTapZone(40f, width))
        assertEquals(PlayerDoubleTapZone.Rewind, playerDoubleTapZone(349f, width))
        assertEquals(PlayerDoubleTapZone.PlayPause, playerDoubleTapZone(500f, width))
        assertEquals(PlayerDoubleTapZone.Forward, playerDoubleTapZone(660f, width))
        assertEquals(PlayerDoubleTapZone.Forward, playerDoubleTapZone(990f, width))
    }

    @Test
    fun `a surface with no width reported falls back to play pause`() {
        // Better a tap that pauses than one that jumps the video somewhere arbitrary.
        assertEquals(PlayerDoubleTapZone.PlayPause, playerDoubleTapZone(10f, 0f))
        assertEquals(PlayerDoubleTapZone.PlayPause, playerDoubleTapZone(Float.NaN, 1000f))
        assertEquals(PlayerDoubleTapZone.PlayPause, playerDoubleTapZone(10f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `a chain of taps adds up from where it started`() {
        val duration = 1_200_000L
        val base = 60_000L

        // Four quick forward taps move forty seconds, not four hops from a drifting position.
        assertEquals(70_000L, playerDoubleTapSeekTarget(base, DOUBLE_TAP_SEEK_MS, duration))
        assertEquals(100_000L, playerDoubleTapSeekTarget(base, 4 * DOUBLE_TAP_SEEK_MS, duration))
        assertEquals(30_000L, playerDoubleTapSeekTarget(base, -3 * DOUBLE_TAP_SEEK_MS, duration))
    }

    @Test
    fun `a chain cannot run off either end of the video`() {
        val duration = 90_000L

        assertEquals(0L, playerDoubleTapSeekTarget(5_000L, -6 * DOUBLE_TAP_SEEK_MS, duration))
        assertEquals(duration, playerDoubleTapSeekTarget(85_000L, 9 * DOUBLE_TAP_SEEK_MS, duration))
        // A live stream reports no duration; the only bound left is the start.
        assertEquals(0L, playerDoubleTapSeekTarget(2_000L, -DOUBLE_TAP_SEEK_MS, 0L))
        assertEquals(12_000L, playerDoubleTapSeekTarget(2_000L, DOUBLE_TAP_SEEK_MS, 0L))
    }
}
