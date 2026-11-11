package com.anilili.playback

/** One finished episode as the summary notification lists it. */
internal data class DownloadBatchNotice(val label: String, val failed: Boolean)

/**
 * The episodes one summary notification speaks for.
 *
 * Each finished download used to post a notification of its own, so a season queued at once left
 * a dozen "Download completed" rows in the shade — fourteen at a time on a test phone. A batch
 * collects into one instead. It stays open while anything is still queued, and the first episode
 * to finish after the queue went idle starts a fresh one, so the notification describes the batch
 * the viewer just ran rather than everything since the app started.
 */
internal class DownloadBatchNotices {
    private val notices = LinkedHashMap<String, DownloadBatchNotice>()
    private var closed = false

    /**
     * Records [id]'s outcome and returns the batch to show, newest first.
     *
     * Media3 repeats `onDownloadChanged` for a download already in its terminal state, so a repeat
     * replaces its earlier entry rather than listing the episode twice.
     */
    @Synchronized
    fun record(id: String, notice: DownloadBatchNotice, queueIdle: Boolean): List<DownloadBatchNotice> {
        if (closed) {
            notices.clear()
            closed = false
        }
        notices.remove(id)
        notices[id] = notice
        if (queueIdle) closed = true
        return notices.values.reversed()
    }
}
