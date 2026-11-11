package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockFormatTest {
    @Test
    fun `an explicit choice ignores the device setting`() {
        assertTrue(ClockFormat.TWENTY_FOUR_HOUR.uses24Hour(systemUses24Hour = false))
        assertFalse(ClockFormat.TWELVE_HOUR.uses24Hour(systemUses24Hour = true))
    }

    @Test
    fun `system follows the device setting`() {
        assertTrue(ClockFormat.SYSTEM.uses24Hour(systemUses24Hour = true))
        assertFalse(ClockFormat.SYSTEM.uses24Hour(systemUses24Hour = false))
    }

    @Test
    fun `an unset or unknown stored value follows the device`() {
        assertEquals(ClockFormat.SYSTEM, ClockFormat.fromStored(null))
        assertEquals(ClockFormat.SYSTEM, ClockFormat.fromStored(""))
        // The app shipped with no such setting at all; a restore from a newer build onto an older
        // one, or a hand-edited preference, must not land on a format nobody chose.
        assertEquals(ClockFormat.SYSTEM, ClockFormat.fromStored("h:mm a"))
    }

    @Test
    fun `every stored value round-trips`() {
        ClockFormat.entries.forEach { format ->
            assertEquals(format, ClockFormat.fromStored(format.storedValue))
        }
    }
}
