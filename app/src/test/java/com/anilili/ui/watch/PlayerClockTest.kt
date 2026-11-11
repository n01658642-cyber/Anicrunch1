package com.anilili.ui.watch

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerClockTest {

    private val twelveHour: DateTimeFormatter =
        DateTimeFormatter.ofPattern("h:mm a").withLocale(Locale.US)
    private val twentyFourHour: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm").withLocale(Locale.US)

    private fun clock(
        now: String,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        speed: Float = 1f,
        formatter: DateTimeFormatter = twelveHour,
    ) = playerClockText(LocalTime.parse(now), positionMs, durationMs, speed, formatter)

    @Test
    fun `names the time now and the time the episode runs out`() {
        assertEquals(
            "10:30 PM — Ends at 10:54 PM",
            clock("22:30", positionMs = 0L, durationMs = 24 * 60_000L),
        )
    }

    @Test
    fun `the ending counts from where the viewer is, not from the start`() {
        // Twenty minutes into a twenty-four minute episode: four left, not twenty-four.
        assertEquals(
            "10:30 PM — Ends at 10:34 PM",
            clock("22:30", positionMs = 20 * 60_000L, durationMs = 24 * 60_000L),
        )
    }

    @Test
    fun `the device's own clock setting carries through`() {
        assertEquals(
            "22:30 — Ends at 22:54",
            clock("22:30", durationMs = 24 * 60_000L, formatter = twentyFourHour),
        )
    }

    /**
     * The case the readout was asked for: it is late, and the answer is a time tomorrow. A naive
     * "hours past midnight" arithmetic prints 24:14 or 0:14 depending on which way it is wrong.
     */
    @Test
    fun `an episode that runs past midnight names tomorrow's time`() {
        assertEquals(
            "11:50 PM — Ends at 12:14 AM",
            clock("23:50", durationMs = 24 * 60_000L),
        )
    }

    @Test
    fun `a faster playback finishes sooner`() {
        assertEquals(
            "10:30 PM — Ends at 10:42 PM",
            clock("22:30", durationMs = 24 * 60_000L, speed = 2f),
        )
        assertEquals(
            "10:30 PM — Ends at 10:46 PM",
            clock("22:30", durationMs = 24 * 60_000L, speed = 1.5f),
        )
    }

    /**
     * Zero is what a player reports before it has said anything about its rate, not a stopped
     * episode. Dividing by it puts the ending at infinity, which overflows into a nonsense time.
     */
    @Test
    fun `a rate the player has not reported yet is read as normal speed`() {
        assertEquals(
            "10:30 PM — Ends at 10:54 PM",
            clock("22:30", durationMs = 24 * 60_000L, speed = 0f),
        )
        assertEquals(
            "10:30 PM — Ends at 10:54 PM",
            clock("22:30", durationMs = 24 * 60_000L, speed = -1f),
        )
    }

    /**
     * A live stream and a source still resolving both report no duration, and Media3 reports
     * `C.TIME_UNSET` as a large negative. Neither has an ending to name, so the clock stands alone
     * rather than printing a guess.
     */
    @Test
    fun `an unknown duration leaves the clock on its own`() {
        assertEquals("10:30 PM", clock("22:30", durationMs = 0L))
        assertEquals("10:30 PM", clock("22:30", durationMs = -1L))
        assertEquals("10:30 PM", clock("22:30", durationMs = Long.MIN_VALUE + 1))
    }

    @Test
    fun `an episode already at its end has no ending left to name`() {
        assertEquals("10:30 PM", clock("22:30", positionMs = 24 * 60_000L, durationMs = 24 * 60_000L))
        // Past the end: the player overruns its own reported duration on some sources.
        assertEquals("10:30 PM", clock("22:30", positionMs = 25 * 60_000L, durationMs = 24 * 60_000L))
    }

    /**
     * Seconds are not printed, so an episode with any part of a minute left must not round up into
     * a minute that has not started — the displayed clock truncates and this has to agree with it.
     */
    @Test
    fun `the ending truncates to the minute the way a clock does`() {
        assertEquals(
            "10:30 PM — Ends at 10:54 PM",
            clock("22:30", durationMs = 24 * 60_000L + 59_999L),
        )
    }
}
