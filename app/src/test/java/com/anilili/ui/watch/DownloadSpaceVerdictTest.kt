package com.anilili.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

private const val GB = 1024L * 1024 * 1024
private const val HEADROOM = 1_000L * 1024 * 1024

class DownloadSpaceVerdictTest {

    @Test
    fun `plenty of room downloads without comment`() {
        assertEquals(
            DownloadSpaceVerdict.FITS,
            downloadSpaceVerdict(freeBytes = 100 * GB, estimatedBytes = 2 * GB, headroomBytes = HEADROOM),
        )
    }

    @Test
    fun `a download larger than the free space is refused`() {
        assertEquals(
            DownloadSpaceVerdict.WONT_FIT,
            downloadSpaceVerdict(freeBytes = 2 * GB, estimatedBytes = 20 * GB, headroomBytes = HEADROOM),
        )
    }

    /** A whole season is where this bites: each episode fits, the batch does not. */
    @Test
    fun `a batch is judged on its total, not on one episode`() {
        val perEpisode = 420L * 1024 * 1024
        assertEquals(
            DownloadSpaceVerdict.FITS,
            downloadSpaceVerdict(6 * GB, perEpisode, HEADROOM),
        )
        assertEquals(
            DownloadSpaceVerdict.WONT_FIT,
            downloadSpaceVerdict(6 * GB, perEpisode * 25, HEADROOM),
        )
    }

    @Test
    fun `a download that fits but eats the headroom only warns`() {
        assertEquals(
            DownloadSpaceVerdict.TIGHT,
            downloadSpaceVerdict(freeBytes = 3 * GB, estimatedBytes = 2500L * 1024 * 1024, headroomBytes = HEADROOM),
        )
    }

    @Test
    fun `exactly fitting with no headroom to spare still warns rather than refuses`() {
        assertEquals(
            DownloadSpaceVerdict.TIGHT,
            downloadSpaceVerdict(freeBytes = 2 * GB, estimatedBytes = 2 * GB, headroomBytes = HEADROOM),
        )
    }

    /** Zero means the free space could not be read, which must not strand a viewer with room. */
    @Test
    fun `an unreadable free space never blocks the download`() {
        assertEquals(
            DownloadSpaceVerdict.FITS,
            downloadSpaceVerdict(freeBytes = 0L, estimatedBytes = 20 * GB, headroomBytes = HEADROOM),
        )
    }
}
