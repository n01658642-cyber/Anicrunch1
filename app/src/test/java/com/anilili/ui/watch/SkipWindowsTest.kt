package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipWindowsTest {

    private val e1 = "https://cdn.example/e1.m3u8"
    private val e2 = "https://cdn.example/e2.m3u8"

    // One Punch Man E2: intro 2:03-3:33 in a 24-minute file.
    private fun windows(positionMs: Long, clockStreamUrl: String?, streamUrl: String = e2) = skipWindowsAt(
        positionMs = positionMs,
        durationMs = 1_451_424L,
        clockStreamUrl = clockStreamUrl,
        streamUrl = streamUrl,
        recapStartMs = null,
        recapEndMs = null,
        introStartMs = 123_228L,
        introEndMs = 213_228L,
        outroStartMs = 1_329_553L,
        outroEndMs = 1_419_553L,
    )

    @Test
    fun `the playhead inside the intro is in the intro window`() {
        assertTrue(windows(positionMs = 130_000L, clockStreamUrl = e2).intro)
    }

    @Test
    fun `the outgoing episode's position does not open the next episode's intro`() {
        // E1 was left at 2:45, which falls inside E2's intro, and the controller still reports it
        // for a moment after E2's windows arrive.
        assertEquals(SkipWindows.NONE, windows(positionMs = 165_794L, clockStreamUrl = e1))
    }

    @Test
    fun `nothing is open before the clock has reported a stream at all`() {
        assertEquals(SkipWindows.NONE, windows(positionMs = 165_794L, clockStreamUrl = null))
    }

    @Test
    fun `once the clock is on the new stream its windows apply again`() {
        assertFalse(windows(positionMs = 0L, clockStreamUrl = e2).intro)
        assertTrue(windows(positionMs = 123_548L, clockStreamUrl = e2).intro)
    }
}
