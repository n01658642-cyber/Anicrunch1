package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue cache is keyed by the provider list that built it. Before that, adding a provider
 * left every install answering the new question out of the old answer: the newcomer was registered,
 * cached catalogues from before it kept being served, and it was never once asked.
 */
class CatalogFingerprintTest {
    @Test
    fun `adding a provider changes the fingerprint`() {
        val before = listOf("anibd", "anikoto", "rareanimes")

        assertNotEquals(
            ProviderCatalog.fingerprint(before),
            ProviderCatalog.fingerprint(before + "newcomer"),
        )
    }

    @Test
    fun `hiding adult providers changes the fingerprint`() {
        // The two lists differ only by the adult-only entries, which the old key could not see —
        // so toggling the setting kept serving the catalogue built for the other answer.
        assertNotEquals(
            ProviderCatalog.fingerprint(ProviderCatalog.anivexaProvidersFor(hideAdult = true)),
            ProviderCatalog.fingerprint(ProviderCatalog.anivexaProvidersFor(hideAdult = false)),
        )
    }

    @Test
    fun `order does not change the fingerprint`() {
        // Provider order is a preference ranking, not part of what the catalogue contains, and it
        // gets reshuffled. Reordering must not throw away every cached catalogue on the device.
        val providers = ProviderCatalog.anivexaProvidersFor(hideAdult = true)

        assertEquals(
            ProviderCatalog.fingerprint(providers),
            ProviderCatalog.fingerprint(providers.reversed()),
        )
    }

    @Test
    fun `fingerprint stays short enough for a key`() {
        val stamp = ProviderCatalog.fingerprint(ProviderCatalog.anivexaProvidersFor(hideAdult = true))

        assertTrue(stamp, stamp.isNotEmpty() && stamp.length <= 8)
        assertTrue(stamp, stamp.none { it == ':' })
    }
}
