package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSystemBarsTest {
    @Test
    fun controlsBringTheStatusBarWithThem() {
        assertEquals(
            PlayerBars.STATUS_ONLY,
            playerBarsFor(
                immersive = true,
                controlsVisible = true,
                isTv = false,
                statusBarOverPlayer = true,
            ),
        )
        assertEquals(
            PlayerBars.NONE,
            playerBarsFor(
                immersive = true,
                controlsVisible = false,
                isTv = false,
                statusBarOverPlayer = true,
            ),
        )
    }

    @Test
    fun turningTheSettingOffLeavesThePictureAlone() {
        assertEquals(
            PlayerBars.NONE,
            playerBarsFor(
                immersive = true,
                controlsVisible = true,
                isTv = false,
                statusBarOverPlayer = false,
            ),
        )
    }

    @Test
    fun aTelevisionStaysImmersiveWhateverTheSettingSays() {
        assertEquals(
            PlayerBars.NONE,
            playerBarsFor(
                immersive = true,
                controlsVisible = true,
                isTv = true,
                statusBarOverPlayer = true,
            ),
        )
    }

    @Test
    fun leavingFullscreenHandsEveryBarBack() {
        assertEquals(
            PlayerBars.ALL,
            playerBarsFor(
                immersive = false,
                controlsVisible = false,
                isTv = false,
                statusBarOverPlayer = false,
            ),
        )
    }
}
