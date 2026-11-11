package com.anilili.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSkipButtonPolicyTest {
    private val episodeDurationMs = 24 * 60_000L

    @Test
    fun `the manual skip is available for the whole episode`() {
        assertTrue(shouldShowManualSkipButton(positionMs = 0L, durationMs = episodeDurationMs))
        // The case that made this a fallback worth having: an opening that starts well after the
        // first minute, on a show whose timings nobody published.
        assertTrue(shouldShowManualSkipButton(positionMs = 90_000L, durationMs = episodeDurationMs))
        assertTrue(
            shouldShowManualSkipButton(positionMs = 12 * 60_000L, durationMs = episodeDurationMs),
        )
        assertTrue(
            shouldShowManualSkipButton(
                positionMs = episodeDurationMs,
                durationMs = episodeDurationMs,
            ),
        )
    }

    @Test
    fun `the manual skip waits for a valid timeline`() {
        assertFalse(shouldShowManualSkipButton(positionMs = 0L, durationMs = 0L))
        assertFalse(shouldShowManualSkipButton(positionMs = -1L, durationMs = episodeDurationMs))
        assertFalse(
            shouldShowManualSkipButton(
                positionMs = episodeDurationMs + 1L,
                durationMs = episodeDurationMs,
            ),
        )
    }
}
