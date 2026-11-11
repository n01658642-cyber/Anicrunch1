package com.anilili.ui.downloads

import com.anilili.playback.EpisodeDownload
import com.anilili.playback.EpisodeDownloadMetadata
import com.anilili.playback.EpisodeDownloadState
import com.anilili.playback.EpisodeExportState
import com.anilili.playback.EpisodeExportStatus
import com.anilili.playback.ExportedEpisode
import com.anilili.playback.OfflineEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the selection bar. Both of these decide what a button *says* it will do to a
 * batch of episodes, so they have to agree with what the per-episode menu would offer — a bar
 * promising to save five episodes and saving two is the sort of thing nobody reports as a bug.
 */
class DownloadSelectionTest {
    @Test
    fun `only a complete app-held copy with no export in flight can be exported`() {
        val ready = episode("a", state = EpisodeDownloadState.COMPLETED)
        val downloading = episode("b", state = EpisodeDownloadState.DOWNLOADING)
        val alreadyOnDevice = episode("c", state = EpisodeDownloadState.COMPLETED, exported = true)
        // An MP4 that only ever existed in the Downloads folder has no cached copy to rewrap.
        val exportOnly = OfflineEpisode("d", download = null, exported = exportedEpisode("d"))

        val exportable = exportableOfflineEpisodes(
            listOf(ready, downloading, alreadyOnDevice, exportOnly),
            exportStatuses = emptyMap(),
            exportSupported = true,
        )

        assertEquals(listOf("a"), exportable.map(OfflineEpisode::id))
    }

    @Test
    fun `a queued or running export is not offered again, a failed one is`() {
        val episode = episode("a", state = EpisodeDownloadState.COMPLETED)

        assertFalse(isOfflineEpisodeExportable(episode, status(EpisodeExportState.PENDING), true))
        assertFalse(isOfflineEpisodeExportable(episode, status(EpisodeExportState.RUNNING), true))
        assertFalse(isOfflineEpisodeExportable(episode, status(EpisodeExportState.COMPLETED), true))
        assertTrue(isOfflineEpisodeExportable(episode, status(EpisodeExportState.FAILED), true))
    }

    @Test
    fun `a device that cannot transmux is offered nothing at all`() {
        val ready = episode("a", state = EpisodeDownloadState.COMPLETED)

        assertTrue(exportableOfflineEpisodes(listOf(ready), emptyMap(), exportSupported = true).size == 1)
        assertTrue(exportableOfflineEpisodes(listOf(ready), emptyMap(), exportSupported = false).isEmpty())
    }

    @Test
    fun `the selection summary counts episodes and adds up their bytes`() {
        assertEquals(
            "2 episodes · 3.0 MB",
            selectionSummary(
                listOf(
                    episode("a", state = EpisodeDownloadState.COMPLETED, bytes = 1024L * 1024),
                    episode("b", state = EpisodeDownloadState.COMPLETED, bytes = 2 * 1024L * 1024),
                ),
            ),
        )
        // A single queued episode has no size yet, and a summary reading "· 0.0 KB" is noise.
        assertEquals("1 episode", selectionSummary(listOf(episode("a", EpisodeDownloadState.QUEUED, bytes = 0))))
    }

    private fun status(state: EpisodeExportState) = EpisodeExportStatus("a", state)

    private fun episode(
        id: String,
        state: EpisodeDownloadState,
        bytes: Long = 1024,
        exported: Boolean = false,
    ) = OfflineEpisode(
        id = id,
        download = EpisodeDownload(
            id = id,
            uri = "https://cdn.example/$id/master.m3u8",
            metadata = metadata,
            state = state,
            percent = if (state == EpisodeDownloadState.COMPLETED) 100f else 20f,
            bytesDownloaded = bytes,
            contentLength = bytes,
            updatedAtMs = 0,
        ),
        exported = if (exported) exportedEpisode(id) else null,
    )

    private fun exportedEpisode(id: String) = ExportedEpisode(
        downloadId = id,
        uri = "content://downloads/$id",
        fileName = "$id.mp4",
        metadata = metadata,
    )

    private val metadata = EpisodeDownloadMetadata(
        anilistId = 42,
        seriesTitle = "Example",
        episodeNumber = "1",
        provider = "example",
        category = "sub",
    )
}
