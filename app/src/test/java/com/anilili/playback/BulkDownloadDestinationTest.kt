package com.anilili.playback

import com.anilili.data.settings.DownloadDestination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anime page's download queue used to ignore the destination preference entirely, so the same
 * episode arrived as an MP4 in Downloads/Anilili from the player and as app-only cache segments
 * from the title page. Both entry points share this queue now, which makes the delete rule the one
 * piece worth pinning: it is the only outcome here a viewer cannot undo.
 */
class BulkDownloadDestinationTest {
    @Test
    fun `device-only drops the cached copy this download created`() {
        assertTrue(
            BulkEpisodeDownloads.deleteAppCopyAfterExport(DownloadDestination.DEVICE_ONLY, alreadyHeld = false),
        )
    }

    @Test
    fun `an episode already in the library survives a device-only download`() {
        // The viewer asked for an MP4, not for their offline copy to be taken away.
        assertFalse(
            BulkEpisodeDownloads.deleteAppCopyAfterExport(DownloadDestination.DEVICE_ONLY, alreadyHeld = true),
        )
    }

    @Test
    fun `destinations that keep a library copy never delete one`() {
        listOf(DownloadDestination.APP_ONLY, DownloadDestination.BOTH).forEach { destination ->
            assertFalse(
                destination.name,
                BulkEpisodeDownloads.deleteAppCopyAfterExport(destination, alreadyHeld = false),
            )
            assertFalse(
                destination.name,
                BulkEpisodeDownloads.deleteAppCopyAfterExport(destination, alreadyHeld = true),
            )
        }
    }

    @Test
    fun `only the app-only destination skips the Downloads folder entirely`() {
        assertFalse(DownloadDestination.APP_ONLY.includesDevice)
        assertTrue(DownloadDestination.DEVICE_ONLY.includesDevice)
        assertTrue(DownloadDestination.BOTH.includesDevice)
    }
}
