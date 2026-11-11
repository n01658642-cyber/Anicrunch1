package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server the viewer switched off has to disappear from the *request*, not just from the picker.
 * Left in the fan-out it still takes a semaphore lane and a round trip on every title — which is
 * most of what someone is asking for when they turn off a server that never works for them.
 */
class DisabledProviderTest {
    private val everything = ProviderCatalog.anivexaProvidersFor(hideAdult = false)

    @Test
    fun `a disabled provider is not asked`() {
        val asked = ProviderCatalog.enabledProviders(everything, setOf("anizone"))

        assertFalse("anizone" in asked)
        assertEquals(everything - "anizone", asked)
    }

    @Test
    fun `an empty set changes nothing`() {
        assertEquals(everything, ProviderCatalog.enabledProviders(everything, emptySet()))
    }

    @Test
    fun `names are matched in the catalog's own case`() {
        // The store lowercases what it saves, but a catalogue name arrives however its backend
        // spelled it, and a case mismatch would silently keep a rejected server in the fan-out.
        assertFalse("anizone" in ProviderCatalog.enabledProviders(listOf("AniZone"), setOf("anizone")))
    }

    @Test
    fun `a name that is not a provider narrows nothing away`() {
        // A server can be dropped from the catalog in a later release while an install still holds
        // its name; that must not disturb the servers that are still here.
        assertEquals(everything, ProviderCatalog.enabledProviders(everything, setOf("animeshqip")))
    }

    @Test
    fun `switching a server off is its own cached catalogue`() {
        // The Anivexa catalogue is keyed by the list that was asked, so the answer that excluded a
        // server cannot be served as the answer that included it — and switching it back on lands
        // on the entry it already had rather than refetching sixteen providers.
        val withAll = ProviderCatalog.fingerprint(everything)
        val without = ProviderCatalog.fingerprint(ProviderCatalog.enabledProviders(everything, setOf("kaa")))

        assertNotEquals(withAll, without)
        assertEquals(withAll, ProviderCatalog.fingerprint(ProviderCatalog.enabledProviders(everything, emptySet())))
    }

    @Test
    fun `every selectable server can be switched off`() {
        // The picker offers `selectableProviders`; if one of those names could not be matched back
        // against a catalogue name, its switch would be inert with no sign of why.
        val selectable = ProviderCatalog.selectableProviders(hideAdult = false)

        assertTrue(selectable.isNotEmpty())
        assertEquals(emptyList<String>(), ProviderCatalog.enabledProviders(selectable, selectable.toSet()))
    }
}
