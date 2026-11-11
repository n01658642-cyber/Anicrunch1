package com.anilili.data.usage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anilili.BuildConfig
import com.anilili.data.AppGraph
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.auth.SimklAuthManager
import com.anilili.data.remote.MalClient
import com.anilili.data.remote.SimklClient
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.playback.PlaybackStatus
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The once-a-day usage ping every Anilili app sends (`docs/USAGE-PING.md`, version 2).
 *
 * What it says: a device ID, the platform, the version and build, which list services are signed
 * in and a hash of each account's ID (so one person's devices can be linked), the device (system,
 * maker, model, screen, languages), the main settings, and how long the app was in front and
 * playing since the last accepted ping, and how often it was opened; the server adds the country
 * and city. Always on, with no switch and no entry in Settings.
 *
 * The ID is a salted hash of [Settings.Secure.ANDROID_ID], which survives uninstalling, so a
 * reinstall is recognised as the same device. The day of the last accepted ping and the usage
 * counters are kept in a preferences file of their own: they belong to this install, so they
 * neither move with a profile nor travel in a backup, where a restore onto a new phone would have
 * it skip a day, miss `first` and report another phone's minutes.
 */
object UsagePing {
    private const val ENDPOINT = "https://pulse.anilili.workers.dev/v1/ping"
    private const val PREFS = "anilili_usage"
    private const val KEY_DAY = "usage_ping_day"
    private const val KEY_FALLBACK_ID = "usage_device_fallback"
    private const val KEY_FRONT_MS = "usage_front_ms"
    private const val KEY_WATCHED_MS = "usage_watched_ms"
    private const val KEY_SESSIONS = "usage_sessions"
    private const val SALT = "anilili-usage-v1:"
    private const val PERSON_SALT = "anilili-person-v1:"
    private const val WORK_NAME = "usage-ping"
    private const val MINUTE_MS = 60_000L

    /** Away this long, coming back is a new session. */
    private const val SESSION_GAP_MS = 5 * MINUTE_MS

    /**
     * Only the build that ships pings. Not `!DEBUG`: the baseline-profile plugin adds
     * `benchmarkRelease` and `nonMinifiedRelease`, which aren't debuggable and run on the
     * developer's emulators and phones during profiling.
     */
    private val counted = BuildConfig.BUILD_TYPE == "release"

    private val lenientJson = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val counting = Any()
    private lateinit var prefs: SharedPreferences

    /** When the app came to the front, and when it last went away (elapsed-realtime ms). */
    private var frontSince: Long? = null
    private var awaySince: Long? = null
    private var playingSince: Long? = null

    /**
     * The question "is the app in use" is asked by the process coming to the foreground; the same
     * moments start and stop the minutes, and [PlaybackStatus] the minutes watched.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    cameToFront(SystemClock.elapsedRealtime())
                    checkNow(app)
                }

                override fun onStop(owner: LifecycleOwner) = wentAway(SystemClock.elapsedRealtime())
            },
        )
        if (!counted) return
        scope.launch {
            PlaybackStatus.isPlaying.collect { playing ->
                val now = SystemClock.elapsedRealtime()
                synchronized(counting) {
                    val since = playingSince
                    if (playing && since == null) {
                        playingSince = now
                    } else if (!playing && since != null) {
                        add(KEY_WATCHED_MS, now - since)
                        playingSince = null
                    }
                }
            }
        }
    }

    private fun cameToFront(now: Long) {
        if (!counted) return
        synchronized(counting) {
            val away = awaySince
            if (frontSince == null && (away == null || now - away >= SESSION_GAP_MS)) {
                prefs.edit().putInt(KEY_SESSIONS, (prefs.getInt(KEY_SESSIONS, 0) + 1).coerceAtMost(500)).apply()
            }
            if (frontSince == null) frontSince = now
        }
    }

    private fun wentAway(now: Long) {
        if (!counted) return
        synchronized(counting) {
            frontSince?.let { add(KEY_FRONT_MS, now - it) }
            frontSince = null
            awaySince = now
        }
    }

    /** Adds to a millisecond counter, capped at two days' worth. Call with [counting] held. */
    private fun add(key: String, ms: Long) {
        if (ms <= 0) return
        prefs.edit().putLong(key, (prefs.getLong(key, 0) + ms).coerceAtMost(2880 * MINUTE_MS)).apply()
    }

    /** One attempt as soon as the network allows. Cheap: an accepted ping today ends it at once. */
    fun checkNow(context: Context) {
        if (!counted) return
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UsagePingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Returns false only when the send failed, for a retry on the next foreground. */
    internal suspend fun send(context: Context): Boolean {
        if (!counted) return true
        // The settings are read from disk asynchronously; before that they show their defaults.
        withTimeoutOrNull(10_000) { SettingsStore.awaitLoaded() } ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = utcDay()
        val lastDay = prefs.getString(KEY_DAY, null)
        if (lastDay == today) return true
        val tv = AppGraph.isTv
        val usage = synchronized(counting) {
            Usage(
                minutes = (prefs.getLong(KEY_FRONT_MS, 0) / MINUTE_MS).toInt(),
                watched = (prefs.getLong(KEY_WATCHED_MS, 0) / MINUTE_MS).toInt(),
                sessions = prefs.getInt(KEY_SESSIONS, 0),
            )
        }
        val body = body(
            device = deviceId(rawDeviceId(context, prefs)),
            platform = platform(context),
            version = BuildConfig.VERSION_NAME,
            build = BuildConfig.VERSION_CODE,
            accounts = accounts(
                anilist = AuthManager.isLoggedIn,
                mal = MalAuthManager.isLoggedIn,
                simkl = SimklAuthManager.isLoggedIn,
            ),
            lastDay = lastDay,
            today = today,
            people = people(),
            env = environment(context, tv),
            settings = settings(),
            usage = usage,
        ) ?: return true
        val accepted = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                AppGraph.httpClient.newCall(request).execute().use { it.isSuccessful }
            }.onFailure { DiagnosticsLog.event("Usage ping failed: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
        }
        if (accepted) {
            // Only what was sent goes: what was counted while it was on its way stays for the next.
            synchronized(counting) {
                prefs.edit()
                    .putString(KEY_DAY, today)
                    .putLong(KEY_FRONT_MS, (prefs.getLong(KEY_FRONT_MS, 0) - usage.minutes * MINUTE_MS).coerceAtLeast(0))
                    .putLong(KEY_WATCHED_MS, (prefs.getLong(KEY_WATCHED_MS, 0) - usage.watched * MINUTE_MS).coerceAtLeast(0))
                    .putInt(KEY_SESSIONS, (prefs.getInt(KEY_SESSIONS, 0) - usage.sessions).coerceAtLeast(0))
                    .apply()
            }
        }
        return accepted
    }

    internal data class Usage(val minutes: Int, val watched: Int, val sessions: Int)

    internal fun utcDay(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** The ID sent for a raw one: salted, so it matches nothing outside Anilili. */
    internal fun deviceId(raw: String): String = sha256(SALT + raw)

    /** The hash every app makes of a list account: the same account, the same hash, everywhere. */
    internal fun personHash(service: String, id: Long): String = sha256("$PERSON_SALT$service:$id")

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * One hash per signed-in list account, AniList, MAL, Simkl, of the account's numeric ID.
     * AniList's is in its token; MAL's and Simkl's are asked for, and one that can't be had right
     * now (offline, a refused token) is left out until the next ping.
     */
    private suspend fun people(): List<String> = buildList {
        AuthManager.viewerId()?.takeIf { it > 0 }?.let { add(personHash("anilist", it.toLong())) }
        if (MalAuthManager.isLoggedIn) {
            runCatching { MalClient(AppGraph.httpClient, lenientJson).viewer().id }
                .onFailure { DiagnosticsLog.event("Usage ping: no MAL id (${it.javaClass.simpleName})") }
                .getOrNull()?.takeIf { it > 0 }?.let { add(personHash("mal", it.toLong())) }
        }
        if (SimklAuthManager.isLoggedIn) {
            runCatching { SimklClient.accountId() }
                .onFailure { DiagnosticsLog.event("Usage ping: no Simkl id (${it.javaClass.simpleName})") }
                .getOrNull()?.let { add(personHash("simkl", it)) }
        }
    }

    /**
     * The device. The screen is the display's own size, held the way the device normally is:
     * portrait for phones and tablets, landscape for a television. The language is the system's,
     * not the one the app may have been switched to.
     */
    private fun environment(context: Context, tv: Boolean): JsonObject {
        // Display modes and locale lists came with Android 6 and 7; the app runs from 5.1.
        val size = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
                    ?.let { it.physicalWidth to it.physicalHeight }
            }.getOrNull()
        } else {
            null
        }
        val screen = size?.let { (w, h) ->
            val (long, short) = maxOf(w, h) to minOf(w, h)
            if (tv) "${long}x$short" else "${short}x$long"
        }
        val configuration = Resources.getSystem().configuration
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            configuration.locale
        }
        return buildJsonObject {
            put("os", clean(Build.VERSION.RELEASE, 24))
            put("api", Build.VERSION.SDK_INT)
            put("maker", clean(Build.MANUFACTURER, 40))
            put("model", clean(Build.MODEL, 60))
            screen?.let { put("screen", it) }
            system?.toLanguageTag()?.takeIf { it != "und" }?.let { put("lang", it) }
            put("app_lang", if (SettingsStore.menuLanguage.value.usesSpanish(system?.language ?: "")) "es" else "en")
        }
    }

    /** The app's main settings, under their stored values. */
    private fun settings(): JsonObject = buildJsonObject {
        put("quality", SettingsStore.defaultQuality.value.storedValue)
        put("theme", SettingsStore.themeBackground.value.storedValue)
        put("audio", if (SettingsStore.preferDub.value) "dub" else "sub")
        put("player", if (SettingsStore.openEpisodesInExternalPlayer.value) "external" else "internal")
    }

    /**
     * A value as the server takes it: letters, digits, spaces and a few marks, trimmed, at most
     * [max] characters.
     */
    internal fun clean(value: String?, max: Int): String =
        (value ?: "").map { if (it.isLetterOrDigit() || it in " ._+-()/,'&") it else ' ' }
            .joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            .take(max).trim()

    /**
     * ANDROID_ID: one per device and signing key, kept across reinstalls, new on a factory reset.
     * Read for this purpose only. Some old or odd builds return nothing (or the 2.2-era constant
     * every one of them shared), and then a random ID in this app's preferences stands in, which a
     * reinstall loses.
     */
    @SuppressLint("HardwareIds")
    private fun rawDeviceId(context: Context, prefs: SharedPreferences): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") return androidId
        return prefs.getString(KEY_FALLBACK_ID, null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_FALLBACK_ID, it).apply() }
    }

    /** A television first (either flavor can find itself on one), then a tablet by its width. */
    private fun platform(context: Context): String = when {
        AppGraph.isTv -> "android-tv"
        context.resources.configuration.smallestScreenWidthDp.let {
            it != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED && it >= 600
        } -> "android-tablet"
        else -> "android"
    }

    internal fun accounts(anilist: Boolean, mal: Boolean, simkl: Boolean): List<String> =
        buildList {
            if (anilist) add("anilist")
            if (mal) add("mal")
            if (simkl) add("simkl")
        }

    /** The JSON to send today, or null when today's ping was already accepted. */
    internal fun body(
        device: String,
        platform: String,
        version: String,
        build: Int,
        accounts: List<String>,
        lastDay: String?,
        today: String,
        people: List<String> = emptyList(),
        env: JsonObject = JsonObject(emptyMap()),
        settings: JsonObject = JsonObject(emptyMap()),
        usage: Usage = Usage(0, 0, 0),
    ): String? {
        if (lastDay == today) return null
        return buildJsonObject {
            put("device", device)
            put("platform", platform)
            put("version", version)
            put("build", build)
            putJsonArray("accounts") { accounts.forEach { add(JsonPrimitive(it)) } }
            put("first", lastDay == null)
            putJsonArray("people") { people.forEach { add(JsonPrimitive(it)) } }
            put("env", env)
            put("settings", settings)
            putJsonObject("usage") {
                put("minutes", usage.minutes)
                put("watched", usage.watched)
                put("sessions", usage.sessions)
            }
        }.toString()
    }
}

class UsagePingWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    // No WorkManager retry: the next time the app comes to the foreground tries again, which is
    // the only moment a ping means anything.
    override suspend fun doWork(): Result {
        UsagePing.send(applicationContext)
        return Result.success()
    }
}
