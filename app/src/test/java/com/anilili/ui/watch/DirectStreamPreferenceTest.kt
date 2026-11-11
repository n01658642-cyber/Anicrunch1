package com.anilili.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectStreamPreferenceTest {

    @Test
    fun `a Fire stick prefers direct streams though it never sets the low-RAM flag`() {
        // AFTSSS as its bundle reported it: lowRam=false, memoryClass 128.
        assertTrue(prefersDirectStreams(isTv = true, isLowRamDevice = false, memoryClassMb = 128))
        assertTrue(prefersDirectStreams(isTv = true, isLowRamDevice = true, memoryClassMb = 512))
    }

    @Test
    fun `phones and roomy TVs keep choosing as before`() {
        // A phone with the same memory class plays an embed without a second browser to spare.
        assertFalse(prefersDirectStreams(isTv = false, isLowRamDevice = true, memoryClassMb = 128))
        assertFalse(prefersDirectStreams(isTv = true, isLowRamDevice = false, memoryClassMb = 384))
        // An unknown memory class is not read as a small one.
        assertFalse(prefersDirectStreams(isTv = true, isLowRamDevice = false, memoryClassMb = 0))
    }

    @Test
    fun `an automatic embed-only answer is set aside, a bounded number of times`() {
        assertTrue(shouldHoldEmbedForDirectStream(true, automaticChoice = true, wouldPlayEmbed = true, alreadyHeld = 0))
        assertTrue(shouldHoldEmbedForDirectStream(true, automaticChoice = true, wouldPlayEmbed = true, alreadyHeld = 1))
        assertFalse(
            shouldHoldEmbedForDirectStream(true, automaticChoice = true, wouldPlayEmbed = true, alreadyHeld = MAX_HELD_EMBEDS),
        )
    }

    @Test
    fun `a direct stream, a chosen server or a roomy device is played as it comes`() {
        assertFalse(shouldHoldEmbedForDirectStream(true, automaticChoice = true, wouldPlayEmbed = false, alreadyHeld = 0))
        assertFalse(shouldHoldEmbedForDirectStream(true, automaticChoice = false, wouldPlayEmbed = true, alreadyHeld = 0))
        assertFalse(shouldHoldEmbedForDirectStream(false, automaticChoice = true, wouldPlayEmbed = true, alreadyHeld = 0))
    }
}
