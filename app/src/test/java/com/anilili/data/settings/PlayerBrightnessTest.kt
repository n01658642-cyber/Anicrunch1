package com.anilili.data.settings

import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved brightness is written straight onto a window's `screenBrightness`, so the values it can
 * hold are the ones that attribute accepts — and the one value that means "do not override at all"
 * has to be exactly the one the platform reserves for it.
 */
class PlayerBrightnessTest {

    @Test
    fun `follow-system is the window's own no-override value`() {
        // Anything else would be applied as a brightness, and BRIGHTNESS_OVERRIDE_NONE is negative:
        // a player told to use it would land on the floor and open dark on a fresh install.
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            PLAYER_BRIGHTNESS_FOLLOW_SYSTEM,
            0f,
        )
    }

    @Test
    fun `a saved level never blacks the screen out`() {
        // The gesture reaches 0 at the bottom of the swipe. Storing that verbatim would reopen the
        // player at a picture the viewer cannot see, with the only control that could undo it
        // invisible on it.
        assertEquals(MIN_PLAYER_BRIGHTNESS, coercePlayerBrightness(0f), 0f)
        assertEquals(MIN_PLAYER_BRIGHTNESS, coercePlayerBrightness(0.001f), 0f)
        assertTrue(MIN_PLAYER_BRIGHTNESS > 0f)
    }

    @Test
    fun `levels in range are kept exactly`() {
        assertEquals(0.5f, coercePlayerBrightness(0.5f), 0f)
        assertEquals(1f, coercePlayerBrightness(1f), 0f)
        assertEquals(1f, coercePlayerBrightness(4f), 0f)
    }

    @Test
    fun `a negative or NaN preference falls back to the system brightness`() {
        // NaN passes coerceIn untouched — every comparison against it is false — and reaches the
        // window as a brightness the platform cannot use.
        assertEquals(PLAYER_BRIGHTNESS_FOLLOW_SYSTEM, coercePlayerBrightness(Float.NaN), 0f)
        assertEquals(PLAYER_BRIGHTNESS_FOLLOW_SYSTEM, coercePlayerBrightness(-3f), 0f)
    }
}
