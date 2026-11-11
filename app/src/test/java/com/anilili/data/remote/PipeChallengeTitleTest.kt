package com.anilili.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeChallengeTitleTest {

    @Test
    fun `cloudflare interstitial titles are recognised`() {
        assertTrue(looksLikePipeChallengeTitle("Just a moment..."))
        assertTrue(looksLikePipeChallengeTitle("just a moment…"))
        assertTrue(looksLikePipeChallengeTitle("Attention Required! | Cloudflare"))
        assertTrue(looksLikePipeChallengeTitle("  Checking your browser before accessing  "))
        assertTrue(looksLikePipeChallengeTitle("www.miruro.tv | Performing security verification"))
        assertTrue(looksLikePipeChallengeTitle("DDoS-Guard"))
    }

    @Test
    fun `a real miruro page is not mistaken for a challenge`() {
        assertFalse(looksLikePipeChallengeTitle("Miruro"))
        assertFalse(looksLikePipeChallengeTitle("Watch Frieren: Beyond Journey's End"))
    }

    @Test
    fun `a page with no title yet is left to the dom probe rather than assumed challenged`() {
        assertFalse(looksLikePipeChallengeTitle(null))
        assertFalse(looksLikePipeChallengeTitle(""))
        assertFalse(looksLikePipeChallengeTitle("   "))
    }
}
