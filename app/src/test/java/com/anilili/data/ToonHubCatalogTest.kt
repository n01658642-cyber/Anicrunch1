package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToonHubCatalogTest {
    @Test
    fun `ToonHub is a selectable native source`() {
        assertTrue("toonhub" in ProviderCatalog.anivexaProviders)
        assertEquals(ProviderCatalog.Source.ANIVEXA, ProviderCatalog.sourceOf("toonhub"))
        assertEquals("ToonHub4u", ProviderCatalog.label("toonhub"))
    }
}
