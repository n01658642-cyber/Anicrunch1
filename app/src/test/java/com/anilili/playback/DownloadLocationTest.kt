package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadLocationTest {

    @Test
    fun `every location survives a round trip through its stored form`() {
        listOf(
            DownloadLocation.PrimaryDownloads,
            DownloadLocation.MediaStoreVolume("1a2b-3c4d"),
            DownloadLocation.DocumentTree("content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AAnime"),
            DownloadLocation.AppDirectory("/storage/1A2B-3C4D/Android/data/com.anilili/files"),
        ).forEach { location ->
            assertEquals(location, parseDownloadLocation(location.storedValue))
        }
    }

    /**
     * A settings backup carries this value to devices that have never seen the card it names, and
     * an old install has nothing stored at all. Both have to land somewhere writable rather than
     * throw on the way to the download.
     */
    @Test
    fun `anything unreadable falls back to the public Downloads folder`() {
        listOf(null, "", "   ", "volume:", "tree:", "dir:", "sdcard", "volume", "tree")
            .forEach { stored ->
                assertEquals(
                    "stored=$stored",
                    DownloadLocation.PrimaryDownloads,
                    parseDownloadLocation(stored),
                )
            }
    }

    /** A tree URI contains colons and slashes of its own; the prefix is the only separator. */
    @Test
    fun `a picked folder keeps its whole uri`() {
        val uri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMovies%2FAnime"
        assertEquals(
            DownloadLocation.DocumentTree(uri),
            parseDownloadLocation("tree:$uri"),
        )
    }

    /**
     * The same card is named `/storage/1A2B-3C4D/…` by the filesystem and `1a2b-3c4d` by
     * MediaStore. Failing to see those as one volume offers the viewer the same card twice, once
     * as a public folder and once as a private one.
     */
    @Test
    fun `a storage path yields the volume that MediaStore would name`() {
        assertEquals(
            "1a2b-3c4d",
            volumeIdOfPath("/storage/1A2B-3C4D/Android/data/com.anilili/files"),
        )
        assertEquals("usb0", volumeIdOfPath("/storage/USB0/Android/data/com.anilili/files"))
    }

    /** Internal storage is not a volume anything can be chosen on, under either of its names. */
    @Test
    fun `internal paths name no removable volume`() {
        assertNull(volumeIdOfPath("/data/user/0/com.anilili/files"))
        assertNull(volumeIdOfPath("/storage/emulated/0/Android/data/com.anilili/files"))
        assertNull(volumeIdOfPath("/storage/self/primary/Download"))
        assertNull(volumeIdOfPath("/storage"))
    }
}
