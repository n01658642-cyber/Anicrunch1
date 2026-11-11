package com.anilili.playback

import androidx.media3.common.MediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cast gate, stated as a test.
 *
 * Only [CastStreamRouting.canCastQueue] is here: the address rewriting either side of it needs
 * android.net.Uri, which JVM tests cannot build, and it is exercised on-device by
 * MiruroCastMediaItemConverterTest and AnimeHeavenDownloadAndCastTest instead.
 */
class CastStreamRoutingTest {

    /** The ordinary case, and the only one that may reach a receiver. */
    @Test
    fun `a plain queue can be cast`() {
        assertTrue(CastStreamRouting.canCastQueue(listOf(MediaItem.EMPTY), playlistKey = null))
    }

    /**
     * ReAnime. Its playlists arrive XOR'd and are decoded by [FlixcloudPlaylistDataSource] on this
     * device; a receiver fetching the same address gets bytes it cannot read. Measured from the
     * 2026-09-20 report, where casting it was offered, accepted, and refused by the receiver in
     * 896ms with no reason given.
     */
    @Test
    fun `a keyed playlist cannot be cast`() {
        assertFalse(CastStreamRouting.canCastQueue(listOf(MediaItem.EMPTY), playlistKey = "3f9c1a"))
    }

    /** UniqueStream states its key the same way, through a marker rather than the key itself. */
    @Test
    fun `a uniquestream marker cannot be cast`() {
        assertFalse(
            CastStreamRouting.canCastQueue(
                listOf(MediaItem.EMPTY),
                playlistKey = "uniquestream:d1613c81cb351cf5a3394ddc8f7c0cac",
            ),
        )
    }

    /**
     * An embed source plays in a WebView, so this service's player holds nothing — there are no
     * addresses to hand over, and a receiver given an empty queue sits at IDLE saying nothing.
     */
    @Test
    fun `an empty queue cannot be cast`() {
        assertFalse(CastStreamRouting.canCastQueue(emptyList(), playlistKey = null))
    }
}
