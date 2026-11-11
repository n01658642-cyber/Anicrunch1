package com.anilili.playback

/**
 * Decides when the active downloads have stopped getting anywhere.
 *
 * The rule this replaced was "did `bytesDownloaded` change at all since the last look", and a CDN
 * holding one broken segment defeats it completely. Media3 runs four segment requests at a time and
 * abandons the whole batch when any one of them throws, so a segment the server answers but never
 * finishes sending costs a read timeout and then takes its three healthy siblings down with it; the
 * next attempt starts over on the same dead segment. What still reaches the cache is the occasional
 * tail of a sibling that happened to land inside the window. One viewer's Re:Zero download crawled
 * from 139.0 MB to 139.2 MB in two minutes and, over the four minutes after that, completed a
 * single 14 KB request — every one of those windows "changed", so the watchdog saw progress for as
 * long as the viewer was willing to watch, and the row sat at 46% for good.
 *
 * Progress therefore has to mean a quantity, not a difference. [MIN_PROGRESS_BYTES] over
 * [STALL_TIMEOUT_MS] is a very low bar for a live transfer — a 300 MB episode moving that slowly
 * needs a day — while still being some sixty times what the stall above managed in the same span.
 * Erring towards patience is deliberate: parking a download that is merely crawling on a bad mobile
 * link is worse than taking a few extra minutes to notice a dead one.
 */
internal class DownloadStallTracker(
    private val minProgressBytes: Long = MIN_PROGRESS_BYTES,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS,
) {
    private var watchedIds: Set<String> = emptySet()
    private var progressBytes: Long = 0L
    private var progressAtMs: Long = 0L

    /**
     * Records one observation of the downloads currently running.
     *
     * Returns true exactly once per stall, when [activeIds] have gone [stallTimeoutMs] without
     * gaining [minProgressBytes] between them. The window then restarts, so a download left running
     * after the caller declines to stop it is judged again rather than reported every sample.
     */
    fun sample(nowMs: Long, activeIds: Set<String>, bytesDownloaded: Long): Boolean {
        // A different set of downloads is a different transfer; it inherits neither the byte
        // baseline nor the clock of the one before it.
        if (activeIds.isEmpty() || activeIds != watchedIds) {
            restart(nowMs, activeIds, bytesDownloaded)
            return false
        }
        // Media3 counts cached bytes, so removing or restarting a download underneath us can lower
        // the figure. Take the new floor rather than reading the drop as five minutes of silence.
        if (bytesDownloaded < progressBytes || bytesDownloaded - progressBytes >= minProgressBytes) {
            restart(nowMs, activeIds, bytesDownloaded)
            return false
        }
        if (nowMs - progressAtMs < stallTimeoutMs) return false
        restart(nowMs, activeIds, bytesDownloaded)
        return true
    }

    private fun restart(nowMs: Long, activeIds: Set<String>, bytesDownloaded: Long) {
        watchedIds = activeIds
        progressBytes = bytesDownloaded
        progressAtMs = nowMs
    }

    companion object {
        /**
         * Roughly 3.5 KB/s once spread over [STALL_TIMEOUT_MS] — below the slowest link that could
         * finish an episode in a day, and far below any transfer that is actually moving.
         */
        const val MIN_PROGRESS_BYTES = 1L * 1024 * 1024
        const val STALL_TIMEOUT_MS = 5L * 60 * 1000
    }
}
