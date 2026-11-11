package com.anilili.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCacheBypassGateTest {
    @Test
    fun `recovery bypass remains active until playback settles or leaves`() {
        val gate = PlaybackCacheBypassGate()

        assertFalse(gate.isActive())
        assertTrue(gate.begin())
        assertTrue(gate.isActive())
        assertFalse(gate.begin())
        assertTrue(gate.end())
        assertFalse(gate.isActive())
        assertFalse(gate.end())
    }
}
