package com.anilili.playback

import com.anilili.ui.downloads.stateLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeDownloadRetryTest {

    @Test
    fun `a failed download can be retried`() {
        assertTrue(download(state = EpisodeDownloadState.FAILED).canRetry)
    }

    @Test
    fun `a download the watchdog parked can be retried and does not read as a user pause`() {
        // The stall watchdog stops a download that has stopped getting anywhere (see
        // [DownloadStallTracker]). It used to be indistinguishable from the viewer pausing it, so
        // the row said "Paused" and sat there for good — the "stuck at episode 1" report.
        val stalled = download(
            state = EpisodeDownloadState.STOPPED,
            stopReason = EpisodeDownloads.STALLED_DOWNLOAD_STOP_REASON,
        )

        assertTrue(stalled.stalled)
        assertTrue(stalled.canRetry)
        // The row no longer spells out "retry to continue": the retry control is right there beside
        // it, and the code is what a viewer can quote when retrying does not help.
        assertEquals("Download stalled. · DL-04", stalled.stateLabel())
    }

    @Test
    fun `a download the system timeout parked can be retried`() {
        val parked = download(
            state = EpisodeDownloadState.STOPPED,
            stopReason = EpisodeDownloads.SYSTEM_TIMEOUT_STOP_REASON,
        )

        assertTrue(parked.canRetry)
    }

    @Test
    fun `a pause the viewer asked for stays a pause`() {
        // Stop reason 1 is the user's own pause; offering Retry there would be noise, and the label
        // has to keep saying what they did.
        val paused = download(state = EpisodeDownloadState.STOPPED, stopReason = 1)

        assertFalse(paused.stalled)
        assertFalse(paused.canRetry)
        assertTrue(paused.canResume)
        assertEquals("Paused", paused.stateLabel())
    }

    @Test
    fun `a healthy download offers no retry`() {
        assertFalse(download(state = EpisodeDownloadState.DOWNLOADING, percent = 42f).canRetry)
        assertFalse(download(state = EpisodeDownloadState.COMPLETED).canRetry)
        assertFalse(download(state = EpisodeDownloadState.QUEUED).canRetry)
    }

    @Test
    fun `a failed download carries a code that can be quoted in a report`() {
        assertEquals(
            "Download failed. · DL-02",
            download(state = EpisodeDownloadState.FAILED).stateLabel(),
        )
    }

    /**
     * A full disk keeps its own code. Retrying is exactly what cannot work until space is freed, so
     * the two failures must not collapse into one label the way they would if the row only said
     * "Download failed".
     */
    @Test
    fun `a download stopped by a full disk says so instead of failing generically`() {
        assertEquals(
            "Not enough space left on this device. · DL-03",
            download(state = EpisodeDownloadState.FAILED).stateLabel(storageIsFull = true),
        )
        assertEquals(
            "Not enough space left on this device. · DL-03",
            download(
                state = EpisodeDownloadState.STOPPED,
                stopReason = EpisodeDownloads.STALLED_DOWNLOAD_STOP_REASON,
            ).stateLabel(storageIsFull = true),
        )
    }

    private fun download(
        state: EpisodeDownloadState = EpisodeDownloadState.DOWNLOADING,
        percent: Float? = null,
        stopReason: Int = 0,
    ) = EpisodeDownload(
        id = "anilist-1-episode-1",
        uri = "https://cdn.example/master.m3u8",
        metadata = EpisodeDownloadMetadata(
            anilistId = 1,
            seriesTitle = "Series",
            episodeNumber = "1",
            provider = "provider",
            category = "sub",
        ),
        state = state,
        percent = percent,
        bytesDownloaded = 0,
        contentLength = null,
        updatedAtMs = 0,
        stopReason = stopReason,
    )
}
