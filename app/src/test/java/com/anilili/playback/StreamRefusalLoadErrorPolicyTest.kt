package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fifteen seconds of retries on a 403 is what made an unplayable stream look like a broken app: the
 * switch to the provider's other stream only happens once the error goes fatal, and until then the
 * player sits blank. Every one of those seconds was spent re-asking a CDN that had already said no
 * — bonk's segments answer `{"code":1004,"error":"domain forbidden"}` to every request, with or
 * without a Referer, signed or not.
 */
class StreamRefusalLoadErrorPolicyTest {
    @Test
    fun `a refusal will not answer differently on the second ask`() {
        assertEquals(true, isStreamRefusal(403))
        assertEquals(true, isStreamRefusal(401))
    }

    @Test
    fun `a refusal with only other renditions left ends the stream`() {
        // Stopping the retries alone measured *slower* — 19 seconds against 15 — because Media3
        // lists 403 as eligible for a fallback and walked 1080p to 720p to 360p, all three of them
        // on the one host that had just refused us, reloading the playlist between each.
        assertEquals(true, refusalEndsStream(403, hasOtherServer = false))
        // A redundant stream is a different server, and that is worth trying.
        assertEquals(false, refusalEndsStream(403, hasOtherServer = true))
        // Everything else keeps Media3's own fallback rules, including 404 and 503.
        assertEquals(false, refusalEndsStream(404, hasOtherServer = false))
        assertEquals(false, refusalEndsStream(null, hasOtherServer = false))
    }

    @Test
    fun `an answer that could change keeps the default retry ladder`() {
        // A 404 can be one bad edge node and a 5xx can be a restart, so this narrows *when* the app
        // gives up on a stream, not whether it does. A load error that is not an HTTP status at all
        // — a timeout, a reset — arrives here as null and is not a refusal; what bounds those is
        // `deadLoadExhausted` below, on evidence rather than on the status.
        assertEquals(false, isStreamRefusal(404))
        assertEquals(false, isStreamRefusal(410))
        assertEquals(false, isStreamRefusal(500))
        assertEquals(false, isStreamRefusal(503))
        assertEquals(false, isStreamRefusal(null))
    }

    @Test
    fun `a load that has produced nothing twice running has been asked enough`() {
        // bee's segments on 1oe.lostproject.club close the TLS connection: 65 load errors over two
        // minutes fifty on a tester's television. bonk's vivibebe.site manifest timed out at 8.2s,
        // 11.1s, 22.0s, 32.3s and 48.0s on one episode. Zero bytes on every one of them.
        assertEquals(true, deadLoadExhausted(errorCount = 2, bytesLoaded = 0))
        assertEquals(true, deadLoadExhausted(errorCount = 5, bytesLoaded = 0))
    }

    @Test
    fun `the first failure still gets its retry`() {
        // The retry that recovers a bad edge node is the second attempt, not the fifth, so one is
        // kept. This is the case the 404-and-5xx carve-out above was protecting.
        assertEquals(false, deadLoadExhausted(errorCount = 1, bytesLoaded = 0))
    }

    @Test
    fun `a load that is actually moving keeps every retry it had`() {
        // A stream that has delivered bytes and then hiccups is a live connection having a bad
        // moment, which is exactly what Media3's growing backoff is for. Nothing here touches it.
        assertEquals(false, deadLoadExhausted(errorCount = 4, bytesLoaded = 1))
        assertEquals(false, deadLoadExhausted(errorCount = 9, bytesLoaded = 2_000_000))
    }
}
