package com.anilili.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOfferTest {

    @Test
    fun `a new version prompts, with or without a build number`() {
        assertEquals(UpdateOffer.PROMPT, updateOffer("0.1.81", 83, "0.1.80", 82))
        // Every manifest published before build numbers: names alone, as always.
        assertEquals(UpdateOffer.PROMPT, updateOffer("0.1.81", null, "0.1.80", 82))
    }

    @Test
    fun `a rebuild of the installed version is offered quietly`() {
        // The case this exists for: 0.1.80 republished with fixes under a higher build number.
        assertEquals(UpdateOffer.QUIET, updateOffer("0.1.80", 82, "0.1.80", 81))
    }

    @Test
    fun `the same build, or no build number, is nothing new`() {
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.80", 82, "0.1.80", 82))
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.80", null, "0.1.80", 82))
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.79", null, "0.1.80", 82))
    }

    @Test
    fun `a lower build number is never offered, whatever the name says`() {
        // Android refuses a lower versionCode over a higher one, so this could only fail to install.
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.81", 80, "0.1.80", 82))
        assertEquals(UpdateOffer.NONE, updateOffer("0.1.80", 80, "0.1.80", 82))
    }

    @Test
    fun `only a new version or an important rebuild pops up unasked`() {
        assertTrue(promptsUnasked(UpdateOffer.PROMPT, important = false))
        assertTrue(promptsUnasked(UpdateOffer.QUIET, important = true))
        assertFalse(promptsUnasked(UpdateOffer.QUIET, important = false))
        // Important never makes an old or equal build worth offering.
        assertFalse(promptsUnasked(UpdateOffer.NONE, important = true))
    }

    @Test
    fun `a build number is only shown where it tells two builds apart`() {
        assertEquals("v0.1.80 (build 82)", versionLabel("0.1.80", 82))
        assertEquals("v0.1.81", versionLabel("0.1.81", null))
    }
}
