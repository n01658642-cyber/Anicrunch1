package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HindiProviderCatalogTest {
    @Test
    fun `Hindi anime providers are selectable native backends`() {
        assertTrue("animedekho" in ProviderCatalog.anivexaProviders)
        assertTrue("toonhub" in ProviderCatalog.anivexaProviders)
        assertTrue("rareanimes" in ProviderCatalog.anivexaProviders)
        assertEquals(ProviderCatalog.Source.ANIVEXA, ProviderCatalog.sourceOf("animedekho"))
        assertEquals(ProviderCatalog.Source.ANIVEXA, ProviderCatalog.sourceOf("toonhub"))
        assertEquals(ProviderCatalog.Source.ANIVEXA, ProviderCatalog.sourceOf("rareanimes"))
        assertEquals("AnimeDekho", ProviderCatalog.label("animedekho"))
        assertEquals("ToonHub4u", ProviderCatalog.label("toonhub"))
        assertEquals("RareAnimes", ProviderCatalog.label("rareanimes"))
        assertTrue(
            "RareAnimes must get a catalog lane before the slower scraper backlog",
            ProviderCatalog.anivexaProviders.indexOf("rareanimes") <
                ProviderCatalog.anivexaProviders.indexOf("anidbapp"),
        )
    }

    @Test
    fun `kartoons is a registered Hindi provider`() {
        assertTrue("kartoons" in ProviderCatalog.anivexaProviders)
        assertEquals(ProviderCatalog.Source.ANIVEXA, ProviderCatalog.sourceOf("kartoons"))
        assertEquals("Kartoons (Hindi)", ProviderCatalog.label("kartoons"))
    }

    @Test
    fun `kartoons is declared Hindi so an English viewer is never handed it automatically`() {
        assertEquals("Hindi", ProviderCatalog.declaredLanguage("kartoons"))
        assertEquals("kartoons", ProviderCatalog.providerForLanguage("Hindi"))
        val walk = listOf("auto", "anikoto", "kartoons", "zoko")
        assertEquals(listOf("auto", "anikoto", "zoko"), languageGatedAttemptOrder(walk, "auto", emptyList(), "English"))
        assertEquals("kartoons", languageGatedAttemptOrder(walk, "auto", emptyList(), "Hindi").first())
        assertTrue("picked by hand it still plays", "kartoons" in languageGatedAttemptOrder(walk, "kartoons", emptyList(), null))
    }

    @Test
    fun `kartoons urls are short-lived`() {
        assertTrue(ProviderCatalog.hasShortLivedStreamUrls("kartoons"))
        assertTrue(!ProviderCatalog.hasShortLivedStreamUrls("anikoto"))
    }
}
