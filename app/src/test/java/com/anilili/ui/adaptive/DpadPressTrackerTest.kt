package com.anilili.ui.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class DpadPressTrackerTest {
    private fun tracker() = DpadPressTracker(longPressTimeoutMs = 500)

    @Test
    fun `a quick press is a click`() {
        val tracker = tracker()

        tracker.onKeyDown(0)

        assertEquals(DpadPress.CLICK, tracker.onKeyUp(120))
    }

    /**
     * The menu must not open while the button is still down. It takes focus, and then the release
     * — a key-up whose key-down the menu never saw — lands on whichever button the menu focused,
     * because Compose's clickable acts on a key-up alone. A viewer reported exactly that: the menu
     * appeared while holding and vanished the instant they let go, leaving no moment in which to
     * press anything.
     */
    @Test
    fun `a hold reports nothing until the button is released`() {
        val tracker = tracker()

        tracker.onKeyDown(0)
        tracker.onKeyDown(500)
        tracker.onKeyDown(900)

        assertEquals(DpadPress.LONG_CLICK, tracker.onKeyUp(1_200))
    }

    /**
     * Some boxes stamp the release with the time of the press, so the release alone can look
     * instantaneous. The repeats seen during the hold are what carry the truth there.
     */
    @Test
    fun `a hold counts even when the release looks instantaneous`() {
        val tracker = tracker()

        tracker.onKeyDown(0)
        tracker.onKeyDown(600)

        assertEquals(DpadPress.LONG_CLICK, tracker.onKeyUp(0))
    }

    /** Not every television box auto-repeats a held OK; there the release is the only event. */
    @Test
    fun `a hold with no repeats is still a long press at release`() {
        val tracker = tracker()

        tracker.onKeyDown(0)

        assertEquals(DpadPress.LONG_CLICK, tracker.onKeyUp(800))
    }

    @Test
    fun `a press exactly at the threshold is long, one below it is a click`() {
        assertEquals(DpadPress.LONG_CLICK, tracker().onKeyUp(500))
        assertEquals(DpadPress.CLICK, tracker().onKeyUp(499))
    }

    /** A hold must never also land as a click, or it would resume the episode it just managed. */
    @Test
    fun `the tracker is reusable and a hold does not colour the next press`() {
        val tracker = tracker()

        tracker.onKeyDown(0)
        tracker.onKeyDown(700)
        assertEquals(DpadPress.LONG_CLICK, tracker.onKeyUp(900))

        tracker.onKeyDown(0)
        assertEquals(DpadPress.CLICK, tracker.onKeyUp(80))
    }
}
