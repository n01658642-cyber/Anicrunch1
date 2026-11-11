package com.anilili.playback

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlixcloudPlaylistDataSourceTest {
    @Test
    fun decodesBase64XorPlaylist() {
        val key = ByteArray(32) { it.toByte() }
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\nvideo.m3u8\n".toByteArray()
        val encrypted = ByteArray(playlist.size) { index ->
            (playlist[index].toInt() xor key[index % key.size].toInt()).toByte()
        }

        val decoded = decodeFlixcloudPlaylist(
            Base64.getEncoder().encode(encrypted),
            Base64.getEncoder().encodeToString(key),
        )

        assertArrayEquals(playlist, decoded)
    }

    @Test
    fun leavesPlainOrInvalidPlaylistUntouched() {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

        assertNull(decodeFlixcloudPlaylist("#EXTM3U\n".toByteArray(), key))
        assertNull(decodeFlixcloudPlaylist("not-base64".toByteArray(), key))
    }

    /**
     * Offline playback of a ReAnime episode rests on this.
     *
     * [EpisodeDownloads.upstreamFactory] decodes before the DownloadManager caches, so what lands
     * on disk is a finished playlist. Playing it back reads the cache through PlaybackService's own
     * copy of this decoder, which therefore sees an already-decoded playlist a second time and must
     * hand it straight back. It does, via the `#EXTM3U` early return — pinned here because that
     * return reads like a cheap optimisation and removing it would corrupt every saved episode
     * from a keyed source while live playback carried on working.
     */
    @Test
    fun decodingAnAlreadyDecodedPlaylistIsANoOp() {
        val key = ByteArray(32) { it.toByte() }
        val keyBase64 = Base64.getEncoder().encodeToString(key)
        val playlist = "#EXTM3U\n#EXT-X-VERSION:3\nvideo.m3u8\n".toByteArray()
        val encrypted = ByteArray(playlist.size) { index ->
            (playlist[index].toInt() xor key[index % key.size].toInt()).toByte()
        }

        val once = decodeFlixcloudPlaylist(Base64.getEncoder().encode(encrypted), keyBase64)
        assertArrayEquals(playlist, once)
        // The second pass is the one the downloaded copy takes. Null means "nothing to do", and
        // the data source passes the original bytes through on it.
        assertNull(decodeFlixcloudPlaylist(once!!, keyBase64))
    }

    @Test
    fun unwrapsPlainAndMaskedImageSegments() {
        val transportStream = byteArrayOf(0x47, 0x40, 0x11, 0x10, 0x00, 0x2a, 0x7f)
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val webpHeader = "RIFF0000WEBP".toByteArray()
        val mask = byteArrayOf(
            157.toByte(), 42, 241.toByte(), 71, 179.toByte(), 142.toByte(), 92, 112,
            166.toByte(), 25, 228.toByte(), 59, 216.toByte(), 98, 15, 197.toByte(),
        )
        val masked = ByteArray(transportStream.size) { index ->
            (transportStream[index].toInt() xor mask[index and 15].toInt()).toByte()
        }

        assertArrayEquals(transportStream, decodeFlixcloudSegment(pngHeader + transportStream))
        assertArrayEquals(transportStream, decodeFlixcloudSegment(webpHeader + masked))
        assertNull(decodeFlixcloudSegment(transportStream))
    }
}
