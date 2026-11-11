package com.anilili.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Cloudflare check that never resolves strands the sign-in behind an eternal "verifying"
 * spinner — reported from a television where the same account logged in fine on a phone. These
 * tests pin the classification that decides when the recovery is allowed to act.
 */
class LoginChallengeClassifyTest {
    @Test
    fun `the interstitial is named by its title`() {
        assertEquals(
            LoginChallengeKind.INTERSTITIAL,
            classifyLoginChallenge("Just a moment...", turnstileTokenPresent = false, turnstileTokenChars = 0, challengeScaffold = true),
        )
        assertEquals(
            LoginChallengeKind.INTERSTITIAL,
            classifyLoginChallenge("Attention Required! | Cloudflare", turnstileTokenPresent = false, turnstileTokenChars = 0, challengeScaffold = true),
        )
    }

    @Test
    fun `a filled turnstile token means the check has passed`() {
        // Measured on a healthy client: the token lands ~816 chars before sign-in is touched.
        assertEquals(
            LoginChallengeKind.READY,
            classifyLoginChallenge("Log in to AniList · AniList", turnstileTokenPresent = true, turnstileTokenChars = 816, challengeScaffold = true),
        )
    }

    @Test
    fun `an embedded turnstile with an empty token is the stuck spinner`() {
        assertEquals(
            LoginChallengeKind.AWAITING_TOKEN,
            classifyLoginChallenge("Log in to AniList · AniList", turnstileTokenPresent = true, turnstileTokenChars = 0, challengeScaffold = true),
        )
    }

    @Test
    fun `challenge scaffolding with no token field is a full-page check`() {
        // The interstitial before its widget renders: title still blank or generic.
        assertEquals(
            LoginChallengeKind.INTERSTITIAL,
            classifyLoginChallenge("AniList", turnstileTokenPresent = false, turnstileTokenChars = 0, challengeScaffold = true),
        )
    }

    @Test
    fun `an ordinary page is left alone`() {
        assertEquals(
            LoginChallengeKind.NONE,
            classifyLoginChallenge("Log in to AniList · AniList", turnstileTokenPresent = false, turnstileTokenChars = 0, challengeScaffold = false),
        )
        assertEquals(LoginChallengeKind.NONE, classifyLoginChallenge(null, turnstileTokenPresent = false, turnstileTokenChars = 0, challengeScaffold = false))
    }

    @Test
    fun `only cloudflare session cookies are purged`() {
        // The stale-clearance loop: a clearance bound to an old WebView fingerprint is refused
        // forever, so the cure may drop these — and must not touch the service's own cookies.
        assertTrue(isCloudflareSessionCookie("cf_clearance"))
        assertTrue(isCloudflareSessionCookie("__cf_bm"))
        assertTrue(isCloudflareSessionCookie("__cfruid"))
        assertTrue(isCloudflareSessionCookie("cf_chl_2_x"))
        assertTrue(isCloudflareSessionCookie("CF_USE_OBSOLETE_v2"))
        assertFalse(isCloudflareSessionCookie("token"))
        assertFalse(isCloudflareSessionCookie("session"))
        assertFalse(isCloudflareSessionCookie("cftheme"))
    }
}

/**
 * The purge has to write its expiry against the scope the cookie was stored under. Cloudflare
 * keeps clearance on the registrable domain, and a cookie is keyed by domain as well as name — so
 * a host-only expiry quietly creates a second entry and leaves the original doing its job.
 */
class CookieDeletionScopeTest {
    @Test
    fun `a subdomain covers itself and every parent`() {
        assertEquals(
            listOf("", "; Domain=.login.anilist.co", "; Domain=.anilist.co"),
            cookieDeletionScopes("login.anilist.co"),
        )
    }

    @Test
    fun `a bare domain covers itself`() {
        assertEquals(listOf("", "; Domain=.anilist.co"), cookieDeletionScopes("anilist.co"))
    }

    @Test
    fun `the host-only scope always comes first`() {
        // A host-only cookie is only matched by a host-only expiry, so it must never be dropped.
        assertEquals("", cookieDeletionScopes("anilist.co").first())
        assertEquals("", cookieDeletionScopes("localhost").first())
    }

    @Test
    fun `a leading dot is not doubled`() {
        assertEquals(listOf("", "; Domain=.anilist.co"), cookieDeletionScopes(".anilist.co"))
    }

    @Test
    fun `something with no parent domain yields only the host scope`() {
        assertEquals(listOf(""), cookieDeletionScopes("localhost"))
        assertEquals(listOf(""), cookieDeletionScopes(""))
    }
}
