package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class AnivexaCatalogPolicyTest {
    @Test
    fun `catalog fan out follows device memory pressure`() {
        assertEquals(2, providerCatalogParallelism(isLowRamDevice = true, largeMemoryClassMb = 512))
        assertEquals(2, providerCatalogParallelism(isLowRamDevice = false, largeMemoryClassMb = 192))
        assertEquals(2, providerCatalogParallelism(isLowRamDevice = false, largeMemoryClassMb = 256))
        assertEquals(4, providerCatalogParallelism(isLowRamDevice = false, largeMemoryClassMb = 512))
        assertEquals(4, providerCatalogParallelism(isLowRamDevice = false, largeMemoryClassMb = 0))
    }

    @Test
    fun `an ordinary modern phone is not treated as a constrained device`() {
        // The reason this reads the large heap class. A Samsung SM-A366W reports memoryClass=256
        // and largeMemoryClass=512 with isLowRamDevice=false; the app sets largeHeap, so 512 is
        // the number that describes it. Read the other one and it got two lanes, sixteen providers
        // never finished inside the budget, and half the server list went missing for two hours.
        assertEquals(4, providerCatalogParallelism(isLowRamDevice = false, largeMemoryClassMb = 512))
    }

    @Test
    fun `an old low-ram box stays on two lanes even though it claims a large heap`() {
        // And the reason isLowRamDevice still decides on its own. A 2018 amlogic Android 5.1 box
        // with 377MB free also reports largeMemoryClass=512 — the heap class says what the app may
        // allocate, never what the device can afford.
        assertEquals(2, providerCatalogParallelism(isLowRamDevice = true, largeMemoryClassMb = 512))
    }
}
