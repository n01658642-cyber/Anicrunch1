package com.anilili.data.backup

import com.anilili.data.library.CustomList
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.WatchlistEntry
import com.anilili.errors.AnililiError
import com.anilili.errors.raise
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Everything one device can hand to another, as it travels: the settings the viewer chose, and the
 * library this device built up on its own.
 *
 * The two lists are the local ones — the watchlist and Continue Watching that exist whether or not
 * anyone is signed in. An account's own list is already the account's, and a signed-in install
 * re-seeds it on the next refresh, so carrying a copy here would only race that refresh; what has
 * never been anywhere but this handset is the part with no other way across.
 *
 * Logins ride along only when the viewer asks for them, and only inside [credentials], which is
 * sealed under a passphrase this file never contains — see [BackupCrypto]. A backup with no
 * passphrase signs nobody in, exactly as before.
 *
 * Downloads are absent for a duller reason: the episodes are gigabytes of cache and MP4s that a
 * JSON file cannot carry, and an index of them alone would describe files the new device does not
 * have.
 *
 * Every field carries a default so a document written by an older Anilili still reads: a missing
 * section restores as an empty one rather than failing the whole file.
 */
@Serializable
data class BackupDocument(
    val format: String = AppBackup.FORMAT,
    val version: Int = AppBackup.VERSION,
    /** Epoch millis, shown to the viewer before a restore so they can tell two files apart. */
    val createdAt: Long = 0L,
    val appVersion: String = "",
    /** Preference key to value, as written by [com.anilili.data.settings.SettingsStore]. */
    val settings: JsonObject = JsonObject(emptyMap()),
    val watchlist: List<WatchlistEntry> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    /**
     * The viewer's own lists. Carried like the watchlist, because a list made while signed out has
     * no other way across, and one the account also holds merges by name instead of doubling.
     */
    val customLists: List<CustomList> = emptyList(),
    /**
     * Titles marked watched without an account. Like a signed-out list, a backup is the only way
     * they reach another device; once an account holds them they are its Completed, not these.
     */
    val watched: List<com.anilili.data.library.WatchedEntry> = emptyList(),
    /** Present only when the viewer asked for logins and set a passphrase. */
    val credentials: SealedCredentials? = null,
) {
    /** "31 settings · 12 saved · 8 continue watching", skipping whatever the file has none of. */
    fun describe(): String = describeCounts(
        settings = settings.size,
        watchlist = watchlist.size,
        history = history.size,
        searchHistory = searchHistory.size,
        logins = credentials?.services.orEmpty().size,
        customLists = customLists.size,
        watched = watched.size,
    )

    /**
     * What the sealed section is bound to.
     *
     * The format and version of the document carrying it, so a sealed blob cannot be moved into a
     * different file and opened there.
     */
    fun credentialAssociatedData(): ByteArray = "$format/$version".toByteArray(Charsets.UTF_8)
}

/**
 * The logins in a backup, under passphrase.
 *
 * [services] is outside the seal on purpose and holds nothing but service names — it is what lets
 * the restore prompt say *what* it is about to ask for a passphrase for. No username, no identity:
 * a file left somewhere should not say whose accounts it can reach.
 */
@Serializable
data class SealedCredentials(
    val kdf: String = BackupCrypto.KDF,
    val iterations: Int = BackupCrypto.ITERATIONS,
    val salt: String = "",
    val iv: String = "",
    val ciphertext: String = "",
    val services: List<String> = emptyList(),
)

/**
 * What is inside the seal.
 *
 * AniList and OpenSubtitles travel as themselves: a long-lived bearer token and a username and
 * password, which two devices can hold at once without interfering.
 *
 * MyAnimeList cannot, and this is the interesting part. MAL rotates its refresh token on every
 * use, so two devices holding one token pair log each other out the first time either refreshes —
 * which is why the pairing flow in `docs/TV_PAIRING_PROTOCOL.md` has the *television* perform its
 * own code exchange. A backup does the same thing across time instead of across a socket: the
 * phone runs one authorization on the restoring device's behalf and puts the resulting
 * [malCode] — with the [malVerifier] it was bound to — in the file. Whoever restores it redeems
 * that code themselves and ends up with a token pair of their own, while the phone keeps its.
 *
 * The consequence is that this one credential is **single-use and short-lived**: MAL invalidates
 * an authorization code once redeemed and expires it quickly on its own schedule. [malIssuedAt]
 * records when it was obtained so a restore can say how old it is, but MAL is the authority on
 * whether it still works — this app does not second-guess a lifetime it was never told.
 */
@Serializable
data class BackupCredentials(
    val aniListToken: String? = null,
    val openSubtitlesUsername: String? = null,
    val openSubtitlesPassword: String? = null,
    /** A MyAnimeList authorization code, obtained for whoever restores this file. */
    val malCode: String? = null,
    /** The PKCE verifier [malCode] is bound to. MAL only supports `plain`, so it is the challenge. */
    val malVerifier: String? = null,
    /** Epoch millis the code was obtained, so a restore can say how stale the hand-off is. */
    val malIssuedAt: Long = 0L,
) {
    val hasAniList: Boolean get() = !aniListToken.isNullOrBlank()

    val hasOpenSubtitles: Boolean
        get() = !openSubtitlesUsername.isNullOrBlank() && !openSubtitlesPassword.isNullOrBlank()

    val hasMalHandoff: Boolean get() = !malCode.isNullOrBlank() && !malVerifier.isNullOrBlank()

    val isEmpty: Boolean get() = !hasAniList && !hasOpenSubtitles && !hasMalHandoff

    /** The names that travel outside the seal, and the ones the restore reports having applied. */
    fun services(): List<String> = buildList {
        if (hasAniList) add(SERVICE_ANILIST)
        if (hasMalHandoff) add(SERVICE_MAL)
        if (hasOpenSubtitles) add(SERVICE_OPEN_SUBTITLES)
    }

    companion object {
        const val SERVICE_ANILIST = "anilist"
        const val SERVICE_MAL = "mal"
        const val SERVICE_OPEN_SUBTITLES = "opensubtitles"

        /** How a service name is written on screen. */
        fun label(service: String): String = when (service) {
            SERVICE_ANILIST -> "AniList"
            SERVICE_MAL -> "MyAnimeList"
            SERVICE_OPEN_SUBTITLES -> "OpenSubtitles"
            else -> service
        }
    }
}

/**
 * A MyAnimeList authorization run on the restoring device's behalf, captured just before the
 * backup is written. See [BackupCredentials] for why it exists at all.
 */
data class MalHandoff(val code: String, val verifier: String)

/** What a restore actually changed, which is rarely the same as what the file held. */
data class RestoreSummary(
    val settings: Int,
    val watchlist: Int,
    val history: Int,
    val searchHistory: Int,
    val logins: Int = 0,
    val customLists: Int = 0,
    val watched: Int = 0,
    /**
     * What went wrong with a part of the restore that the rest could survive — a MyAnimeList
     * hand-off MAL refused, say. Anything the whole restore depends on throws instead.
     */
    val warnings: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = settings == 0 && watchlist == 0 && history == 0 && searchHistory == 0 && logins == 0 &&
            customLists == 0 && watched == 0

    fun describe(): String = describeCounts(settings, watchlist, history, searchHistory, logins, customLists, watched)
}

private fun describeCounts(
    settings: Int,
    watchlist: Int,
    history: Int,
    searchHistory: Int,
    logins: Int,
    customLists: Int = 0,
    watched: Int = 0,
): String = buildList {
    if (settings > 0) add("$settings setting${plural(settings)}")
    if (watchlist > 0) add("$watchlist saved")
    if (customLists > 0) add("$customLists list${plural(customLists)}")
    if (watched > 0) add("$watched watched")
    if (history > 0) add("$history continue watching")
    if (searchHistory > 0) add("$searchHistory search${if (searchHistory == 1) "" else "es"}")
    if (logins > 0) add("$logins login${plural(logins)}")
}.joinToString(" · ").ifEmpty { "nothing" }

private fun plural(count: Int): String = if (count == 1) "" else "s"

/**
 * Reading and writing the backup file itself, with no Android in it so the format can be tested
 * off-device. [BackupManager] is the half that talks to the stores.
 */
object AppBackup {
    /** Stamped into every file and checked on the way back in, so a stray JSON is refused early. */
    const val FORMAT = "anilili-backup"

    /**
     * The document version, bumped only when an older Anilili could *misread* a newer file —
     * adding a field does not count, because unknown keys are ignored and missing ones default.
     */
    const val VERSION = 1

    const val MIME_TYPE = "application/json"

    /**
     * The largest file the parser will look at. A real backup is tens of kilobytes; the cap is
     * there so choosing the wrong file cannot hand a multi-megabyte string to the decoder.
     */
    const val MAX_SOURCE_BYTES = 8L * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // A backup is a file people keep and occasionally open. Indented, it can be read and
        // diffed by hand; the size difference at this scale is a few kilobytes.
        prettyPrint = true
    }

    fun encode(document: BackupDocument): String =
        json.encodeToString(BackupDocument.serializer(), document)

    /**
     * Parse [text], or raise `BAK-03` with something worth showing the viewer.
     *
     * Every failure here is a file the viewer picked by hand, and the answer to all of them is the
     * same — pick a different file — so they are one entry, and what distinguishes them rides in the
     * detail. The usual cause is choosing the MyAnimeList export sitting beside it in the folder,
     * which is why the detail says which kind of wrong this file is.
     */
    fun decode(text: String): BackupDocument {
        if (text.isBlank()) {
            AnililiError.BACKUP_NOT_A_BACKUP.raise(detail = "The chosen file is empty.")
        }
        val document = runCatching { json.decodeFromString(BackupDocument.serializer(), text) }
            .getOrElse { error ->
                AnililiError.BACKUP_NOT_A_BACKUP.raise(
                    detail = "The chosen file is not the JSON an Anilili backup is written as.",
                    cause = error,
                )
            }
        if (document.format != FORMAT) {
            AnililiError.BACKUP_NOT_A_BACKUP.raise(
                detail = "The chosen file declares itself as \"${document.format}\" rather than $FORMAT.",
            )
        }
        if (document.version > VERSION) {
            AnililiError.BACKUP_NOT_A_BACKUP.raise(
                detail = "That backup was written by a newer version of Anilili " +
                    "(format v${document.version}). Update the app, then restore it.",
            )
        }
        return document
    }

    fun fileName(date: LocalDate = LocalDate.now()): String =
        "Anilili-backup-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"
}
