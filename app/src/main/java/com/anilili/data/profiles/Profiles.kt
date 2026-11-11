package com.anilili.data.profiles

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Process
import com.anilili.data.auth.SecureTokenStore
import com.anilili.data.library.WatchNextManager
import com.anilili.data.remote.ListProgressSyncWorker
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** One person on a shared television: a name, a picture, and their own copy of the account data. */
@Serializable
data class LocalProfile(
    val id: String,
    val name: String,
    /**
     * A character portrait from AniList, or the `file://` URL of the viewer's own picture (see
     * [ProfilePictures]); null draws the coloured initial instead.
     */
    val avatarUrl: String? = null,
    val colorIndex: Int = 0,
    val createdAt: Long = 0L,
    /** Whether this profile has been offered the "connect AniList / MAL" step once. */
    val accountStepDone: Boolean = false,
    /**
     * The profile's 4-digit lock, as `salt:sha256(salt + pin)` in hex, or null when it has none.
     * Never the PIN itself: the profile list is a plain SharedPreferences file.
     */
    val pinHash: String? = null,
) {
    val locked: Boolean get() = pinHash != null
}

/**
 * Netflix-style local profiles.
 *
 * What belongs to a profile is exactly what says who is watching: the library (history, Continue
 * Watching, the watchlist and the account's list statuses), the sign-ins (AniList, MAL, Discord,
 * OpenSubtitles — one encrypted file), the search history, and the settings — theme, player,
 * subtitles, content filters. The handful of settings that describe the television itself (layout
 * detection, secure DNS, where downloads go, cache and buffer sizes) are carried across every switch
 * by SettingsStore, so they behave as if nothing were split.
 *
 * **A switch restarts the process.** Every holder of that data — AuthManager, MalAuthManager,
 * LibraryStore, Discord's presence, every ViewModel that has already fetched the account's lists —
 * is a process singleton that read it once. Re-initialising them in place would have to find and
 * reset every one of those caches, and a single one missed would show one person another person's
 * list. So the active profile's files simply *are* the ordinary SharedPreferences files, and a
 * switch moves them aside and moves the other profile's in, in a fresh process, before anything
 * has opened them ([init] runs ahead of every store in `MiruroApp.onCreate`). Nothing else in the
 * app knows profiles exist.
 */
object Profiles {
    private const val PREFS = "anilili_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"
    private const val KEY_PENDING = "pending_switch"
    private const val KEY_LOGIN_COOKIES_STALE = "login_cookies_stale"
    private const val PROFILE_DIR = "profiles"
    private const val SETTINGS_WRITE_GRACE_MS = 400L
    const val MAX_PROFILES = 5
    const val MAX_NAME_LENGTH = 20
    const val MASTER_CODE_LENGTH = 8
    private const val MASTER_CODE_HASH =
        "7f4760418ed125a7dfc808e635a74521:7cde5cd3cd5a26c443de36fcfa4ba128dc76a106e2a7bca60ebbfb68a3ae14ba"

    /** The SharedPreferences files that travel with a profile. */
    private val PROFILE_PREFS = listOf(
        "miruro_library",
        SecureTokenStore.PREFS_NAME,
        "anilili_search_history",
    )

    /**
     * The settings DataStore, which travels too: theme, player, subtitles, content filters. The few
     * settings that describe the television itself are copied back across by SettingsStore — see
     * its DEVICE_KEYS.
     */
    private const val SETTINGS_FILE = "anilili_settings.preferences_pb"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(LocalProfile.serializer())
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    private val _profiles = MutableStateFlow<List<LocalProfile>>(emptyList())
    val profiles: StateFlow<List<LocalProfile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /**
     * The settings file of the profile just switched away from, in the process that switch
     * started; SettingsStore copies the TV's own settings out of it. Null otherwise.
     */
    var outgoingSettingsFile: File? = null
        private set

    /** Set in the process a switch started, so the account-scoped schedulers can be re-run. */
    var justSwitched: Boolean = false
        private set

    val active: LocalProfile? get() = _profiles.value.firstOrNull { it.id == _activeId.value }

    /**
     * Must run before any store opens a per-profile file: a SharedPreferences instance is
     * cached for the life of the process, so a file moved after it was read would be written back
     * over by the stale copy.
     */
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _profiles.value = runCatching {
            prefs.getString(KEY_PROFILES, null)?.let { json.decodeFromString(listSerializer, it) }
        }.getOrNull().orEmpty()
        _activeId.value = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> _profiles.value.any { it.id == id } }
        applyPendingSwitch()
    }

    private fun applyPendingSwitch() {
        val target = prefs.getString(KEY_PENDING, null) ?: return
        val current = _activeId.value
        if (current == null || target == current || _profiles.value.none { it.id == target }) {
            prefs.edit().remove(KEY_PENDING).commit()
            return
        }
        val outgoing = profileDir(current).apply { mkdirs() }
        val incoming = profileDir(target)
        val files = liveFiles()
        // Save first, restore second. Each move is a rename on one filesystem, and a save only ever
        // replaces its destination when there is something to save, so a switch interrupted at any
        // point leaves both copies intact and simply runs again on the next start.
        files.forEach { live ->
            File(live.parentFile, "${live.nameWithoutExtension}.bak").delete()
            if (live.exists()) move(live, File(outgoing, live.name))
        }
        files.forEach { live ->
            val saved = File(incoming, live.name)
            when {
                saved.exists() -> move(saved, live)
                // A profile that has never been active starts with the settings the TV already
                // has rather than factory defaults: the person adding it was looking at them.
                live.name == SETTINGS_FILE -> File(outgoing, live.name).takeIf(File::exists)
                    ?.copyTo(live.apply { parentFile?.mkdirs() }, overwrite = true)
            }
        }
        outgoingSettingsFile = File(outgoing, SETTINGS_FILE)
        _activeId.value = target
        prefs.edit()
            .putString(KEY_ACTIVE, target)
            .remove(KEY_PENDING)
            .putBoolean(KEY_LOGIN_COOKIES_STALE, true)
            .commit()
        justSwitched = true
        DiagnosticsLog.event("Profiles switched from=$current to=$target")
    }

    /** Where each per-profile file lives while its profile is the active one. */
    private fun liveFiles(): List<File> {
        val sharedPrefs = File(appContext.applicationInfo.dataDir, "shared_prefs")
        return PROFILE_PREFS.map { File(sharedPrefs, "$it.xml") } +
            File(File(appContext.filesDir, "datastore"), SETTINGS_FILE)
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        to.delete()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun profileDir(id: String) = File(File(appContext.filesDir, PROFILE_DIR), id)

    /**
     * [id]'s saved settings file while it is *not* the active profile, or null — for the active
     * profile (read its live settings instead; this file is the one SettingsStore has open) and for
     * a profile never opened yet, which will start from the active profile's settings anyway.
     */
    fun savedSettingsFile(id: String): File? {
        if (!::appContext.isInitialized || id == _activeId.value) return null
        return File(profileDir(id), SETTINGS_FILE).takeIf(File::exists)
    }

    /** The first profile adopts everything already on the device, so an update loses nothing. */
    fun createFirst(name: String, avatarUrl: String?, colorIndex: Int): LocalProfile {
        val profile = newProfile(name, avatarUrl, colorIndex)
        _profiles.value = listOf(profile)
        _activeId.value = profile.id
        persist()
        return profile
    }

    /** A new, empty profile. It starts signed out with nothing watched. */
    fun add(name: String, avatarUrl: String?, colorIndex: Int): LocalProfile? {
        if (_profiles.value.size >= MAX_PROFILES) return null
        val profile = newProfile(name, avatarUrl, colorIndex)
        _profiles.value = _profiles.value + profile
        persist()
        return profile
    }

    fun update(profile: LocalProfile) {
        _profiles.value = _profiles.value.map { if (it.id == profile.id) profile else it }
        persist()
    }

    /** The active profile cannot be deleted: its data is the live data. Switch away first. */
    fun delete(id: String): Boolean {
        if (id == _activeId.value) return false
        _profiles.value = _profiles.value.filterNot { it.id == id }
        persist()
        profileDir(id).deleteRecursively()
        DiagnosticsLog.event("Profiles deleted id=$id")
        return true
    }

    /** Sets [id]'s lock to [pin] (4 digits), or removes it when [pin] is null. */
    fun setPin(id: String, pin: String?) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        update(profile.copy(pinHash = pin?.let(::hashPin)))
        DiagnosticsLog.event("Profiles lock ${if (pin == null) "removed" else "set"} id=$id")
    }

    /** Whether [pin] opens [profile]; always true for a profile with no lock. */
    fun pinMatches(profile: LocalProfile, pin: String): Boolean {
        val stored = profile.pinHash ?: return true
        val salt = stored.substringBefore(':', missingDelimiterValue = "")
        return hashPin(pin, salt) == stored
    }

    /**
     * Whether [code] is the master code, which resets any profile's lock on any install — the way
     * back for someone who has forgotten their PIN, handed out on request. Only its hash ships, so
     * the code is not one search of the APK away; it is still the same for everyone, which is
     * acceptable only because the lock keeps a housemate out rather than guarding an account (the
     * sign-ins behind it each have their own password).
     */
    fun masterCodeMatches(code: String): Boolean {
        val salt = MASTER_CODE_HASH.substringBefore(':')
        return code.length == MASTER_CODE_LENGTH && hashPin(code, salt) == MASTER_CODE_HASH
    }

    fun markAccountStepDone() {
        val current = active ?: return
        if (!current.accountStepDone) update(current.copy(accountStepDone = true))
    }

    /**
     * Whether the AniList / MAL website sessions in the WebView cookie jar may belong to another
     * profile. The login page would otherwise sign the new person straight in as the last one.
     * Consumed by the login screen, which is the one place a WebView is being built anyway.
     */
    fun consumeLoginCookiesStale(): Boolean {
        if (!::prefs.isInitialized || !prefs.getBoolean(KEY_LOGIN_COOKIES_STALE, false)) return false
        prefs.edit().remove(KEY_LOGIN_COOKIES_STALE).apply()
        return true
    }

    /**
     * Switch to [id] and restart into it. Suspends while the outgoing profile's work is settled,
     * then hands the restart to [ProfileRestartActivity], which runs in a process of its own so it
     * can outlive this one.
     */
    suspend fun switchTo(activity: Activity, id: String) {
        if (id == _activeId.value || _profiles.value.none { it.id == id }) return
        DiagnosticsLog.event("Profiles switch requested to=$id")
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_PENDING, id).commit()
            // Progress still queued for the outgoing account would otherwise run after the restart
            // and be delivered to whoever is signed in then — the next profile's account.
            runCatching { ListProgressSyncWorker.cancelAll(appContext) }
                .onFailure { DiagnosticsLog.throwable("Profiles list-progress cancel failed", it) }
            runCatching { com.anilili.data.remote.SimklSyncWorker.cancelAll(appContext) }
                .onFailure { DiagnosticsLog.throwable("Profiles Simkl sync cancel failed", it) }
            // The launcher's Continue Watching row is the outgoing person's.
            WatchNextManager.removeAll(appContext)
            // An empty commit waits for every queued apply() on that file, so the move in the next
            // process sees what was last written rather than what was last flushed.
            PROFILE_PREFS.forEach { name ->
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            }
            // Settings saves are fire-and-forget DataStore writes; a change made a moment ago
            // (the picker is one press from Settings) gets time to land before the process goes.
            delay(SETTINGS_WRITE_GRACE_MS)
        }
        activity.startActivity(
            Intent(activity, ProfileRestartActivity::class.java)
                .putExtra(ProfileRestartActivity.EXTRA_PID, Process.myPid()),
        )
        activity.finishAffinity()
    }

    internal fun hashPin(pin: String, salt: String = newSalt()): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray())
        return salt + ":" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun newSalt(): String =
        ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private fun newProfile(name: String, avatarUrl: String?, colorIndex: Int) = LocalProfile(
        id = UUID.randomUUID().toString().take(12),
        name = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "Viewer" },
        avatarUrl = avatarUrl,
        colorIndex = colorIndex,
        createdAt = System.currentTimeMillis(),
    )

    private fun persist() {
        prefs.edit()
            .putString(KEY_PROFILES, json.encodeToString(listSerializer, _profiles.value))
            .putString(KEY_ACTIVE, _activeId.value)
            .apply()
        ProfilePictures.prune(appContext, _profiles.value.map { it.avatarUrl })
    }
}
