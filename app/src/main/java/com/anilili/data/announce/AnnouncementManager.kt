package com.anilili.data.announce

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anilili.BuildConfig
import com.anilili.MainActivity
import com.anilili.R
import com.anilili.data.AppGraph
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Shows the project's signed broadcasts (see [AnnouncementSource]) as a system notification.
 *
 * Only one announcement is ever live — the manifest is an addressable event, so a relay keeps only
 * the newest for its d-tag — which is why this posts under a single fixed notification id. A new
 * broadcast replaces the one on screen rather than stacking beside it, and a corrected one
 * re-published under the same [Announcement.id] does not re-notify at all.
 */
object AnnouncementManager {
    const val CHANNEL_ID = "announcements"
    const val URGENT_CHANNEL_ID = "announcements_urgent"

    /** One id, because only one announcement can be live. See the class note. */
    private const val NOTIFICATION_ID = -1103

    private const val PREFS = "anilili_announcements"
    private const val KEY_SEEN_IDS = "seen_ids"
    private const val KEY_LAST_CHECK = "last_check_ms"

    /**
     * The announcement the live notification belongs to, kept so tapping it can show the message
     * in full. The notification itself cannot carry it: an `Intent` extra survives fine, but the
     * pending intent is rebuilt on every delivery and the process it reopens may be a cold one.
     */
    private const val KEY_LIVE = "live_announcement"

    /** Intent extra naming the announcement to open. Value is [Announcement.id]. */
    const val EXTRA_ANNOUNCEMENT_ID = "com.miruronative.extra.ANNOUNCEMENT_ID"

    /**
     * The announcement a viewer has asked to read, or null when no dialog is open.
     *
     * Nothing sets this on delivery — only a tap does. A message arriving while someone is midway
     * through an episode should wait in the shade, not seize the screen.
     */
    private val _pending = MutableStateFlow<Announcement?>(null)
    val pending = _pending.asStateFlow()

    /**
     * One check at a time.
     *
     * The foreground trigger and the periodic worker are separate WorkManager requests and really
     * do run at once — observed on device, two relay scans a millisecond apart, both concluding the
     * id had not been shown yet and both posting. They collapsed onto one notification id so
     * nothing was visibly wrong, which is exactly why it needed catching here rather than later.
     * The loser of the lock re-reads the timestamp the winner wrote and stands down.
     */
    private val checkLock = Mutex()

    /**
     * How stale a check may be before a launch triggers another.
     *
     * The hourly worker is the floor; this is what governs the far more frequent triggers — every
     * time the app is brought to the foreground. It is a throttle rather than a schedule: the work
     * request is enqueued on each of those and usually finds the last check recent enough to do
     * nothing at all, which is what keeps a screen rotation from becoming a network request.
     */
    private val CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)

    /** Ids already shown, capped: this is a dedup ledger, not a history anyone reads. */
    private const val MAX_REMEMBERED_IDS = 20

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun init(context: Context) {
        val app = context.applicationContext
        (app as? Application)?.registerActivityLifecycleCallbacks(ForegroundCheck)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = app
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Two channels rather than one, because a channel's importance is fixed once created and
        // the two kinds of message deserve different treatment: "the provider is flaky today" must
        // not make the same noise at 3am as "do not install 0.1.76". Splitting them also lets a
        // viewer silence the routine one in system settings and keep the other.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Announcements", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Occasional messages from the developer about the app or its sources"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(URGENT_CHANNEL_ID, "Urgent announcements", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Rare, time-critical notices — an outage, a bad release, or a moved download"
            },
        )
    }

    /**
     * Fetches and delivers, unless something says not to.
     *
     * Returns false only for a genuine failure worth a WorkManager retry; "switched off", "nothing
     * published" and "not for this version" are all successful outcomes of a check.
     */
    internal suspend fun check(context: Context, force: Boolean = false): Boolean =
        checkLock.withLock { checkLocked(context, force) }

    private suspend fun checkLocked(context: Context, force: Boolean): Boolean {
        val app = context.applicationContext
        if (!SettingsStore.announcements.value) return true
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        // A clock moved backwards would otherwise park the channel until it caught up again.
        val due = force || lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS
        if (!due) return true

        val announcement = withContext(Dispatchers.IO) {
            runCatching { AnnouncementSource.fetch(AppGraph.httpClient, json) }
                .onFailure { DiagnosticsLog.throwable("Announcement check failed", it) }
                .getOrNull()
        }
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (announcement == null) return true

        if (!announcementApplies(announcement, BuildConfig.VERSION_NAME, System.currentTimeMillis() / 1000L)) {
            DiagnosticsLog.event(
                "Announcement skipped id=${announcement.id} reason=not-applicable " +
                    "installed=${BuildConfig.VERSION_NAME}",
            )
            return true
        }
        val seen = prefs.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty()
        if (announcement.id in seen) return true

        if (!post(app, announcement)) return true
        // Recorded only after it was actually posted, so a device that was denied the permission
        // still gets the message once the viewer grants it.
        // Prepended, not appended: a SharedPreferences string set comes back in hash order, so
        // trimming the tail of `seen + id` can discard the id just delivered and notify again.
        prefs.edit()
            .putStringSet(
                KEY_SEEN_IDS,
                (listOf(announcement.id) + seen).distinct().take(MAX_REMEMBERED_IDS).toSet(),
            )
            .putString(KEY_LIVE, json.encodeToString(Announcement.serializer(), announcement))
            .apply()
        DiagnosticsLog.event(
            "Announcement delivered id=${announcement.id} urgent=${announcement.urgent} " +
                "hasLink=${announcement.url != null}",
        )
        return true
    }

    /** Returns false when the device will not show notifications, so nothing is marked as seen. */
    private fun post(app: Context, announcement: Announcement): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        // Tapping opens the app onto the message in full, rather than straight into a browser.
        // A notification collapses a long message to one line and gives a link nothing tappable at
        // all, so the shade is the summary and the dialog is the message. It also puts the whole
        // URL in front of the viewer before anything opens it, which is the honest version of the
        // guard that used to be "show the host and hope" — see [AnnouncementHost].
        val openApp = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ANNOUNCEMENT_ID, announcement.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = announcement.message
        val builder = NotificationCompat.Builder(
            app,
            if (announcement.urgent) URGENT_CHANNEL_ID else CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(announcement.title)
            .setContentText(announcement.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(
                if (announcement.urgent) NotificationCompat.CATEGORY_ERROR
                else NotificationCompat.CATEGORY_MESSAGE,
            )
            .setPriority(
                if (announcement.urgent) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
        // The action opens the same dialog, not the browser. Two buttons that look alike and go
        // to different places is how a viewer ends up somewhere they did not choose.
        if (announcement.url != null) {
            builder.addAction(R.drawable.ic_notification, announcement.urlLabel ?: "Read", openApp)
        }
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, builder.build())
        return true
    }

    /**
     * Opens the message a notification tap refers to.
     *
     * Matched on id rather than trusted: the intent may have been sitting in the shade across a
     * newer announcement replacing it, and showing the viewer a message they never saw the
     * notification for would be worse than showing nothing.
     */
    fun openFromNotification(context: Context, id: String?) {
        if (id == null) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LIVE, null) ?: return
        val stored = runCatching { json.decodeFromString(Announcement.serializer(), raw) }.getOrNull()
        _pending.value = stored?.takeIf { it.id == id }
    }

    fun dismissPending() {
        _pending.value = null
    }
}

/**
 * Brings the app to the foreground back into the loop.
 *
 * A periodic worker alone is a background-only channel: someone who leaves the app open all
 * evening would sit there until WorkManager's next window, which a dozing device can defer for
 * hours. Counting started activities catches both halves of "the app is in use" — a cold launch and
 * a return from the recents list — and each one asks for a check. The 15-minute throttle inside
 * [AnnouncementManager.check] is what keeps that from becoming a request per screen rotation.
 */
private object ForegroundCheck : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        // Only the 0 -> 1 edge is the app becoming visible. Every later increment is one activity
        // opening over another, which is not news about the outside world.
        if (started++ == 0) AnnouncementScheduler.checkNow(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Runs the check on a schedule, and again whenever the app is opened.
 *
 * The hourly worker is what reaches a phone sitting in a pocket; [checkNow] is what reaches the
 * person actually using the app, which is most of the audience for anything urgent. An hour is
 * shorter than the three the episode sync uses because the two channels fail differently: a late
 * episode alert is a mild annoyance, while a late "do not install this release" has already missed
 * the moment it was for. Both paths share one throttle, so the cost of the extra triggers is a
 * SharedPreferences read.
 */
object AnnouncementScheduler {
    private const val PERIODIC_NAME = "announcement-check"
    private const val NOW_NAME = "announcement-check-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AnnouncementWorker>(1, TimeUnit.HOURS)
                .addTag("announcements")
                .setConstraints(network)
                .build(),
        )
        checkNow(context)
    }

    /** One check as soon as the network allows. Cheap to call: [AnnouncementManager] throttles. */
    fun checkNow(context: Context) {
        // KEEP rather than REPLACE: a trigger that lands while the previous check is still in
        // flight should let it finish, not restart it from the top.
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NOW_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AnnouncementWorker>()
                .addTag("announcements")
                .setConstraints(network)
                .build(),
        )
    }
}

class AnnouncementWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (AnnouncementManager.check(applicationContext)) Result.success() else Result.retry()
}
