package com.anilili.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchBackArrowTest {
    @Test
    fun playingWithTheControlsHiddenTakesTheArrowWithThem() {
        assertFalse(
            windowedBackArrowIsVisible(
                fullscreen = false,
                isTv = false,
                hasStream = true,
                loadingOverlayShowing = false,
                controlsVisible = false,
            ),
        )
    }

    @Test
    fun playingWithTheControlsUpShowsTheArrowBesideThem() {
        assertTrue(
            windowedBackArrowIsVisible(
                fullscreen = false,
                isTv = false,
                hasStream = true,
                loadingOverlayShowing = false,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun fullscreenNeverDrawsIt() {
        assertFalse(
            windowedBackArrowIsVisible(
                fullscreen = true,
                isTv = false,
                hasStream = true,
                loadingOverlayShowing = false,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun theLoadingOverlayKeepsTheOnlyWayOutOnScreen() {
        assertTrue(
            windowedBackArrowIsVisible(
                fullscreen = false,
                isTv = false,
                hasStream = true,
                loadingOverlayShowing = true,
                controlsVisible = false,
            ),
        )
    }

    @Test
    fun aTitleWithNoStreamKeepsIt() {
        assertTrue(
            windowedBackArrowIsVisible(
                fullscreen = false,
                isTv = false,
                hasStream = false,
                loadingOverlayShowing = false,
                controlsVisible = false,
            ),
        )
    }

    @Test
    fun theTvPaneKeepsItRegardlessOfWhatTheLastPlayerReported() {
        assertTrue(
            windowedBackArrowIsVisible(
                fullscreen = false,
                isTv = true,
                hasStream = true,
                loadingOverlayShowing = false,
                controlsVisible = false,
            ),
        )
    }
}
