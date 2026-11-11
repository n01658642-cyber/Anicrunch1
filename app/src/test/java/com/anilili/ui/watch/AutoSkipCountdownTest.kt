package com.anilili.ui.watch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSkipCountdownTest {
    private val intro = AutoSkipCountdown("Intro", startMs = 60_000L, endMs = 150_000L)

    @Test
    fun `label counts the seconds down`() {
        assertEquals("Skipping Intro · 3s", autoSkipCountdownLabel(intro, 3))
        assertEquals("Skipping Intro · 0s", autoSkipCountdownLabel(intro, -1))
    }

    @Test
    fun `sweep reaches the far edge as the count reaches zero`() {
        assertEquals(1f / 3f, autoSkipSweepTarget(3), 1e-6f)
        assertEquals(2f / 3f, autoSkipSweepTarget(2), 1e-6f)
        assertEquals(1f, autoSkipSweepTarget(1), 1e-6f)
        assertEquals(1f, autoSkipSweepTarget(0), 1e-6f)
    }

    @Test
    fun `countdown ticks every second down to zero`(): Unit = runBlocking {
        val seen = mutableListOf<Int>()
        runAutoSkipCountdown(isPlaying = { true }, onSecondsLeft = { if (seen.lastOrNull() != it) seen += it })
        assertEquals(listOf(3, 2, 1, 0), seen)
    }

    @Test
    fun `a pause holds the clock`(): Unit = runBlocking {
        var ticks = 0
        var last = -1
        // Paused for the first twenty ticks: two seconds of wall time that must not count.
        runAutoSkipCountdown(
            isPlaying = { ticks++ >= 20 },
            onSecondsLeft = { last = it },
        )
        assertEquals(0, last)
        assertEquals(50, ticks)
    }
}
