package com.anilili.ui.components

import com.anilili.playback.EpisodeDownloadStage
import com.anilili.playback.EpisodeDownloadUi
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeDownloadButtonTest {
    @Test
    fun `an episode with no copy offers to download`() {
        assertEquals(
            EpisodeDownloadAction.START,
            episodeDownloadAction(state = null, pending = false),
        )
    }

    @Test
    fun `a press waits for the provider before offering anything else`() {
        // Nothing reaches the download index until a source resolves, which is up to twenty
        // seconds on a slow provider. Without this the button would still read "download" and
        // invite the second press that queues nothing.
        assertEquals(
            EpisodeDownloadAction.WAIT,
            episodeDownloadAction(state = null, pending = true),
        )
    }

    @Test
    fun `a saved episode offers removal, not another download`() {
        assertEquals(
            EpisodeDownloadAction.REMOVE,
            episodeDownloadAction(EpisodeDownloadUi(EpisodeDownloadStage.SAVED, 1f), pending = false),
        )
    }

    @Test
    fun `a failed download offers to try again`() {
        assertEquals(
            EpisodeDownloadAction.RETRY,
            episodeDownloadAction(EpisodeDownloadUi(EpisodeDownloadStage.FAILED), pending = false),
        )
    }

    @Test
    fun `every stage still in flight offers to stop`() {
        val inFlight = listOf(
            EpisodeDownloadStage.QUEUED,
            EpisodeDownloadStage.DOWNLOADING,
            EpisodeDownloadStage.PAUSED,
            EpisodeDownloadStage.CONVERTING,
        )
        inFlight.forEach { stage ->
            assertEquals(
                "stage $stage",
                EpisodeDownloadAction.CANCEL,
                episodeDownloadAction(EpisodeDownloadUi(stage, 0.4f), pending = false),
            )
        }
    }

    @Test
    fun `the queue outranks a stale badge`() {
        // A retry re-queues an episode whose failed entry is still in the index; until the new
        // source resolves the honest thing to show is the wait, not the old failure.
        assertEquals(
            EpisodeDownloadAction.WAIT,
            episodeDownloadAction(EpisodeDownloadUi(EpisodeDownloadStage.FAILED), pending = true),
        )
    }
}
