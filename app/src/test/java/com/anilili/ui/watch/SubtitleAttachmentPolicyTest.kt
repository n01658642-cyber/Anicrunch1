package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAttachmentPolicyTest {
    @Test
    fun attachingSubtitleKeepsPositionAndPausedState() {
        val state = subtitleReprepareState(
            replacingCurrentItem = true,
            currentPositionMs = 123_456L,
            currentPlayWhenReady = false,
            requestedStartPositionMs = 8_000L,
            playOnOpen = true,
        )

        assertEquals(123_456L, state.positionMs)
        assertFalse(state.playWhenReady)
    }

    @Test
    fun newEpisodeUsesRequestedResumeAndStartsPlayback() {
        val state = subtitleReprepareState(
            replacingCurrentItem = false,
            currentPositionMs = 500_000L,
            currentPlayWhenReady = false,
            requestedStartPositionMs = 42_000L,
            playOnOpen = true,
        )

        assertEquals(42_000L, state.positionMs)
        assertTrue(state.playWhenReady)
    }

    @Test
    fun newEpisodeWaitsForThePlayButtonWhenPlayOnOpenIsOff() {
        val state = subtitleReprepareState(
            replacingCurrentItem = false,
            currentPositionMs = 0L,
            currentPlayWhenReady = false,
            requestedStartPositionMs = 42_000L,
            playOnOpen = false,
        )

        assertEquals(42_000L, state.positionMs)
        assertFalse(state.playWhenReady)
    }

    @Test
    fun playOnOpenOffStillCarriesRunningPlaybackIntoTheNextEpisode() {
        val state = subtitleReprepareState(
            replacingCurrentItem = false,
            currentPositionMs = 1_400_000L,
            currentPlayWhenReady = true,
            requestedStartPositionMs = 0L,
            playOnOpen = false,
        )

        assertEquals(0L, state.positionMs)
        assertTrue(state.playWhenReady)
    }

    /**
     * The case the outgoing player cannot speak for: it has already been stopped by the time the
     * next episode is prepared, so only the auto-advance flag knows this is a binge and not an open.
     */
    @Test
    fun autoAdvanceStartsTheNextEpisodeWithNoOutgoingPlaybackToInherit() {
        val state = subtitleReprepareState(
            replacingCurrentItem = false,
            currentPositionMs = 0L,
            currentPlayWhenReady = false,
            requestedStartPositionMs = 0L,
            playOnOpen = false,
            autoAdvanced = true,
        )

        assertTrue(state.playWhenReady)
    }

    /** An auto-advance must not disturb a subtitle re-prepare of the episode already on screen. */
    @Test
    fun autoAdvanceDoesNotResumeAPausedEpisodeBeingRepreparedForSubtitles() {
        val state = subtitleReprepareState(
            replacingCurrentItem = true,
            currentPositionMs = 90_000L,
            currentPlayWhenReady = false,
            requestedStartPositionMs = 0L,
            playOnOpen = false,
            autoAdvanced = true,
        )

        assertEquals(90_000L, state.positionMs)
        assertFalse(state.playWhenReady)
    }
}
