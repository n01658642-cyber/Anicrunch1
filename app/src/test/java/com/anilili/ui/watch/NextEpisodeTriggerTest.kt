package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPISODE_MS = 24 * 60_000L

class NextEpisodeTriggerTest {

    private fun trigger(
        positionMs: Long,
        durationMs: Long = EPISODE_MS,
        outroStartMs: Long? = null,
        outroEndMs: Long? = null,
    ) = nextEpisodeTrigger(positionMs, durationMs, outroStartMs, outroEndMs)

    /**
     * The card used to arrive the moment the highlighted credits began, and sat over them with its
     * countdown — where "Skip Outro" belongs. Inside the credits it now offers nothing at all.
     */
    @Test
    fun nothingIsOfferedWhileTheHighlightedCreditsRoll() {
        val outroStart = 22 * 60_000L
        assertEquals(NextEpisodeTrigger.NONE, trigger(outroStart, outroStartMs = outroStart, outroEndMs = EPISODE_MS))
        // Inside the tail of the file too: the credits still win until they finish.
        assertEquals(
            NextEpisodeTrigger.NONE,
            trigger(EPISODE_MS - NEXT_EPISODE_TAIL_WINDOW_MS, outroStartMs = outroStart, outroEndMs = EPISODE_MS),
        )
    }

    /**
     * Credits filed to the last frame still have to hand over somewhere: the embed player has no
     * end-of-file event and only sees a position. The final few seconds are that somewhere.
     */
    @Test
    fun creditsToTheLastFrameOfferInTheFinalSeconds() {
        val outroStart = 22 * 60_000L
        assertEquals(
            NextEpisodeTrigger.NONE,
            trigger(EPISODE_MS - NEXT_EPISODE_FINAL_MS - 1, outroStartMs = outroStart, outroEndMs = EPISODE_MS),
        )
        assertEquals(
            NextEpisodeTrigger.AFTER_CREDITS,
            trigger(EPISODE_MS - NEXT_EPISODE_FINAL_MS, outroStartMs = outroStart, outroEndMs = EPISODE_MS),
        )
        // Where "Skip Outro" lands on such a window.
        assertEquals(
            NextEpisodeTrigger.AFTER_CREDITS,
            trigger(EPISODE_MS, outroStartMs = outroStart, outroEndMs = EPISODE_MS),
        )
    }

    /**
     * A preview after the credits plays out: the card waits for the tail of the file, not for the
     * end of the credits, so the preview is not covered or cut short.
     */
    @Test
    fun aPreviewAfterTheCreditsIsLeftToPlay() {
        val outroStart = 21 * 60_000L
        val outroEnd = 22 * 60_000L + 30_000L
        assertEquals(NextEpisodeTrigger.NONE, trigger(outroEnd, outroStartMs = outroStart, outroEndMs = outroEnd))
        assertEquals(
            NextEpisodeTrigger.NONE,
            trigger(EPISODE_MS - NEXT_EPISODE_TAIL_WINDOW_MS - 1, outroStartMs = outroStart, outroEndMs = outroEnd),
        )
        assertEquals(
            NextEpisodeTrigger.AFTER_CREDITS,
            trigger(EPISODE_MS - NEXT_EPISODE_TAIL_WINDOW_MS, outroStartMs = outroStart, outroEndMs = outroEnd),
        )
    }

    /** Credits that end inside the tail hand over the moment they finish. */
    @Test
    fun creditsEndingInsideTheTailHandOverAsTheyFinish() {
        val outroStart = 22 * 60_000L
        val outroEnd = EPISODE_MS - 15_000L
        assertEquals(NextEpisodeTrigger.NONE, trigger(outroEnd - 1, outroStartMs = outroStart, outroEndMs = outroEnd))
        assertEquals(
            NextEpisodeTrigger.AFTER_CREDITS,
            trigger(outroEnd, outroStartMs = outroStart, outroEndMs = outroEnd),
        )
    }

    @Test
    fun withoutTimingsItWaitsForTheTailOfTheFile() {
        assertEquals(NextEpisodeTrigger.NONE, trigger(EPISODE_MS - NEXT_EPISODE_TAIL_WINDOW_MS - 1))
        assertEquals(NextEpisodeTrigger.ENDING, trigger(EPISODE_MS - NEXT_EPISODE_TAIL_WINDOW_MS))
        assertEquals(NextEpisodeTrigger.ENDING, trigger(EPISODE_MS))
    }

    /**
     * A half-formed window says nothing about where the credits are. Falling back to the tail is
     * right; treating the one timestamp we have as the whole outro would be a guess.
     */
    @Test
    fun aWindowMissingAnEndIsNotUsed() {
        assertEquals(NextEpisodeTrigger.NONE, trigger(60_000L, outroStartMs = 30_000L, outroEndMs = null))
        assertEquals(
            NextEpisodeTrigger.ENDING,
            trigger(EPISODE_MS - 1_000L, outroStartMs = 30_000L, outroEndMs = null),
        )
    }

    /**
     * A window that never reaches the end of the file is not this episode's credits, so the offer
     * waits for the tail instead of arriving in the fifth minute. See [outroWindowIsCredits] for
     * the AniSkip row that made this necessary.
     */
    @Test
    fun anOutroFromTheMiddleOfTheEpisodeIsNotUsed() {
        val outroStart = 286_665L
        val outroEnd = 376_665L
        assertEquals(
            NextEpisodeTrigger.NONE,
            trigger(5 * 60_000L, outroStartMs = outroStart, outroEndMs = outroEnd),
        )
        assertEquals(
            NextEpisodeTrigger.ENDING,
            trigger(EPISODE_MS - 1_000L, outroStartMs = outroStart, outroEndMs = outroEnd),
        )
    }

    /** Some payloads carry a zero-length or inverted window; neither locates an outro. */
    @Test
    fun aWindowThatDoesNotRunForwardsIsNotUsed() {
        assertEquals(NextEpisodeTrigger.NONE, trigger(60_000L, outroStartMs = 90_000L, outroEndMs = 90_000L))
        assertEquals(NextEpisodeTrigger.NONE, trigger(60_000L, outroStartMs = 90_000L, outroEndMs = 30_000L))
        assertEquals(
            NextEpisodeTrigger.ENDING,
            trigger(EPISODE_MS - 1_000L, outroStartMs = EPISODE_MS, outroEndMs = EPISODE_MS - 60_000L),
        )
    }

    /** An unknown duration is the state a stream is in before it has loaded; offer nothing. */
    @Test
    fun anUnknownDurationOffersNothing() {
        assertEquals(NextEpisodeTrigger.NONE, trigger(60_000L, durationMs = 0L))
        assertEquals(NextEpisodeTrigger.NONE, trigger(60_000L, durationMs = -1L))
    }

    /** A live position never precedes the start of the file; a negative one is a bad report. */
    @Test
    fun aNegativePositionOffersNothing() {
        assertEquals(NextEpisodeTrigger.NONE, trigger(-1L))
    }
}
