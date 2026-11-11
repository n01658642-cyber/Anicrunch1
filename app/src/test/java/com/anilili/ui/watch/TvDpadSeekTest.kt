package com.anilili.ui.watch

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvDpadSeekTest {
    @Test
    fun `the first press of a run is the first press`() {
        assertEquals(1, tvDpadSeekPresses(previousPresses = 0, msSinceLastPress = 0))
        assertEquals(1, tvDpadSeekPresses(previousPresses = 0, msSinceLastPress = 60_000))
    }

    @Test
    fun `presses that keep coming build on each other`() {
        var presses = 0
        repeat(5) { presses = tvDpadSeekPresses(presses, msSinceLastPress = 250) }

        assertEquals(5, presses)
    }

    /**
     * Coming back to the remote later should start again at the smallest step; otherwise a viewer
     * who seeked once an hour ago would find their next press jumping a minute.
     */
    @Test
    fun `a gap ends the run`() {
        val continued = tvDpadSeekPresses(previousPresses = 8, msSinceLastPress = TV_DPAD_SEEK_RUN_GAP_MS)
        val restarted = tvDpadSeekPresses(previousPresses = 8, msSinceLastPress = TV_DPAD_SEEK_RUN_GAP_MS + 1)

        assertEquals(9, continued)
        assertEquals(1, restarted)
    }

    /** The step has to grow, or travelling a long episode is as tedious as the old +10s button. */
    @Test
    fun `a sustained run travels further per press`() {
        val duration = 24 * 60 * 1000L

        val first = tvScrubStepMs(presses = 1, durationMs = duration)
        val middle = tvScrubStepMs(presses = 6, durationMs = duration)
        val late = tvScrubStepMs(presses = 12, durationMs = duration)

        assertTrue("$first should be under $middle", first < middle)
        assertTrue("$middle should be at most $late", middle <= late)
    }

    /** A short episode must never travel in leaps that skip past what the viewer is aiming at. */
    @Test
    fun `a short episode keeps a proportionate step however long the run`() {
        val fiveMinutes = 5 * 60 * 1000L

        val late = tvScrubStepMs(presses = 30, durationMs = fiveMinutes)

        assertTrue("$late should stay within a fiftieth of the episode", late <= fiveMinutes / 50 + 5_000)
    }

    /**
     * Android repeats a held key about every 50ms. Counting each one would climb the acceleration
     * almost instantly — a second of holding travelling most of an episode — so the timeline only
     * accepts presses at roughly the rate a person can tap.
     */
    @Test
    fun `a held key is throttled to something like a tapping rate`() {
        assertTrue(tvDpadSeekAccepts(TV_DPAD_SEEK_MIN_GAP_MS))
        assertTrue(tvDpadSeekAccepts(400))
        assertTrue("the very first press has no predecessor", tvDpadSeekAccepts(Long.MAX_VALUE))
        assertTrue("a 50ms auto-repeat must not count", !tvDpadSeekAccepts(50))
        assertTrue(!tvDpadSeekAccepts(TV_DPAD_SEEK_MIN_GAP_MS - 1))
    }

    /** A run of held-key repeats must still land somewhere the viewer would recognise. */
    @Test
    fun `a second of holding travels minutes, not most of the episode`() {
        val duration = 24 * 60 * 1000L
        var presses = 0
        var travelledMs = 0L
        // One accepted press every TV_DPAD_SEEK_MIN_GAP_MS for a second of holding.
        repeat((1_000L / TV_DPAD_SEEK_MIN_GAP_MS).toInt()) {
            presses = tvDpadSeekPresses(presses, TV_DPAD_SEEK_MIN_GAP_MS)
            travelledMs += tvScrubStepMs(presses, duration)
        }

        assertTrue("$travelledMs should stay under two minutes", travelledMs < 120_000)
    }

    @Test
    fun `seeking backwards past the start stops at the start`() {
        assertEquals(0L, tvSeekTargetMs(currentPositionMs = 4_000, durationMs = 600_000, offsetMs = -10_000))
    }

    @Test
    fun `seeking forwards past the end stops at the end`() {
        assertEquals(600_000L, tvSeekTargetMs(currentPositionMs = 595_000, durationMs = 600_000, offsetMs = 60_000))
    }

    @Test
    fun `a digit lands on its own tenth of the episode`() {
        val duration = 24 * 60 * 1000L

        assertEquals(0L, tvNumberSeekTargetMs(KeyEvent.KEYCODE_0, duration))
        assertEquals(duration / 10, tvNumberSeekTargetMs(KeyEvent.KEYCODE_1, duration))
        assertEquals(duration / 2, tvNumberSeekTargetMs(KeyEvent.KEYCODE_5, duration))
        assertEquals(duration * 9 / 10, tvNumberSeekTargetMs(KeyEvent.KEYCODE_9, duration))
    }

    /** The keypad on a keyboard-style remote or an air-mouse dongle reports a different code. */
    @Test
    fun `a numeric keypad seeks the same places as the number row`() {
        val duration = 24 * 60 * 1000L

        assertEquals(
            tvNumberSeekTargetMs(KeyEvent.KEYCODE_7, duration),
            tvNumberSeekTargetMs(KeyEvent.KEYCODE_NUMPAD_7, duration),
        )
        assertEquals(0L, tvNumberSeekTargetMs(KeyEvent.KEYCODE_NUMPAD_0, duration))
    }

    /** The furthest key still has a tenth of the episode left to play, so nothing lands on the end. */
    @Test
    fun `no digit runs the episode off its end`() {
        val duration = 24 * 60 * 1000L

        val furthest = tvNumberSeekTargetMs(KeyEvent.KEYCODE_9, duration)

        assertTrue("$furthest should stay inside the episode", furthest != null && furthest < duration)
    }

    @Test
    fun `keys that are not digits move nothing`() {
        val duration = 24 * 60 * 1000L

        assertNull(tvNumberSeekTargetMs(KeyEvent.KEYCODE_DPAD_CENTER, duration))
        assertNull(tvNumberSeekTargetMs(KeyEvent.KEYCODE_DPAD_LEFT, duration))
        assertNull(tvNumberSeekTargetMs(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, duration))
        assertNull(tvNumberSeekTargetMs(KeyEvent.KEYCODE_A, duration))
    }

    /**
     * A stream whose duration has not arrived yet would put every digit on the same frame — the
     * start — which reads as the keypad restarting the episode at random.
     */
    @Test
    fun `an episode of unknown length declines every digit`() {
        assertNull(tvNumberSeekTargetMs(KeyEvent.KEYCODE_5, durationMs = 0L))
        assertNull(tvNumberSeekTargetMs(KeyEvent.KEYCODE_5, durationMs = -1L))
    }
}
