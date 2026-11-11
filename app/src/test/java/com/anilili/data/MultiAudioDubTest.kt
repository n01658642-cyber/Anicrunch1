package com.anilili.data

import com.anilili.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A user reported picking English from the player's audio menu on a server the app calls
 * "sub only", and the app then switching servers whenever prefer-dub was on — deciding no dub
 * existed while an English track sat in the menu. These servers ship one file with several audio
 * tracks, and the ones whose catalog cannot say so are only ever listed under sub.
 */
class MultiAudioDubTest {

    @Test
    fun `a dub request on a server that cannot list one searches its sub catalog`() {
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("kaa", Category.DUB))
        // Case is provider-authored and has varied between catalogs.
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("KAA", Category.DUB))
    }

    @Test
    fun `a sub request is never redirected`() {
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("kaa", Category.SUB))
        assertEquals(Category.SUB, ProviderCatalog.dubCapableCategory("bonk", Category.SUB))
    }

    @Test
    fun `ordinary servers keep separate sub and dub catalogs`() {
        // Redirecting these would hand a viewer Japanese audio and call it a dub.
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("bonk", Category.DUB))
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("anibd", Category.DUB))
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("anikoto", Category.DUB))
    }

    @Test
    fun `a multi-audio server that can list a dub keeps its own dub catalog`() {
        // ReAnime counts subbed and dubbed separately (Gintama is 200/0) and only returns
        // dataType:"dub" servers when the count is non-zero, so its dub rows are real. Sending a
        // dub request to its sub rows dropped the a=1 audio-track index the site needs, and let a
        // fallback play Japanese from a title with no dub at all.
        assertEquals(Category.DUB, ProviderCatalog.dubCapableCategory("reanime", Category.DUB))
        // It still carries both tracks in one file, so the player is what chooses between them.
        assertEquals(true, "reanime" in ProviderCatalog.multiAudioProviders)
        assertEquals(true, "kaa" in ProviderCatalog.multiAudioProviders)
    }
}
