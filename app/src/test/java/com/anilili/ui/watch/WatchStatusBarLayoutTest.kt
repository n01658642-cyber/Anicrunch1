package com.anilili.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStatusBarLayoutTest {
    @Test
    fun fullscreenLandscapeLetsTheStatusBarOverlayThePicture() {
        assertFalse(
            statusBarStaysOutsidePlayer(
                fullscreen = true,
                windowIsLandscape = true,
            ),
        )
    }

    @Test
    fun fullscreenPortraitKeepsItsInsetUntilRotationCompletes() {
        assertTrue(
            statusBarStaysOutsidePlayer(
                fullscreen = true,
                windowIsLandscape = false,
            ),
        )
    }

    @Test
    fun regularPagesAlwaysStayClearOfTheStatusBar() {
        assertTrue(statusBarStaysOutsidePlayer(fullscreen = false, windowIsLandscape = false))
        assertTrue(statusBarStaysOutsidePlayer(fullscreen = false, windowIsLandscape = true))
    }
}
