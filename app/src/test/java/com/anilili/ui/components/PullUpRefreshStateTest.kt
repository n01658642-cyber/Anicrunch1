package com.anilili.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullUpRefreshStateTest {
    private fun state() = PullUpRefreshState(thresholdPx = 200f)

    @Test
    fun `a drag short of the threshold does not refresh`() {
        val state = state()

        state.drag(120f)

        assertFalse(state.release())
    }

    @Test
    fun `a drag exactly at the threshold refreshes, one pixel short does not`() {
        assertTrue(state().apply { drag(200f) }.release())
        assertFalse(state().apply { drag(199f) }.release())
    }

    /**
     * A finger that reverses mid-gesture is taking back what it offered, and the indicator has to
     * unwind before the list starts scrolling again — otherwise it stays out at full stretch while
     * the content moves away underneath it.
     */
    @Test
    fun `reversing the drag gives the pull back before it refreshes`() {
        val state = state()

        state.drag(150f)
        state.drag(-100f)
        state.drag(40f)

        assertFalse(state.release())
    }

    /**
     * The unwind stops at zero and reports how much of the reversal it actually used. The rest
     * belongs to the list: past this point the finger is scrolling, not un-pulling.
     */
    @Test
    fun `the unwind cannot go past zero and says how much it took`() {
        val state = state()

        state.drag(60f)

        assertEquals(-60f, state.drag(-100f), 0.01f)
        assertFalse(state.pulled)
        assertEquals(0f, state.drag(-80f), 0.01f)
    }

    @Test
    fun `the gauge fills across the threshold and stops there`() {
        val state = state()

        assertEquals(0f, state.progress, 0.01f)
        state.drag(100f)
        assertEquals(0.5f, state.progress, 0.01f)
        state.drag(400f)
        assertEquals(1f, state.progress, 0.01f)
    }

    /** A release must never also leave a pull behind, or the next one would start part-way in. */
    @Test
    fun `the state is reusable and a fired pull does not colour the next one`() {
        val state = state()

        state.drag(260f)
        assertTrue(state.release())
        assertFalse(state.pulled)

        state.drag(90f)
        assertFalse(state.release())
    }

    /** A refresh that started from the top of the page drops whatever the bottom had gathered. */
    @Test
    fun `cancelling clears the pull`() {
        val state = state()

        state.drag(190f)
        state.cancel()

        assertFalse(state.pulled)
        assertEquals(0f, state.progress, 0.01f)
    }

    /** Degenerate rather than dividing by zero: a threshold of nothing gauges nothing. */
    @Test
    fun `a zero threshold reports no progress`() {
        val state = PullUpRefreshState(thresholdPx = 0f)

        state.drag(50f)

        assertEquals(0f, state.progress, 0.01f)
    }
}
