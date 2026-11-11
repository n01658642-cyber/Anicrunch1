package com.anilili.ui.watch

import com.anilili.playback.SLEEP_FADE_MS
import com.anilili.playback.SleepTimer
import com.anilili.playback.formatSleepRemaining
import com.anilili.playback.sleepFadeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerSleepTimerTest {

    @Test
    fun volumeIsUntouchedUntilTheFadeBegins() {
        assertEquals(1f, sleepFadeLevel(30 * 60_000L), 0f)
        assertEquals(1f, sleepFadeLevel(SLEEP_FADE_MS), 0f)
    }

    @Test
    fun volumeFallsLinearlyToSilenceAcrossTheFade() {
        assertEquals(0.5f, sleepFadeLevel(SLEEP_FADE_MS / 2), 0.001f)
        assertEquals(0f, sleepFadeLevel(0L), 0f)
        assertEquals(0f, sleepFadeLevel(-500L), 0f)
    }

    @Test
    fun remainingTimeRoundsUpSoZeroMeansFired() {
        assertEquals("0:01", formatSleepRemaining(1L))
        assertEquals("0:00", formatSleepRemaining(0L))
        assertEquals("1:00", formatSleepRemaining(59_001L))
        assertEquals("23:41", formatSleepRemaining((23 * 60 + 41) * 1000L))
    }

    @Test
    fun remainingTimeGainsAnHourFieldOnlyWhenNeeded() {
        assertEquals("59:59", formatSleepRemaining(3_599_000L))
        assertEquals("1:00:00", formatSleepRemaining(3_600_000L))
    }

    @Test
    fun segmentsFollowTheTimerState() {
        assertEquals(0, sleepTimerSegment(SleepTimer.State.Off))
        assertEquals(45, sleepTimerSegment(SleepTimer.State.Counting(45, deadlineElapsedMs = 0L)))
        // End of episode belongs to its switch; lighting a segment as well would claim two choices.
        assertNull(sleepTimerSegment(SleepTimer.State.EndOfEpisode))
    }

    @Test
    fun segmentLabelsFitTheNarrowestPanel() {
        assertEquals(listOf("Off", "15m", "30m", "45m", "1h"), listOf(0, 15, 30, 45, 60).map(::sleepTimerSegmentLabel))
    }
}
