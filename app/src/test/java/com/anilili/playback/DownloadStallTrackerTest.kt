package com.anilili.playback

import com.anilili.playback.DownloadStallTracker.Companion.MIN_PROGRESS_BYTES
import com.anilili.playback.DownloadStallTracker.Companion.STALL_TIMEOUT_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MINUTE = 60_000L

class DownloadStallTrackerTest {

    @Test
    fun `a trickle of bytes is not progress`() {
        // The report this exists for: a download stuck on a segment the CDN would answer but never
        // finish sending, creeping from 139.0 MB to 139.2 MB over two minutes and then completing
        // one 14 KB request in the four minutes after that. The old rule asked only whether the
        // byte count had changed, so every one of those windows read as progress and the watchdog
        // never fired.
        val tracker = DownloadStallTracker()
        var bytes = 139_000_000L

        assertFalse(tracker.sample(0, setOf("episode-1"), bytes))
        for (minute in 1..4) {
            bytes += 100_000
            assertFalse(tracker.sample(minute * MINUTE, setOf("episode-1"), bytes))
        }
        bytes += 100_000

        assertTrue(tracker.sample(5 * MINUTE, setOf("episode-1"), bytes))
    }

    @Test
    fun `a download that is merely slow is left alone`() {
        // A bad mobile link still finishes eventually and must not be parked. Half a megabyte a
        // minute is well under a tenth of what this CDN gives a healthy transfer, and it survives.
        val tracker = DownloadStallTracker()
        var bytes = 0L

        assertFalse(tracker.sample(0, setOf("episode-1"), bytes))
        for (minute in 1..30) {
            bytes += MIN_PROGRESS_BYTES / 2
            assertFalse(tracker.sample(minute * MINUTE, setOf("episode-1"), bytes))
        }
    }

    @Test
    fun `nothing is reported before the timeout has run`() {
        val tracker = DownloadStallTracker()

        assertFalse(tracker.sample(0, setOf("episode-1"), 1_000))
        assertFalse(tracker.sample(STALL_TIMEOUT_MS - 1, setOf("episode-1"), 1_000))
        assertTrue(tracker.sample(STALL_TIMEOUT_MS, setOf("episode-1"), 1_000))
    }

    @Test
    fun `a stall is reported once and then judged again from scratch`() {
        // The caller stops the download it is told about, but a download it declines to stop must
        // not produce a report on every sample from then on.
        val tracker = DownloadStallTracker()
        tracker.sample(0, setOf("episode-1"), 0)

        assertTrue(tracker.sample(STALL_TIMEOUT_MS, setOf("episode-1"), 0))
        assertFalse(tracker.sample(STALL_TIMEOUT_MS + MINUTE, setOf("episode-1"), 0))
        assertTrue(tracker.sample(2 * STALL_TIMEOUT_MS, setOf("episode-1"), 0))
    }

    @Test
    fun `the next download does not inherit the last one's silence`() {
        val tracker = DownloadStallTracker()
        tracker.sample(0, setOf("episode-1"), 5_000_000)

        assertFalse(tracker.sample(4 * MINUTE, setOf("episode-2"), 0))
        assertFalse(tracker.sample(8 * MINUTE, setOf("episode-2"), 0))
        assertTrue(tracker.sample(9 * MINUTE, setOf("episode-2"), 0))
    }

    @Test
    fun `an idle queue is never stalled`() {
        val tracker = DownloadStallTracker()

        assertFalse(tracker.sample(0, emptySet(), 0))
        assertFalse(tracker.sample(STALL_TIMEOUT_MS, emptySet(), 0))
        assertFalse(tracker.sample(10 * STALL_TIMEOUT_MS, emptySet(), 0))
    }

    @Test
    fun `a download that restarts from a lower byte count gets a fresh baseline`() {
        // Media3 reports cached bytes, so removing and re-adding a download can drop the figure by
        // a hundred megabytes. Reading that as five minutes of silence would park a transfer that
        // is in fact starting over and running perfectly.
        val tracker = DownloadStallTracker()
        tracker.sample(0, setOf("episode-1"), 140_000_000)

        assertFalse(tracker.sample(MINUTE, setOf("episode-1"), 0))
        assertFalse(tracker.sample(2 * MINUTE, setOf("episode-1"), 30_000_000))
    }
}
