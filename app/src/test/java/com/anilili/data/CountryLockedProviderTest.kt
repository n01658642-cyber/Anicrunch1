package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AnimeKhor is a donghua catalogue and holds no Japanese anime at all — searching it for a Japanese
 * title returns "Not Found". Left ungated it would take one of the catalogue's limited lanes on
 * every title in the app to learn that, so it must drop out for anything that is not `CN`.
 */
class CountryLockedProviderTest {
    private val everything = ProviderCatalog.anivexaProvidersFor(hideAdult = false)

    @Test
    fun `animekhor is offered for donghua`() {
        assertTrue("animekhor" in ProviderCatalog.providersForCountry(everything, "CN"))
    }

    @Test
    fun `animekhor drops out for anime and aeni`() {
        assertFalse("animekhor" in ProviderCatalog.providersForCountry(everything, "JP"))
        assertFalse("animekhor" in ProviderCatalog.providersForCountry(everything, "KR"))
    }

    @Test
    fun `no other provider is narrowed away`() {
        val japanese = ProviderCatalog.providersForCountry(everything, "JP")

        assertEquals(everything - "animekhor", japanese)
    }

    @Test
    fun `an unknown country keeps every provider`() {
        // countryOfOrigin is absent from every Media cached before the field was requested, and
        // hiding a server for the life of that cache is worse than one wasted search.
        assertEquals(everything, ProviderCatalog.providersForCountry(everything, null))
        assertEquals(everything, ProviderCatalog.providersForCountry(everything, ""))
    }

    @Test
    fun `the country match is case insensitive`() {
        assertTrue("animekhor" in ProviderCatalog.providersForCountry(everything, "cn"))
    }
}
