package com.anilili.ui.adaptive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmExitOnBackTest {
    @Test
    fun firstPressNeverExits() {
        assertFalse(exitConfirmed(promptedAtMillis = null, nowMillis = 10_000L))
    }

    @Test
    fun secondPressInsideTheWindowExits() {
        assertTrue(exitConfirmed(promptedAtMillis = 10_000L, nowMillis = 10_400L))
    }

    @Test
    fun theWindowIsOpenUntilItsLastMoment() {
        // The prompt is taken off screen at exactly this point, so a press timed with the last
        // frame of it has to still count — otherwise the app ignores a press it just invited.
        assertTrue(exitConfirmed(promptedAtMillis = 10_000L, nowMillis = 10_000L + ConfirmExitWindowMillis))
        assertFalse(
            exitConfirmed(promptedAtMillis = 10_000L, nowMillis = 10_001L + ConfirmExitWindowMillis),
        )
    }

    @Test
    fun aPressFromMinutesAgoDoesNotArmTheExit() {
        assertFalse(exitConfirmed(promptedAtMillis = 10_000L, nowMillis = 130_000L))
    }

    @Test
    fun aClockThatWentBackwardsIsNotAConfirmation() {
        assertFalse(exitConfirmed(promptedAtMillis = 10_000L, nowMillis = 9_000L))
    }
}
