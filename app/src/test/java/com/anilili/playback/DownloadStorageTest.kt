package com.anilili.playback

import com.anilili.data.settings.DownloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {
    @Test
    fun `estimates scale with resolution`() {
        val p1080 = DownloadStorage.estimatedEpisodeBytes(1080)
        val p720 = DownloadStorage.estimatedEpisodeBytes(720)
        val p480 = DownloadStorage.estimatedEpisodeBytes(480)
        assertTrue("1080p should be the largest", p1080 > p720)
        assertTrue("720p should beat 480p", p720 > p480)
    }

    @Test
    fun `every resolution the picker offers is smaller than the one above it`() {
        // The regression this locks: 360p had no entry, so it took the unknown-height fallback
        // and came out *larger* than 480p. Choosing a lower resolution then raised the quoted
        // size, and the pre-flight check refused a 36 MB download without 1.4 GB free. Walking
        // the enum rather than a hand-written list means a new rung cannot reopen the hole.
        val ladder = DownloadQuality.entries
            .mapNotNull { it.maxHeight }
            .sorted()
        ladder.zipWithNext { lower, higher ->
            val lowerBytes = DownloadStorage.estimatedEpisodeBytes(lower)
            val higherBytes = DownloadStorage.estimatedEpisodeBytes(higher)
            assertTrue(
                "${lower}p ($lowerBytes) must estimate below ${higher}p ($higherBytes)",
                lowerBytes < higherBytes,
            )
        }
    }

    @Test
    fun `the smallest offered resolution is not priced like a whole episode at full quality`() {
        // 360p episodes measured 36 MB each on device; anything near the old 400 MB fallback is
        // wrong by an order of magnitude and blocks downloads that would comfortably fit.
        val p360 = DownloadStorage.estimatedEpisodeBytes(360)
        assertTrue("360p estimate is unreasonably large: $p360", p360 < 200L * 1024 * 1024)
        assertTrue("360p estimate should still leave headroom over the ~36 MB measured", p360 > 36L * 1024 * 1024)
    }

    @Test
    fun `unknown and best-available resolutions fall back to a middling estimate`() {
        // "Best available" carries a null height, and an odd rendition height should not throw.
        val fallback = DownloadStorage.estimatedEpisodeBytes(null)
        assertEquals(fallback, DownloadStorage.estimatedEpisodeBytes(1440))
        assertTrue(fallback > DownloadStorage.estimatedEpisodeBytes(480))
        assertTrue(fallback < DownloadStorage.estimatedEpisodeBytes(1080))
    }

    @Test
    fun `headroom is large enough to matter on a TV stick`() {
        // A Fire TV stick has roughly 5 GB usable. Reserving under a few hundred MB would let a
        // download run the device dry, which is the failure this whole check exists to avoid.
        assertTrue(DownloadStorage.HEADROOM_BYTES >= 500L * 1024 * 1024)
    }
}
