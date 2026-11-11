package com.anilili.data.settings

import com.anilili.ui.watch.PlaybackSpeeds
import com.anilili.ui.watch.formatPlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPlaybackSpeedTest {
    @Test
    fun `the clamp matches the speeds the player actually offers`() {
        // The picker's options come from PlaybackSpeeds and the stored value is clamped by
        // SettingsStore. If the two ever drift, the picker offers a speed the store silently
        // rewrites to another one, and the settings row then shows a value no option is
        // selected on.
        assertEquals(MIN_PLAYBACK_SPEED, PlaybackSpeeds.min(), 0f)
        assertEquals(MAX_PLAYBACK_SPEED, PlaybackSpeeds.max(), 0f)
        assertTrue("normal speed must be selectable", DEFAULT_PLAYBACK_SPEED in PlaybackSpeeds)
    }

    @Test
    fun `every offered speed survives the clamp unchanged`() {
        PlaybackSpeeds.forEach { speed ->
            assertEquals(speed, coercePlaybackSpeed(speed), 0f)
        }
    }

    @Test
    fun `hold speed defaults to double and shares the full adjustable range`() {
        assertEquals(2f, DEFAULT_HOLD_PLAYBACK_SPEED, 0f)
        assertTrue(0.5f in PlaybackSpeeds)
        assertTrue(DEFAULT_HOLD_PLAYBACK_SPEED in PlaybackSpeeds)
        assertTrue(4f in PlaybackSpeeds)
    }

    @Test
    fun `the dedicated hold toggle preserves the old gesture opt out on migration`() {
        assertEquals(false, holdToSpeedSeed(stored = null, legacyPlayerGestures = false))
        assertEquals(true, holdToSpeedSeed(stored = null, legacyPlayerGestures = true))
    }

    @Test
    fun `the seed runs once and never follows the gesture switch afterwards`() {
        // The seed is the whole reason the two settings can be told apart. Reading it on every
        // load instead — applyPreferences re-runs on each write — would turn the brightness and
        // volume switch back into a master switch: turning the swipes off would take hold-to-speed
        // with it, in front of the viewer, in the same list.
        assertNull(holdToSpeedSeed(stored = true, legacyPlayerGestures = false))
        assertNull(holdToSpeedSeed(stored = false, legacyPlayerGestures = true))
        // A fresh install has neither key, so there is nothing to carry over and the default holds.
        assertNull(holdToSpeedSeed(stored = null, legacyPlayerGestures = null))
    }

    @Test
    fun `out of range values are clamped rather than reset to normal`() {
        // A value written by a build with a wider list, or a corrupted preference, should land on
        // the nearest usable speed. Falling back to normal instead would read as the setting
        // quietly refusing to hold anything fast.
        assertEquals(MAX_PLAYBACK_SPEED, coercePlaybackSpeed(9f), 0f)
        assertEquals(MIN_PLAYBACK_SPEED, coercePlaybackSpeed(0.05f), 0f)
        assertEquals(MIN_PLAYBACK_SPEED, coercePlaybackSpeed(0f), 0f)
        assertEquals(MIN_PLAYBACK_SPEED, coercePlaybackSpeed(-2f), 0f)
    }

    @Test
    fun `a NaN never reaches the player`() {
        // coerceIn passes NaN straight through — every comparison against it is false — and Media3
        // rejects a non-positive speed outright.
        assertEquals(DEFAULT_PLAYBACK_SPEED, coercePlaybackSpeed(Float.NaN), 0f)
    }

    @Test
    fun `the settings row reads the way the player's own speed control does`() {
        assertEquals("1x", DEFAULT_PLAYBACK_SPEED.formatPlaybackSpeed())
        assertEquals("2x", 2f.formatPlaybackSpeed())
        assertEquals("1.25x", 1.25f.formatPlaybackSpeed())
    }
}
