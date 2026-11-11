package com.anilili.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedSilentPagePolicyTest {
    private val timeoutMs = 20_000L

    private fun failed(
        pageFinished: Boolean = true,
        playbackAvailable: Boolean = false,
        loadError: String? = null,
        silentMs: Long = timeoutMs,
    ) = embedSilentPageFailed(
        pageFinished = pageFinished,
        playbackAvailable = playbackAvailable,
        loadError = loadError,
        silentMs = silentMs,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `a page that loads and never shows a video is given up on`() {
        // The measured case: bonk's last embed finished loading and produced nothing for 34s.
        assertTrue(failed(silentMs = 34_000L))
        assertTrue(failed(silentMs = timeoutMs))
    }

    @Test
    fun `a video is enough even when it is not playing`() {
        // An embed parked at 0:00 behind a gesture is working, so it must never be failed over.
        assertTrue(failed(playbackAvailable = false))
        assertFalse(failed(playbackAvailable = true))
        assertFalse(failed(playbackAvailable = true, silentMs = Long.MAX_VALUE))
    }

    @Test
    fun `a page still loading belongs to the load watchdog`() {
        // Before onPageFinished there is nothing to judge: EMBED_LOAD_TIMEOUT_MS owns that window,
        // and firing here too would fail the source on half the budget.
        assertFalse(failed(pageFinished = false))
        assertFalse(failed(pageFinished = false, silentMs = Long.MAX_VALUE))
    }

    @Test
    fun `a page that reported its own error belongs to the dead-page watchdog`() {
        // Two failures for one page would spend two sources on it.
        assertFalse(failed(loadError = "HTTP 404 from the video server"))
    }

    @Test
    fun `the budget has to actually elapse`() {
        assertFalse(failed(silentMs = 0L))
        assertFalse(failed(silentMs = timeoutMs - 1))
    }
}
