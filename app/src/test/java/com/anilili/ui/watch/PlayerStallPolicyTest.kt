package com.anilili.ui.watch

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStallPolicyTest {
    @Test
    fun `playing stream fails over after prolonged buffering`() {
        assertFalse(shouldFailOverStalledPlayback(true, Player.STATE_BUFFERING, true, 19_999, false))
        assertTrue(shouldFailOverStalledPlayback(true, Player.STATE_BUFFERING, true, 20_000, false))
    }

    @Test
    fun `offline paused ready and already handled streams never fail over`() {
        assertFalse(shouldFailOverStalledPlayback(false, Player.STATE_BUFFERING, true, 20_000, false))
        assertFalse(shouldFailOverStalledPlayback(true, Player.STATE_BUFFERING, false, 20_000, false))
        assertFalse(shouldFailOverStalledPlayback(true, Player.STATE_READY, true, 20_000, false))
        assertFalse(shouldFailOverStalledPlayback(true, Player.STATE_BUFFERING, true, 20_000, true))
    }
}
