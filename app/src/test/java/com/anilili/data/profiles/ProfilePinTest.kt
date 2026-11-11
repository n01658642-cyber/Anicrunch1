package com.anilili.data.profiles

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinTest {

    private fun profile(pinHash: String?) = LocalProfile(id = "p", name = "Test", pinHash = pinHash)

    @Test
    fun `the right pin opens a locked profile and a wrong one does not`() {
        val locked = profile(Profiles.hashPin("1234"))
        assertTrue(locked.locked)
        assertTrue(Profiles.pinMatches(locked, "1234"))
        assertFalse(Profiles.pinMatches(locked, "4321"))
        assertFalse(Profiles.pinMatches(locked, "0000"))
    }

    @Test
    fun `a profile without a lock opens with anything`() {
        val open = profile(null)
        assertFalse(open.locked)
        assertTrue(Profiles.pinMatches(open, "9999"))
    }

    @Test
    fun `the stored value is salted, not the pin`() {
        val a = Profiles.hashPin("1234")
        val b = Profiles.hashPin("1234")
        assertNotEquals(a, b)
    }

    // The master code itself is deliberately not written here: only its hash ships.
    @Test
    fun `the master code rejects ordinary pins and guesses`() {
        assertFalse(Profiles.masterCodeMatches(""))
        assertFalse(Profiles.masterCodeMatches("1234"))
        assertFalse(Profiles.masterCodeMatches("00000000"))
        assertFalse(Profiles.masterCodeMatches("12345678"))
    }
}
