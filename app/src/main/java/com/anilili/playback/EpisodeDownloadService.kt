package com.anilili.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.scheduler.Scheduler
import com.anilili.MainActivity
import com.anilili.R
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.nav.Routes

/**
 * Keeps episode downloads alive after the app is backgrounded or its activity is destroyed.
 *
 * The notification refreshes every [NOTIFICATION_UPDATE_INTERVAL_MS] rather than Media3's default
 * second. Every refresh is a full re-post that the system UI has to re-render, and on a Galaxy S25
 * Ultra a multi-episode download plus its export put this app at 82 posts in ~35s against one from
 * every other app combined. With the shade open, Samsung's System UI rebuilt this app's auto-grouped
 * notifications on each one and dropped to 0.7–1.5s frames.
 */
@OptIn(UnstableApi::class)
class EpisodeDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    NOTIFICATION_UPDATE_INTERVAL_MS,
    NOTIFICATION_CHANNEL_ID,
    R.string.download_notification_channel_name,
    R.string.download_notification_channel_description,
) {
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var lastWatchdogAt = 0L
    private var activeRuntimeMs = 0L
    private val stallTracker = DownloadStallTracker()
    private val watchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val active = getDownloadManager().currentDownloads
                .filter { it.state == Download.STATE_DOWNLOADING }
            val activeIds = active.mapTo(mutableSetOf()) { it.request.id }
            val bytes = active.sumOf { it.bytesDownloaded }
            if (active.isNotEmpty()) activeRuntimeMs += (now - lastWatchdogAt).coerceAtLeast(0L)
            lastWatchdogAt = now
            if (stallTracker.sample(now, activeIds, bytes)) {
                active.forEach { download ->
                    getDownloadManager().setStopReason(
                        download.request.id,
                        STALLED_DOWNLOAD_STOP_REASON,
                    )
                }
                DiagnosticsLog.event(
                    "Episode downloads paused after no progress ids=${active.map { it.request.id }}",
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                activeRuntimeMs >= PROACTIVE_FGS_LIMIT_MS
            ) {
                DiagnosticsLog.event("Episode download service reached proactive runtime limit")
                stopForSystemLimit()
                return
            }
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val notificationHelper by lazy {
        DownloadNotificationHelper(this, NOTIFICATION_CHANNEL_ID)
    }

    override fun getDownloadManager(): DownloadManager =
        EpisodeDownloads.getDownloadManager(this)

    override fun getScheduler(): Scheduler =
        PlatformScheduler(this, DOWNLOAD_JOB_ID)

    override fun onCreate() {
        super.onCreate()
        lastWatchdogAt = SystemClock.elapsedRealtime()
        watchdogHandler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacks(watchdog)
        super.onDestroy()
    }

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification = notificationHelper.buildProgressNotification(
        this,
        R.drawable.ic_notification,
        downloadsPendingIntent(),
        progressSizeMessage(downloads),
        downloads,
        notMetRequirements,
    )

    /**
     * The bytes line under the notification's progress bar, or null to leave it out.
     *
     * Media3's own notification shows a percentage and nothing else, so the shade could not answer
     * "how big is this going to be" — the question every other downloader on the phone answers in
     * its second line. Summed across whatever is running, because the notification is for the queue
     * rather than for one episode.
     *
     * Null when no total is known, which leaves the notification exactly as it was rather than
     * printing a bare fetched-size that reads like a total.
     */
    private fun progressSizeMessage(downloads: List<Download>): String? {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        if (active.isEmpty()) return null
        // One unknown length makes the sum a floor rather than a total, and a total that is quietly
        // short of the truth is worse here than no total at all: it would stop rising and sit there
        // looking stuck while the download carried on.
        if (active.any { it.contentLength <= 0L }) return null
        return downloadSizeLabel(
            bytesDownloaded = active.sumOf { it.bytesDownloaded },
            contentLength = active.sumOf { it.contentLength },
        )
    }

    /** Android 15+ calls this when the data-sync foreground-service budget is exhausted. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForSystemLimit(startId)
    }

    private fun stopForSystemLimit(startId: Int? = null) {
        runCatching { EpisodeDownloads.pauseAllForSystemTimeout(this) }
            .onFailure { DiagnosticsLog.throwable("Episode download timeout shutdown failed", it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId == null) stopSelf() else stopSelf(startId)
    }

    private fun downloadsPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(Routes.EXTRA_ROUTE, Routes.DOWNLOADS)
        }
        return PendingIntent.getActivity(
            this,
            DOWNLOAD_JOB_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 7_201
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 3_000L
        const val DOWNLOAD_JOB_ID = 7_202
        const val NOTIFICATION_CHANNEL_ID = "episode_downloads"
        val STALLED_DOWNLOAD_STOP_REASON = EpisodeDownloads.STALLED_DOWNLOAD_STOP_REASON
        const val WATCHDOG_INTERVAL_MS = 60_000L
        const val PROACTIVE_FGS_LIMIT_MS = 5L * 60 * 60 * 1000 + 30L * 60 * 1000
    }
}
