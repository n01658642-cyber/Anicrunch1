package com.anilili.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MegaPlay's `enc` blob and the manifest hidden in it.
 *
 * The ciphertext below is the live answer for Frieren episode 1 sub (AniList 154587, MegaPlay file
 * 13461), captured 2026-09-17 from `stream/getSourcesNew` — the day both of MegaPlay's routes
 * stopped carrying a `sources` object at all. It is pinned here rather than fetched because the
 * point of the test is the cipher, and a test that needs the network to tell you the key is still
 * right is a test that fails for the wrong reason.
 */
class MegaPlayEncTest {

    private val liveBlob =
        "wdeBruh3qqn_i5wUNnyaPcXqidp1UWP84FfPHzGyKXAz4mAVkH6j3DueswO2yXLWn8H-XMHNvbAo5Gsg7zIcFBuQ" +
            "I_zsUvMGI1gKwQsPTSHQHiF55R4BopgEQ-7jebQQ4C0Gu7YhaMucopp6d3Q8yAY9b5GdsSvPGq6CUn7SHyc"

    @Test
    fun theLiveBlobDecryptsToItsManifest() {
        assertEquals(
            """{"file":"https://fetch.nexabloom.top/anime/bb6d2babd7797d94d8f4a8600bc9b44e/""" +
                """b7d51fb7e838ee9b60dcdb34b953bc07/master.m3u8"}""",
            megaPlayDecryptSources(liveBlob),
        )
    }

    /**
     * The blob is base64url, not base64: the live payloads carry `-` and `_`, and a decoder that
     * only knows the standard alphabet fails on roughly every other episode rather than obviously.
     */
    @Test
    fun theBlobIsReadAsBase64Url() {
        assertTrue(liveBlob.contains('-') || liveBlob.contains('_'))
        assertTrue(megaPlayDecryptSources(liveBlob)!!.startsWith("""{"file":"""))
    }

    /**
     * A rotated key is the failure this has to survive without pretending it succeeded: returning
     * junk would put an unplayable URL in front of the player, where the same silence that hid the
     * last rotation would hide this one.
     */
    @Test
    fun aBlobThisKeyCannotOpenIsRefused() {
        assertNull(megaPlayDecryptSources("not-base64url-at-all!!"))
        assertNull(megaPlayDecryptSources(""))
        // The live blob with its last cipher block replaced — the length and the alphabet are still
        // right, so what refuses it is the cipher and not the shape.
        assertNull(megaPlayDecryptSources(liveBlob.dropLast(22) + "AAAAAAAAAAAAAAAAAAAAAA"))
    }

    /** The rewrite the CDN's refusal of `master.m3u8` forces; see [megaPlayRenditionUrl]. */
    @Test
    fun theMasterIsRewrittenToTheRenditionBesideIt() {
        assertEquals(
            "https://fetch.nexabloom.top/anime/bb6d/b7d5/index-f1-v1-a1.m3u8",
            megaPlayRenditionUrl("https://fetch.nexabloom.top/anime/bb6d/b7d5/master.m3u8"),
        )
    }

    /** A query belongs to the request, not the file name, so it survives the swap. */
    @Test
    fun aQueryIsCarriedOver() {
        assertEquals(
            "https://h.top/a/b/index-f1-v1-a1.m3u8?t=9",
            megaPlayRenditionUrl("https://h.top/a/b/master.m3u8?t=9"),
        )
    }

    /**
     * Only a master is rewritten. MegaPlay's hosts have served plain `sources` URLs of other shapes
     * before, and inventing a sibling for one of those would replace a stream that works with a
     * guess that does not.
     */
    @Test
    fun anythingElseIsLeftAlone() {
        assertNull(megaPlayRenditionUrl("https://h.top/a/b/index-f1-v1-a1.m3u8"))
        assertNull(megaPlayRenditionUrl("https://h.top/a/b/playlist.m3u8"))
        assertNull(megaPlayRenditionUrl("https://h.top/master.m3u8.html"))
        assertNull(megaPlayRenditionUrl(""))
    }
}
