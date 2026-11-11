package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule that keeps a Spanish server out of an English viewer's automatic walk. */
class LanguageGatedAttemptOrderTest {
    private val walk = listOf("auto", "kiwi", "anibd", "animeav1", "zoko")

    private fun order(
        preferred: String = "auto",
        userRanked: List<String> = emptyList(),
        wanted: String? = null,
    ) = languageGatedAttemptOrder(walk, preferred, userRanked, wanted)

    @Test
    fun `an automatic walk never reaches a Spanish server nobody asked for`() {
        assertEquals(listOf("auto", "kiwi", "anibd", "zoko"), order())
    }

    @Test
    fun `a preferred language that is not Spanish still leaves it out`() {
        assertEquals(listOf("auto", "kiwi", "anibd", "zoko"), order(wanted = "English"))
    }

    @Test
    fun `asking for Spanish puts it first`() {
        assertEquals(listOf("animeav1", "auto", "kiwi", "anibd", "zoko"), order(wanted = "Spanish"))
    }

    @Test
    fun `picked by hand it is played wherever it sits`() {
        val picked = languageGatedAttemptOrder(listOf("animeav1", "kiwi", "anibd"), "animeav1", emptyList(), null)
        assertEquals(listOf("animeav1", "kiwi", "anibd"), picked)
    }

    @Test
    fun `ranked in Settings it stays in the walk`() {
        assertTrue("animeav1" in order(userRanked = listOf("animeav1")))
    }

    @Test
    fun `a server picked by hand still leads when Spanish is preferred`() {
        val walk = listOf("kiwi", "anibd", "animeav1")
        assertEquals(listOf("kiwi", "animeav1", "anibd"), languageGatedAttemptOrder(walk, "kiwi", emptyList(), "Spanish"))
    }

    @Test
    fun `the language is read from the catalogue`() {
        assertEquals("Spanish", ProviderCatalog.declaredLanguage("animeav1"))
        assertEquals("AnimeAV1 (Español)", ProviderCatalog.label("animeav1"))
        assertEquals(null, ProviderCatalog.declaredLanguage("kiwi"))
        assertEquals(ProviderCatalog.Source.ANIVEXA, ProviderCatalog.sourceOf("animeav1"))
        assertEquals("animeav1", ProviderCatalog.providerForLanguage("Spanish"))
        assertEquals(null, ProviderCatalog.providerForLanguage("English"))
        assertEquals(null, ProviderCatalog.providerForLanguage(null))
    }
}
