package com.anilili.data.discord

import android.content.Context
import android.os.SystemClock
import com.anilili.R
import com.anilili.data.AppGraph
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Publishes what is playing to Discord, and takes it down again when it stops.
 *
 * Fed from [com.anilili.ui.watch.WatchViewModel.onProgress], which is the one report both players
 * and the offline screen already send — so an embed episode, a native one and a downloaded one all
 * arrive here through the same door.
 *
 * Two things shape everything below. The first is that Discord keeps a progress bar moving on its
 * own once it has been handed a start time, so a card only needs republishing when the *card*
 * changes — a different episode, a pause, a seek — not on every half-second tick. The second is
 * that a presence belongs to a gateway session: closing the connection is how a status disappears,
 * which makes [stop] both the cheap and the correct way to stop showing.
 *
 * Reporting is deliberately lock-free. Publishing is not — it opens sockets and makes requests, and
 * two of those racing would produce two connections and a card that flickers between them — but a
 * progress tick must never wait on one, so the two paths only meet through volatile state.
 */
object DiscordPresence {
    /**
     * Neither player reports progress while paused — both tick only when the video is running —
     * so silence is the pause signal. Native ticks every 500 ms and the embed bridge every second,
     * which leaves this comfortably clear of a slow tick and still quick enough to feel live.
     */
    private const val PAUSE_AFTER_SILENCE_MS = 4_000L
    private const val WATCHDOG_INTERVAL_MS = 1_000L

    /**
     * The gateway allows five presence updates per twenty seconds. Publishing is event-driven and
     * nowhere near that on its own, but a viewer dragging the seek bar generates a burst — so
     * updates inside this window are held and only the last is sent, which is also the only one
     * whose position was ever true.
     */
    private const val MIN_PUBLISH_INTERVAL_MS = 5_000L

    /** How far the clock may drift from where the card said it would be before republishing. */
    private const val SEEK_TOLERANCE_MS = 10_000L

    /** First wait after a refused connection; doubles per refusal. See [connectFailures]. */
    private const val CONNECT_BACKOFF_BASE_MS = 5_000L
    private const val CONNECT_BACKOFF_MAX_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Held only across the network half of an update, never across a report. */
    private val publishMutex = Mutex()

    private lateinit var appName: String

    @Volatile private var enabled = false
    @Volatile private var current: DiscordWatching? = null
    @Volatile private var lastTickAtMs = 0L

    @Volatile private var gateway: DiscordGateway? = null
    @Volatile private var publishedIdentity: String? = null
    @Volatile private var publishedPositionMs = 0L
    @Volatile private var publishedAtMs = 0L

    /**
     * Refused connections in a row, and when the next may be tried. On 0.1.80 a refusal before
     * READY was redialled on every update — twice every five seconds for as long as the episode
     * played, which a TV logged for the whole of an episode. A refusal that repeats is not going
     * to change in five seconds, so each one doubles the wait, up to five minutes; a connection
     * that reaches READY resets it. Guarded by [publishMutex], like [connect].
     */
    private var connectFailures = 0
    private var nextConnectAtMs = 0L

    private var watchdogJob: Job? = null
    private var pendingPublishJob: Job? = null

    /** Whether [pendingPublishJob] has passed its wait and is publishing. Guarded by `this`. */
    private var publishInFlight = false

    fun init(context: Context) {
        appName = context.getString(R.string.app_name)
        scope.launch {
            SettingsStore.discordRichPresence.collectLatest { on ->
                enabled = on
                if (!on) stop()
            }
        }
        scope.launch {
            // A change of status mid-episode goes out now rather than with the next card change,
            // which during uninterrupted playback may not come until the episode ends.
            SettingsStore.discordStatus.collectLatest {
                if (current == null) return@collectLatest
                publishedIdentity = null
                requestPublish()
            }
        }
        scope.launch {
            DiscordAuthManager.loggedIn.collectLatest { signedIn ->
                if (!signedIn) {
                    DiscordAssets.forget()
                    stop()
                }
            }
        }
    }

    /**
     * Reports the live episode. Cheap and safe to call on every progress tick: most calls only
     * refresh the pause watchdog and return without touching the network or a lock.
     */
    fun watching(watching: DiscordWatching) {
        if (!enabled || !DiscordAuthManager.isLoggedIn) return
        lastTickAtMs = SystemClock.elapsedRealtime()
        current = watching
        ensureWatchdog()
        if (shouldPublish(watching)) requestPublish()
    }

    /** Takes the card down. Called when the player is left, and when the feature is turned off. */
    fun stop() {
        synchronized(this) {
            watchdogJob?.cancel()
            watchdogJob = null
            pendingPublishJob?.cancel()
            pendingPublishJob = null
        }
        current = null
        publishedIdentity = null
        publishedAtMs = 0L
        scope.launch {
            publishMutex.withLock {
                val active = gateway ?: return@withLock
                gateway = null
                // Closing the session is what removes the status. Sending an empty activity list
                // first is belt and braces for a socket that outlives this call.
                runCatching {
                    active.sendPresence(
                        clearedPresencePayload(
                            status = SettingsStore.discordStatus.value.storedValue,
                            nowMs = System.currentTimeMillis(),
                        ),
                    )
                }
                active.close()
                DiagnosticsLog.event(category = "discord", name = "presence.stopped")
            }
        }
    }

    private fun shouldPublish(watching: DiscordWatching): Boolean {
        if (watching.cardIdentity != publishedIdentity) return true
        if (publishedAtMs == 0L) return true
        // Where the card claims the viewer is by now, against where they actually are. Ordinary
        // playback tracks this within a tick; a seek does not.
        val projected = publishedPositionMs + (SystemClock.elapsedRealtime() - publishedAtMs)
        return abs(watching.positionMs - projected) > SEEK_TOLERANCE_MS
    }

    /**
     * Queues the update, replacing any still waiting on the rate-limit window. The episode is read
     * again when the wait ends rather than captured here, so a burst of seeks publishes once, with
     * the position the viewer actually settled on.
     */
    private fun requestPublish() {
        val sincePublish = SystemClock.elapsedRealtime() - publishedAtMs
        val wait = if (publishedAtMs == 0L) 0L else (MIN_PUBLISH_INTERVAL_MS - sincePublish).coerceAtLeast(0L)
        synchronized(this) {
            // Only a job still waiting out the window may be replaced. An update already on the
            // wire is left to finish: it reads `current` when it gets there, so it carries what
            // this call would have sent anyway.
            //
            // Cancelling that one instead is what made a status appear only after the viewer
            // paused. Nothing is published yet, so `publishedIdentity` is null and shouldPublish
            // answers true on every tick — every 500 ms on the native player — and each tick tore
            // down a gateway handshake that needs longer than that to complete. The first publish
            // of a card could never land while ticks were arriving. Silence was the only thing
            // that ever let one through, which read as "presence only works when paused".
            if (publishInFlight) return
            pendingPublishJob?.cancel()
            pendingPublishJob = scope.launch {
                if (wait > 0) delay(wait)
                synchronized(this@DiscordPresence) { publishInFlight = true }
                try {
                    publishMutex.withLock {
                        val latest = current ?: return@withLock
                        publish(latest.copy(paused = latest.paused || isSilent()))
                    }
                } finally {
                    // Also on cancellation, so a stop() mid-publish cannot strand the flag set and
                    // wedge every later update out.
                    synchronized(this@DiscordPresence) { publishInFlight = false }
                }
            }
        }
    }

    private fun ensureWatchdog() {
        synchronized(this) {
            if (watchdogJob?.isActive == true) return
            watchdogJob = scope.launch {
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    val live = current ?: continue
                    val silent = isSilent()
                    if (silent == live.paused) continue
                    current = live.copy(paused = silent)
                    requestPublish()
                }
            }
        }
    }

    private fun isSilent(): Boolean =
        SystemClock.elapsedRealtime() - lastTickAtMs > PAUSE_AFTER_SILENCE_MS

    /** Runs under [publishMutex]. */
    private suspend fun publish(watching: DiscordWatching) {
        val token = DiscordAuthManager.freshAccessToken() ?: return
        val largeImage = DiscordAssets.resolve(token, watching.artworkUrl, AppGraph.httpClient)
        // Both keys resolve out of one listing, and the ids are cached for the process, so a binge
        // pays a single request no matter how often the badge swaps between the two.
        val smallImageKey = if (watching.paused) PAUSED_ASSET_KEY else APP_ICON_ASSET_KEY
        val smallImage = DiscordAssets.applicationAssetId(smallImageKey, AppGraph.httpClient)
        val status = SettingsStore.discordStatus.value.storedValue
        val nowMs = System.currentTimeMillis()
        val payload = buildPresencePayload(
            watching = watching,
            appName = appName,
            largeImage = largeImage,
            smallImage = smallImage,
            status = status,
            nowMs = nowMs,
        )

        // One retry, because the failure this covers is a socket that died quietly between
        // updates: the send returns false, and a fresh connection is exactly what fixes it.
        repeat(2) { attempt ->
            val connection = connect(token, replace = attempt > 0) ?: return
            if (connection.sendPresence(payload)) {
                publishedIdentity = watching.cardIdentity
                publishedPositionMs = watching.positionMs
                publishedAtMs = SystemClock.elapsedRealtime()
                DiagnosticsLog.event(
                    category = "discord",
                    name = "presence.published",
                    attributes = mapOf(
                        "paused" to watching.paused,
                        "episode" to (watching.episodeNumber ?: "none"),
                        "hasCover" to (largeImage != null),
                        "retried" to (attempt > 0),
                    ),
                )
                return
            }
        }
        DiagnosticsLog.event(category = "discord", name = "presence.publish_failed")
    }

    /** Runs under [publishMutex]. */
    private suspend fun connect(token: String, replace: Boolean): DiscordGateway? {
        gateway?.let { existing ->
            if (!replace && existing.isReady) return existing
            existing.close()
            gateway = null
        }
        if (SystemClock.elapsedRealtime() < nextConnectAtMs) return null
        val fresh = DiscordGateway(AppGraph.httpClient)
        fresh.onClosed = { closed ->
            if (gateway === fresh) {
                gateway = null
                // Force the next update to republish rather than assume the card survived.
                publishedIdentity = null
                // A refused token is a signed-out session, not a blip. Dropping what is being
                // watched stops a dead token from being retried on every tick for the rest of
                // the episode; everything else simply redials on the next update.
                if (closed.fatal) stop()
            }
        }
        return runCatching {
            fresh.connect(token)
            gateway = fresh
            connectFailures = 0
            nextConnectAtMs = 0L
            fresh
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            runCatching { fresh.close() }
            connectFailures++
            val backoffMs = (CONNECT_BACKOFF_BASE_MS shl (connectFailures - 1).coerceAtMost(6))
                .coerceAtMost(CONNECT_BACKOFF_MAX_MS)
            nextConnectAtMs = SystemClock.elapsedRealtime() + backoffMs
            DiagnosticsLog.throwable(
                "Discord gateway connection failed attempt=$connectFailures retryInMs=$backoffMs",
                error,
            )
        }.getOrNull()
    }
}
