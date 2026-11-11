package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A refused media request has to be describable without carrying the credential that would have
 * granted it. These bundles get shared.
 */
class HttpFailureSummaryTest {
    @Test
    fun `a signed expiring segment is described without its token`() {
        val summary = httpFailureSummary(
            responseCode = 403,
            url = "https://vivibebe.site/stream/abc/def/seg-014.ts?token=SECRETVALUE&expires=1755500000",
        )

        assertTrue(summary, "kind=segment" in summary)
        assertTrue(summary, "file=seg-014.ts" in summary)
        assertTrue(summary, "params=token,expires" in summary)
        assertTrue(summary, "signed=true expiring=true" in summary)
        // The point of the whole exercise.
        assertFalse(summary, "SECRETVALUE" in summary)
        assertFalse(summary, "1755500000" in summary)
    }

    @Test
    fun `a playlist refusal is told apart from a segment one`() {
        val summary = httpFailureSummary(403, "https://vivibebe.site/stream/abc/index.m3u8")

        assertTrue(summary, "kind=playlist" in summary)
        assertTrue(summary, "params=none signed=false expiring=false" in summary)
    }

    @Test
    fun `an unsigned refusal says the host simply refused`() {
        // No token and no expiry: nothing timed out and nothing was minted elsewhere, so the
        // refusal is about us — headers, address or referer — not about the link.
        val summary = httpFailureSummary(403, "https://vivibebe.site/stream/abc/seg-1.ts")

        assertTrue(summary, "signed=false expiring=false" in summary)
    }

    @Test
    fun `the CDN's own account of itself is carried, briefly`() {
        val summary = httpFailureSummary(
            responseCode = 403,
            url = "https://vivibebe.site/a/b.ts",
            headers = mapOf(
                "Server" to listOf("cloudflare"),
                "CF-Ray" to listOf("8f2a1b"),
                "Set-Cookie" to listOf("session=nope"),
            ),
        )

        assertTrue(summary, "server=cloudflare" in summary)
        assertTrue(summary, "cf-ray=8f2a1b" in summary)
        // Only the named headers; a response's cookies are not diagnostics.
        assertFalse(summary, "nope" in summary)
    }

    @Test
    fun `a key request is named as one`() {
        assertTrue("kind=key" in httpFailureSummary(403, "https://x.site/a/key.bin"))
    }

    @Test
    fun `a malformed url still produces something`() {
        assertEquals(
            "code=0 kind=unknown host=unknown file=/ params=none signed=false expiring=false",
            httpFailureSummary(0, ""),
        )
    }
}
