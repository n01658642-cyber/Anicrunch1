package com.anilili.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Miruro's `episodes` response names native providers only, so the iframe embeds it re-hosts have
 * to be derived the way the site derives them or they are simply absent from the app's picker.
 *
 * Measured on 2026-09-06 for Bleach ep 7 (AniList 185874): the site listed `bees`, `hops`,
 * `allydlh`, `bonkdls`, `bonkdlh`, `nunembedh`, `bunembeds`, `twinembedh` and `twinembeds`, while
 * the pipe's episode lists named only bonk, kiwi, hop, ally, pewe and bee — the app could not
 * offer the four embed entries at all. Asking `sources` for `bun` and `twin` with bee's and bonk's
 * own episode ids answered 200 with streams, which is what makes deriving them from the parent
 * correct rather than a guess.
 */
class EmbedProviderDerivationTest {
    @Test
    fun eachEmbedIsDerivedFromTheNativeProviderItRehosts() {
        assertEquals(listOf("nun"), ProviderCatalog.embedChildrenOf("ally"))
        assertEquals(listOf("bun"), ProviderCatalog.embedChildrenOf("bee"))
        assertEquals(listOf("twin"), ProviderCatalog.embedChildrenOf("bonk"))
        assertEquals(listOf("cog"), ProviderCatalog.embedChildrenOf("moo"))
    }

    @Test
    fun derivedEmbedsAreEmbedsAndTheirParentsStayNative() {
        listOf("nun", "bun", "twin", "cog").forEach {
            assertTrue("$it should route as an embed", ProviderCatalog.isEmbed(it))
        }
        listOf("ally", "bee", "bonk", "moo").forEach {
            assertTrue("$it should stay native", ProviderCatalog.isNative(it))
        }
    }

    /**
     * telli is the one embed Miruro's live map marks `visible: false`. Deriving it would invent a
     * server the site has retired, but it must still classify as an embed so an old saved
     * favourite keeps routing to the WebView player rather than Media3.
     */
    @Test
    fun theEmbedMiruroRetiredIsNotDerivedButStillRoutesAsOne() {
        assertEquals(emptyList<String>(), ProviderCatalog.embedChildrenOf("kiwi"))
        assertTrue(ProviderCatalog.isEmbed("telli"))
    }

    @Test
    fun aProviderWithNoEmbedChildDerivesNothing() {
        listOf("hop", "pewe", "anibd", "nun").forEach {
            assertEquals(emptyList<String>(), ProviderCatalog.embedChildrenOf(it))
        }
    }
}
