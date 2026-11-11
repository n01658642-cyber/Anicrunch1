package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CatalogAvailabilityKeyTest {

    @Test
    fun `availability is cached per title, not per provider`() {
        // The disk cache holds 800 entries, 300 on a low-RAM device — which is what a TV box is —
        // and evicts the oldest. A key carrying the provider meant seventeen rows per title, so
        // twenty titles of browsing filled a TV's whole cache with availability and pushed out the
        // metadata that makes the rest of the app quick. One row per title is the fix, and this is
        // the guard: the key must not vary by provider.
        val key = availabilityCacheKey(11061)

        assertEquals("avail:v2:11061", key)
        listOf("anibd", "2dhive", "animeshqip").forEach { provider ->
            assertFalse("key must not carry $provider", key.contains(provider))
        }
    }

    @Test
    fun `each title gets its own row`() {
        assertFalse(availabilityCacheKey(1) == availabilityCacheKey(2))
    }
}
