package com.anilili.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The DataSource half of this integration needs android.net.Uri, which JVM tests cannot build;
 * the end-to-end path is covered on-device by UniqueStreamPlaybackTest instead.
 */
class UniqueStreamKeyDataSourceTest {
    @Test
    fun keyBytesHexDecodesTheMediaId() {
        // Verified against the live CDN: unwrapping key.bin for this media id yields exactly
        // these 16 bytes, and they decrypt seg-0 to a 0x47-synced MPEG-TS header.
        val key = uniqueStreamKeyBytes("uniquestream:d1613c81cb351cf5a3394ddc8f7c0cac")
        assertArrayEquals(
            byteArrayOf(
                0xd1.toByte(), 0x61, 0x3c, 0x81.toByte(), 0xcb.toByte(), 0x35, 0x1c, 0xf5.toByte(),
                0xa3.toByte(), 0x39, 0x4d, 0xdc.toByte(), 0x8f.toByte(), 0x7c, 0x0c, 0xac.toByte(),
            ),
            key,
        )
    }

    @Test
    fun keyBytesRejectsForeignAndMalformedMarkers() {
        assertNull(uniqueStreamKeyBytes(null))
        assertNull(uniqueStreamKeyBytes("flixcloud-key-without-prefix"))
        assertNull(uniqueStreamKeyBytes("uniquestream:tooshort"))
        assertNull(uniqueStreamKeyBytes("uniquestream:zz613c81cb351cf5a3394ddc8f7c0cac"))
    }
}
