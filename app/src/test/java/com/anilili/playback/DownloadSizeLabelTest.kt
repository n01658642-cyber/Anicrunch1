package com.anilili.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadSizeLabelTest {
    private val mb = 1024L * 1024

    @Test
    fun `a known total is shown beside what has arrived`() {
        assertEquals(
            "6.5 MB / 36.8 MB",
            downloadSizeLabel(bytesDownloaded = (6.5 * mb).toLong(), contentLength = (36.78 * mb).toLong()),
        )
    }

    @Test
    fun `an unknown total leaves the fetched size on its own`() {
        // The HLS case, which is most episodes: SegmentDownloader never learns a content length, so
        // the row says what it has and claims nothing about what is left.
        assertEquals("6.5 MB", downloadSizeLabel(bytesDownloaded = (6.5 * mb).toLong(), contentLength = null))
    }

    @Test
    fun `a total the transfer has already passed is not shown`() {
        // Better to print one number than to print "40.0 MB / 37.0 MB", which reads as a bug.
        assertEquals(
            "40.0 MB",
            downloadSizeLabel(bytesDownloaded = 40 * mb, contentLength = 37 * mb),
        )
    }

    @Test
    fun `a download that has fetched nothing yet has nothing to say`() {
        assertNull(downloadSizeLabel(bytesDownloaded = 0L, contentLength = 37 * mb))
        assertNull(downloadSizeLabel(bytesDownloaded = -1L, contentLength = null))
    }

    @Test
    fun `units follow the size`() {
        assertEquals("512.0 KB / 2.0 MB", downloadSizeLabel(512 * 1024L, 2 * mb))
        assertEquals("1.5 GB / 2.0 GB", downloadSizeLabel((1.5 * 1024 * mb).toLong(), 2 * 1024 * mb))
    }

    @Test
    fun `a download exactly at its total still reads sensibly`() {
        assertEquals("37.0 MB / 37.0 MB", downloadSizeLabel(37 * mb, 37 * mb))
    }
}
