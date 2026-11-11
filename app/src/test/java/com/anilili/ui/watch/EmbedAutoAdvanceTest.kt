package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

private const val EPISODE_MS = 24 * 60_000L

/**
 * The embed player's auto-play, which used to be two settings' worth of gates away from working.
 *
 * It advanced only inside a measured outro window *and* only with auto-skip switched on, because it
 * was written inside the auto-skip effect and inherited that effect's early return. A source that
 * filed no timings never advanced at all — which is what "the next episode doesn't auto-play on
 * AniKoto" turned out to be, once AniKoto stopped handing over a manifest and started playing in
 * this player instead of the native one.
 */
class EmbedAutoAdvanceTest {

    private fun step(
        trigger: NextEpisodeTrigger,
        autoplay: Boolean = true,
        hasNextEpisode: Boolean = true,
        armed: Boolean = true,
        durationMs: Long = EPISODE_MS,
    ) = embedAutoAdvanceStep(autoplay, hasNextEpisode, armed, trigger, durationMs)

    @Test
    fun anEndingThatWasWatchedIntoTakesTheNextEpisode() {
        assertEquals(EmbedAutoAdvanceStep.ADVANCE, step(NextEpisodeTrigger.AFTER_CREDITS))
        assertEquals(EmbedAutoAdvanceStep.ADVANCE, step(NextEpisodeTrigger.ENDING))
    }

    /**
     * The tail of the file is the whole point of the change: it is the only trigger a source with no
     * outro timings will ever produce, and this player used to ignore it.
     */
    @Test
    fun theTailCountsEvenWithNoOutroFiled() {
        assertEquals(EmbedAutoAdvanceStep.ADVANCE, step(NextEpisodeTrigger.ENDING))
    }

    @Test
    fun aPositionShortOfTheEndingArmsRatherThanAdvancing() {
        assertEquals(EmbedAutoAdvanceStep.ARM, step(NextEpisodeTrigger.NONE))
    }

    /**
     * Resuming at 23:50 of a 24:00 file is inside the tail window on the first tick. Advancing there
     * would replace the episode the viewer had just chosen before they saw a frame of it, and unlike
     * the native player there is no countdown to say no to.
     */
    @Test
    fun anEndingFoundRatherThanReachedIsRefused() {
        assertEquals(EmbedAutoAdvanceStep.WAIT, step(NextEpisodeTrigger.ENDING, armed = false))
        assertEquals(EmbedAutoAdvanceStep.WAIT, step(NextEpisodeTrigger.AFTER_CREDITS, armed = false))
    }

    /**
     * A page with no video reports no duration, so it cannot claim to have seen a position before
     * the credits — arming on it would hand the very next tick an advance it has not earned.
     */
    @Test
    fun aPageWithNoVideoYetArmsNothing() {
        assertEquals(EmbedAutoAdvanceStep.WAIT, step(NextEpisodeTrigger.NONE, durationMs = 0L))
        assertEquals(EmbedAutoAdvanceStep.WAIT, step(NextEpisodeTrigger.NONE, durationMs = -1L))
    }

    @Test
    fun autoPlayOffAdvancesNothing() {
        assertEquals(EmbedAutoAdvanceStep.WAIT, step(NextEpisodeTrigger.ENDING, autoplay = false))
    }

    /** The last episode of a season has nothing to advance to. */
    @Test
    fun aFinaleAdvancesNothing() {
        assertEquals(EmbedAutoAdvanceStep.WAIT, step(NextEpisodeTrigger.ENDING, hasNextEpisode = false))
    }

    /**
     * Arming is not conditional on the settings, so switching auto-play on during an episode gets it
     * at the end of *that* episode. Gating the arm as well would silently cost the viewer one.
     */
    @Test
    fun theClockIsWatchedEvenWhileAutoPlayIsOff() {
        assertEquals(
            EmbedAutoAdvanceStep.ARM,
            step(NextEpisodeTrigger.NONE, autoplay = false, hasNextEpisode = false, armed = false),
        )
    }

    /**
     * Credits filed to the last frame: the page has no end-of-file event, so the episode has to
     * advance from a position. It is held through the credits (which arms it) and taken in the
     * final seconds — never at the start of the credits, which is where it used to go.
     */
    @Test
    fun creditsToTheLastFrameStillAdvanceAtTheEnd() {
        fun at(positionMs: Long) = nextEpisodeTrigger(
            positionMs = positionMs,
            durationMs = EPISODE_MS,
            outroStartMs = 22 * 60_000L,
            outroEndMs = EPISODE_MS,
        )
        assertEquals(EmbedAutoAdvanceStep.ARM, step(at(22 * 60_000L), armed = false))
        assertEquals(EmbedAutoAdvanceStep.ADVANCE, step(at(EPISODE_MS - 1_000L)))
    }
}
