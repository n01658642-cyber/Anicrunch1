package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerActionRowFitTest {
    /**
     * What the phone player's bottom-right row wants: five label-less pills at 38dp and the cast
     * button's 48dp, and what a 360dp-wide window has left for it after the lock button and the
     * clock — the window the too-small gear was reported from, at density 2.
     */
    private val wantedPx = (5 * 38 + 48) * 2
    private val leftOverPx = 229 * 2

    @Test
    fun `a row with room for it is left at its own size`() {
        assertEquals(1f, playerActionRowScale(naturalWidthPx = 400, availableWidthPx = 400), 0f)
        assertEquals(1f, playerActionRowScale(naturalWidthPx = 400, availableWidthPx = 900), 0f)
    }

    @Test
    fun `the reported window comes down as a set, not at its last control`() {
        val scale = playerActionRowScale(naturalWidthPx = wantedPx, availableWidthPx = leftOverPx)
        // The row is a near miss there — 476px of controls into 458px — so the answer has to be a
        // fraction just under one: the bug was the gear alone being drawn at the 18px that was left,
        // which is what a scale near zero would look like.
        assertTrue("scale $scale is not a near miss", scale in 0.95f..0.99f)
    }

    @Test
    fun `a row that cannot fit at all still yields a usable fraction`() {
        assertEquals(0.5f, playerActionRowScale(naturalWidthPx = 400, availableWidthPx = 200), 0f)
        assertEquals(0f, playerActionRowScale(naturalWidthPx = 400, availableWidthPx = 0), 0f)
    }

    @Test
    fun `degenerate widths do not produce a nonsense scale`() {
        // An empty row is not a row that has to shrink, and a width that has gone negative cannot
        // produce a negative scale — which would draw the row mirrored.
        assertEquals(1f, playerActionRowScale(naturalWidthPx = 0, availableWidthPx = 300), 0f)
        assertEquals(0f, playerActionRowScale(naturalWidthPx = 400, availableWidthPx = -5), 0f)
    }
}
