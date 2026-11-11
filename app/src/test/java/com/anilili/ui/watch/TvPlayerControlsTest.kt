package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class TvPlayerControlsTest {
    @Test
    fun backRetiresTheControlRowWhileItIsTheTopLayer() {
        assertEquals(
            TvPlayerBackTarget.CONTROLS,
            tvPlayerBackTarget(isTv = true, playerOwnsRemote = true, controlsVisible = true),
        )
    }

    @Test
    fun backLeavesThePlayerOnceNothingIsShowing() {
        assertEquals(
            TvPlayerBackTarget.NONE,
            tvPlayerBackTarget(isTv = true, playerOwnsRemote = true, controlsVisible = false),
        )
    }

    @Test
    fun panelsAreRetiredBeforeTheControlRowUnderneathThem() {
        // The control row stays visible behind every panel, so each of these has to outrank it or
        // Back would close the row out from under an open panel.
        assertEquals(
            TvPlayerBackTarget.SETTINGS,
            tvPlayerBackTarget(
                isTv = true,
                playerOwnsRemote = true,
                settingsVisible = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            TvPlayerBackTarget.CAPTIONS,
            tvPlayerBackTarget(
                isTv = true,
                playerOwnsRemote = true,
                captionsVisible = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            TvPlayerBackTarget.EPISODE_DRAWER,
            tvPlayerBackTarget(
                isTv = true,
                playerOwnsRemote = true,
                episodeDrawerVisible = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun theCaptionDialogOutranksThePanelThatOpenedIt() {
        assertEquals(
            TvPlayerBackTarget.CAPTION_APPEARANCE,
            tvPlayerBackTarget(
                isTv = true,
                playerOwnsRemote = true,
                captionAppearanceVisible = true,
                captionsVisible = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun openSubtitlesBrowserIsTheTopmostPlayerLayer() {
        assertEquals(
            TvPlayerBackTarget.OPEN_SUBTITLES,
            tvPlayerBackTarget(
                isTv = true,
                playerOwnsRemote = true,
                openSubtitlesVisible = true,
                captionsVisible = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun anEpisodeChangeUnderAVisibleRowLeavesTheRemoteInTheRow() {
        // The row survives the swap, so the ring stays on the control the viewer was using —
        // pressing Next twice has to skip two episodes.
        assertEquals(
            TvEpisodeChangeFocus.HOLD,
            tvEpisodeChangeFocus(
                isTv = true,
                playerOwnsRemote = true,
                controlsVisible = true,
                controlsFocused = true,
            ),
        )
    }

    @Test
    fun aRowLeftWithNoFocusedControlIsAimedAtAgain() {
        // The dead row this exists for: on screen, and unreachable from the picture behind it.
        assertEquals(
            TvEpisodeChangeFocus.CONTROLS,
            tvEpisodeChangeFocus(
                isTv = true,
                playerOwnsRemote = true,
                controlsVisible = true,
                controlsFocused = false,
            ),
        )
    }

    @Test
    fun aRowThatIsNotShowingHandsTheRemoteBackToThePicture() {
        assertEquals(
            TvEpisodeChangeFocus.SURFACE,
            tvEpisodeChangeFocus(isTv = true, playerOwnsRemote = true, controlsVisible = false),
        )
    }

    @Test
    fun anOpenPanelKeepsTheRemoteItWasGiven() {
        // Every panel is drawn over a row that stays visible underneath it, so the row would
        // otherwise claim focus back the moment a source failover swapped the stream under it.
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvEpisodeChangeFocus(
                isTv = true,
                playerOwnsRemote = true,
                panelVisible = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun aScreenReaderIsLeftOnWhateverItWasReading() {
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvEpisodeChangeFocus(
                isTv = true,
                playerOwnsRemote = true,
                screenReaderActive = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun touchBuildsAndTheWindowedPlayerMoveNoFocusOnAnEpisodeChange() {
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvEpisodeChangeFocus(isTv = false, playerOwnsRemote = true, controlsVisible = false),
        )
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvEpisodeChangeFocus(isTv = true, playerOwnsRemote = false, controlsVisible = false),
        )
    }

    @Test
    fun pressingNextEpisodeOnTheCardHandsTheRowBackTheRemote() {
        // The bug this exists for: the up-next card and the skip action are focusable and vanish on
        // the press, so the ring went with them and the lit row underneath answered nothing.
        assertEquals(
            TvEpisodeChangeFocus.CONTROLS,
            tvOverlayDismissFocus(
                isTv = true,
                playerOwnsRemote = true,
                controlsVisible = true,
                controlsFocused = false,
            ),
        )
    }

    @Test
    fun anOverlayThatExpiredWhileTheRowHeldTheRemoteMovesNothing() {
        // "Skip Intro" timing out, or the card being superseded, while the viewer's ring never left
        // the row: taking focus off the control under their thumb would be the fault in reverse.
        assertEquals(
            TvEpisodeChangeFocus.HOLD,
            tvOverlayDismissFocus(
                isTv = true,
                playerOwnsRemote = true,
                controlsVisible = true,
                controlsFocused = true,
            ),
        )
    }

    @Test
    fun anOverlayDismissedOverBarePictureHandsTheRemoteToThePicture() {
        assertEquals(
            TvEpisodeChangeFocus.SURFACE,
            tvOverlayDismissFocus(isTv = true, playerOwnsRemote = true, controlsVisible = false),
        )
    }

    @Test
    fun anOverlayDismissedUnderAnOpenPanelLeavesThePanelTheRemote() {
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvOverlayDismissFocus(
                isTv = true,
                playerOwnsRemote = true,
                panelVisible = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvOverlayDismissFocus(
                isTv = true,
                playerOwnsRemote = true,
                screenReaderActive = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun touchBuildsAndTheWindowedPlayerMoveNoFocusWhenAnOverlayGoes() {
        // A phone dismisses these with the finger that is already on the screen; there is no ring
        // to strand, and moving focus would only fight the touch controls.
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvOverlayDismissFocus(isTv = false, playerOwnsRemote = true, controlsVisible = true),
        )
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvOverlayDismissFocus(isTv = true, playerOwnsRemote = false, controlsVisible = true),
        )
    }

    @Test
    fun aPointerSeeksToWhereItPressedRatherThanNudgingFromThePosition() {
        // Half way along a 20 minute episode is ten minutes in, wherever playback happens to be.
        assertEquals(600_000L, tvPointerSeekMs(x = 500f, widthPx = 1000, durationMs = 1_200_000L))
        assertEquals(0L, tvPointerSeekMs(x = 0f, widthPx = 1000, durationMs = 1_200_000L))
    }

    @Test
    fun aPressPastEitherEndOfTheTrackStaysInsideTheEpisode() {
        // The band that answers the pointer is wider than the drawn track and a drag runs off it
        // entirely, so out-of-range positions arrive here as a matter of course.
        assertEquals(1_200_000L, tvPointerSeekMs(x = 4000f, widthPx = 1000, durationMs = 1_200_000L))
        assertEquals(0L, tvPointerSeekMs(x = -320f, widthPx = 1000, durationMs = 1_200_000L))
    }

    @Test
    fun anUnmeasuredBarOrAnUnknownLengthSeeksNowhere() {
        assertEquals(0L, tvPointerSeekMs(x = 500f, widthPx = 0, durationMs = 1_200_000L))
        assertEquals(0L, tvPointerSeekMs(x = 500f, widthPx = 1000, durationMs = 0L))
    }

    @Test
    fun touchBuildsAndTheWindowedPlayerClaimNothing() {
        assertEquals(
            TvPlayerBackTarget.NONE,
            tvPlayerBackTarget(
                isTv = false,
                playerOwnsRemote = true,
                settingsVisible = true,
                controlsVisible = true,
            ),
        )
        // Windowed on the Watch screen the remote drives the page, not the player, so Back has to
        // reach the screen.
        assertEquals(
            TvPlayerBackTarget.NONE,
            tvPlayerBackTarget(
                isTv = true,
                playerOwnsRemote = false,
                settingsVisible = true,
                controlsVisible = true,
            ),
        )
    }

    /**
     * A mouse tap leaves Compose in Touch mode, where the row's buttons refuse focus silently, so a
     * pointer reveal has to claim keyboard mode on its way in. Both players do it, which is what
     * this predicate exists to keep true — the fix reached the native player first and left every
     * embed provider, the majority of TV playback, with a row that lit up and answered nothing.
     */
    @Test
    fun aPointerRevealClaimsKeyboardModeSoTheRowCanTakeFocus() {
        assertEquals(true, claimsKeyboardModeOnPointerReveal(screenReaderActive = false))
    }

    /** TalkBack consumes the D-pad and moves its own focus, so there is no remote to rescue. */
    @Test
    fun aScreenReaderKeepsTouchModeAndItsOwnFocus() {
        assertEquals(false, claimsKeyboardModeOnPointerReveal(screenReaderActive = true))
    }

    @Test
    fun upWalksTheRowWhileThereIsSomethingAbove() {
        // Buttons to seek bar, seek bar to a floating skip action: neither closes the row.
        assertEquals(false, tvUpClosesControls(movedFocus = true, isRepeat = false))
    }

    @Test
    fun upFromTheTopOfTheRowClosesIt() {
        assertEquals(true, tvUpClosesControls(movedFocus = false, isRepeat = false))
    }

    @Test
    fun aHeldUpStopsAtTheTopRatherThanClosingTheRowItOpened() {
        assertEquals(false, tvUpClosesControls(movedFocus = false, isRepeat = true))
    }

    @Test
    fun aPressAtALitRowThatLostTheRingPutsTheRingBack() {
        // The Fire TV report: after Next the row stayed up with the ring elsewhere, and every press
        // aimed at it did nothing while also keeping it from timing out.
        assertEquals(true, tvPressReclaimsControls(controlsVisible = true, controlsFocused = false))
    }

    @Test
    fun aRowStillHoldingTheRingIsLeftToTheRing() {
        assertEquals(false, tvPressReclaimsControls(controlsVisible = true, controlsFocused = true))
    }

    @Test
    fun aHiddenRowIsNotReclaimedByAPressAtThePicture() {
        // Left and Right seek and Up, Down and OK open the row from there; none should be taken.
        assertEquals(false, tvPressReclaimsControls(controlsVisible = false, controlsFocused = false))
    }

    @Test
    fun theUpNextCardOrASkipKeepsTheRingTheyWereGiven() {
        assertEquals(
            false,
            tvPressReclaimsControls(controlsVisible = true, controlsFocused = false, overlayFocused = true),
        )
    }

    @Test
    fun anOpenPanelKeepsItsOwnPresses() {
        assertEquals(
            false,
            tvPressReclaimsControls(controlsVisible = true, controlsFocused = false, panelVisible = true),
        )
    }

    @Test
    fun aPlayerThatLostTheRingGivesItToTheRowWhenTheRowIsUp() {
        assertEquals(
            TvEpisodeChangeFocus.CONTROLS,
            tvFocusLostTarget(
                isTv = true,
                playerOwnsRemote = true,
                playerHasFocus = false,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun aPlayerThatLostTheRingGivesItToThePictureWhenTheRowIsDown() {
        assertEquals(
            TvEpisodeChangeFocus.SURFACE,
            tvFocusLostTarget(isTv = true, playerOwnsRemote = true, playerHasFocus = false),
        )
    }

    @Test
    fun aPlayerStillHoldingTheRingMovesNothing() {
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvFocusLostTarget(
                isTv = true,
                playerOwnsRemote = true,
                playerHasFocus = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun aLostRingIsLeftAloneForAPanelAScreenReaderOrAPlayerThatDoesNotOwnTheRemote() {
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvFocusLostTarget(isTv = true, playerOwnsRemote = true, playerHasFocus = false, panelVisible = true),
        )
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvFocusLostTarget(
                isTv = true,
                playerOwnsRemote = true,
                playerHasFocus = false,
                screenReaderActive = true,
            ),
        )
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvFocusLostTarget(isTv = true, playerOwnsRemote = false, playerHasFocus = false),
        )
        assertEquals(
            TvEpisodeChangeFocus.UNCHANGED,
            tvFocusLostTarget(isTv = false, playerOwnsRemote = true, playerHasFocus = false),
        )
    }
}
