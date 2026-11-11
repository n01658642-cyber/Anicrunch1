package com.anilili.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiScaleTest {
    /**
     * An unreadable television is not something a viewer can navigate their way out of, so an
     * unknown or corrupt stored value has to land on the size the app shipped with rather than on
     * whichever entry happens to be first.
     */
    @Test
    fun `an unknown stored size falls back to medium`() {
        assertEquals(UiScale.MEDIUM, UiScale.fromStored(null))
        assertEquals(UiScale.MEDIUM, UiScale.fromStored(""))
        assertEquals(UiScale.MEDIUM, UiScale.fromStored("enormous"))
    }

    @Test
    fun `every size survives a round trip through storage`() {
        UiScale.entries.forEach { scale ->
            assertEquals(scale, UiScale.fromStored(scale.storedValue))
        }
    }

    /** Medium must be the identity, or an untouched install would resize itself on upgrade. */
    @Test
    fun `medium leaves the interface exactly as it was`() {
        assertEquals(1f, UiScale.MEDIUM.factor, 0f)
    }

    @Test
    fun `sizes are ordered smallest to largest`() {
        val factors = UiScale.entries.map(UiScale::factor)
        assertEquals(factors.sorted(), factors)
        assertTrue(factors.distinct().size == factors.size)
    }
}
