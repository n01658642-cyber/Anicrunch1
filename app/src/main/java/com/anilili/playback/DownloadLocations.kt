package com.anilili.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where finished episode MP4s are written.
 *
 * Four kinds, because Android offers no single way to reach a memory card. Each one is the only
 * route that works for some real device, and every one of them is reachable from a television with
 * a remote control:
 *
 * - [PrimaryDownloads] is what every export used before this existed, and stays the default.
 * - [MediaStoreVolume] writes `Download/Anilili` on a removable volume the platform indexes. It
 *   needs no permission at all and keeps the files visible to every other app, but MediaStore only
 *   admits secondary volumes from Android 10.
 * - [DocumentTree] is a folder the viewer picked in the system picker. It reaches anywhere —
 *   including a USB drive the platform never indexed — at the cost of a picker that plenty of
 *   television builds do not ship.
 * - [AppDirectory] is this app's own folder on a volume. No permission, no picker, so it is the
 *   fallback that always works; the price is that Android 11+ hides `Android/data` from other apps
 *   and uninstalling takes the episodes with it.
 */
sealed interface DownloadLocation {
    /** The form persisted in preferences; parsed back by [parseDownloadLocation]. */
    val storedValue: String

    /** The primary volume's public `Downloads/Anilili`. */
    data object PrimaryDownloads : DownloadLocation {
        override val storedValue: String = PRIMARY
    }

    /** A removable volume MediaStore indexes, named as MediaStore names it (`0000-0000`). */
    data class MediaStoreVolume(val volumeName: String) : DownloadLocation {
        override val storedValue: String = "$VOLUME_PREFIX$volumeName"
    }

    /** A folder picked through the system picker, held open by a persisted URI grant. */
    data class DocumentTree(val treeUri: String) : DownloadLocation {
        override val storedValue: String = "$TREE_PREFIX$treeUri"
    }

    /** This app's own folder on some volume, addressed by absolute path. */
    data class AppDirectory(val path: String) : DownloadLocation {
        override val storedValue: String = "$DIRECTORY_PREFIX$path"
    }

    companion object {
        internal const val PRIMARY = "primary"
        internal const val VOLUME_PREFIX = "volume:"
        internal const val TREE_PREFIX = "tree:"
        internal const val DIRECTORY_PREFIX = "dir:"
    }
}

/**
 * Reads back a stored location, falling back to [DownloadLocation.PrimaryDownloads] for anything
 * unrecognisable.
 *
 * Deliberately total and deliberately quiet. This value names a volume UUID or a picked folder on
 * one particular device, so it also arrives from a settings backup taken on a *different* device,
 * where the card it names does not exist. Whether the target is reachable is a separate question,
 * asked at [DownloadLocations.isReachable]; this one only has to never throw.
 */
internal fun parseDownloadLocation(stored: String?): DownloadLocation {
    val value = stored?.trim().orEmpty()
    return when {
        value.isEmpty() || value == DownloadLocation.PRIMARY -> DownloadLocation.PrimaryDownloads
        value.startsWith(DownloadLocation.VOLUME_PREFIX) ->
            value.removePrefix(DownloadLocation.VOLUME_PREFIX)
                .takeIf { it.isNotBlank() }
                ?.let(DownloadLocation::MediaStoreVolume)
                ?: DownloadLocation.PrimaryDownloads
        value.startsWith(DownloadLocation.TREE_PREFIX) ->
            value.removePrefix(DownloadLocation.TREE_PREFIX)
                .takeIf { it.isNotBlank() }
                ?.let(DownloadLocation::DocumentTree)
                ?: DownloadLocation.PrimaryDownloads
        value.startsWith(DownloadLocation.DIRECTORY_PREFIX) ->
            value.removePrefix(DownloadLocation.DIRECTORY_PREFIX)
                .takeIf { it.isNotBlank() }
                ?.let(DownloadLocation::AppDirectory)
                ?: DownloadLocation.PrimaryDownloads
        else -> DownloadLocation.PrimaryDownloads
    }
}

/**
 * The volume identifier inside a storage path, so a card found one way can be recognised in
 * another. `/storage/1A2B-3C4D/Android/data/…` and MediaStore's `1a2b-3c4d` are the same card.
 */
internal fun volumeIdOfPath(path: String): String? = path
    .removePrefix("/")
    .split('/')
    .let { segments -> segments.getOrNull(1)?.takeIf { segments.firstOrNull() == "storage" } }
    ?.takeIf { it.isNotBlank() && !it.equals("emulated", ignoreCase = true) && it != "self" }
    ?.lowercase()

/** One entry in the save-location picker. */
data class DownloadLocationOption(
    val location: DownloadLocation,
    val title: String,
    val detail: String,
    /** Null when the free space cannot be read — a picked folder on an unidentifiable volume. */
    val freeBytes: Long?,
)

/**
 * The chosen save location, and everything writing to one needs.
 *
 * This setting is kept here in its own preferences rather than in
 * [com.anilili.data.settings.SettingsStore] for two reasons. It is read from
 * [EpisodeExportWorker], which can run on a cold start before the DataStore-backed settings have
 * hydrated — a race whose losing side silently writes to internal storage instead of the card the
 * viewer chose. And it names a volume that exists on this device only, so it has no business
 * travelling in a settings backup to another one.
 */
object DownloadLocations {

    private val _selected = MutableStateFlow<DownloadLocation>(DownloadLocation.PrimaryDownloads)
    val selected: StateFlow<DownloadLocation> = _selected.asStateFlow()

    @Volatile private var loaded = false

    fun current(context: Context): DownloadLocation {
        load(context)
        return _selected.value
    }

    fun selected(context: Context): StateFlow<DownloadLocation> {
        load(context)
        return selected
    }

    fun setLocation(context: Context, location: DownloadLocation) {
        load(context)
        _selected.value = location
        prefs(context).edit().putString(KEY_LOCATION, location.storedValue).apply()
        DiagnosticsLog.event("Download save location set to ${describeForLog(location)}")
    }

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _selected.value = parseDownloadLocation(prefs(context).getString(KEY_LOCATION, null))
            loaded = true
        }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Every place this device can currently save an episode, primary volume first.
     *
     * A volume that MediaStore indexes is offered as a [DownloadLocation.MediaStoreVolume] rather
     * than as this app's private folder on the same card: same disk, but the files stay visible to
     * file managers and survive an uninstall. The private folder is offered only for volumes
     * MediaStore will not take — every secondary volume below Android 10, and USB drives the
     * platform mounts without indexing.
     */
    fun options(context: Context): List<DownloadLocationOption> {
        val app = context.applicationContext
        val options = mutableListOf(
            DownloadLocationOption(
                location = DownloadLocation.PrimaryDownloads,
                title = "Internal storage",
                detail = "Downloads/${EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY}",
                freeBytes = freeBytes(app, DownloadLocation.PrimaryDownloads),
            ),
        )
        val indexedVolumes = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            externalVolumeNames(app).forEach { volumeName ->
                indexedVolumes += volumeName
                val location = DownloadLocation.MediaStoreVolume(volumeName)
                options += DownloadLocationOption(
                    location = location,
                    title = volumeTitle(app, volumeName),
                    detail = "Download/${EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY}",
                    freeBytes = freeBytes(app, location),
                )
            }
        }
        secondaryAppDirectories(app).forEach { directory ->
            val volumeId = volumeIdOfPath(directory.absolutePath)
            if (volumeId != null && volumeId in indexedVolumes) return@forEach
            val location = DownloadLocation.AppDirectory(directory.absolutePath)
            options += DownloadLocationOption(
                location = location,
                title = volumeTitle(app, volumeId, directory),
                detail = "App folder — other apps cannot see it, and uninstalling deletes it",
                freeBytes = freeBytes(app, location),
            )
        }
        // A folder chosen earlier stays on the list even if it is not one of the volumes above:
        // the picker can reach places nothing here enumerates, and a viewer who picked one should
        // see it selected rather than see the list silently disagree with what is stored.
        val chosen = current(app)
        if (chosen is DownloadLocation.DocumentTree && options.none { it.location == chosen }) {
            options += DownloadLocationOption(
                location = chosen,
                title = treeTitle(app, chosen),
                detail = if (holdsTreeGrant(app, chosen)) {
                    "Folder you picked"
                } else {
                    "Folder you picked — access was lost, pick it again"
                },
                freeBytes = freeBytes(app, chosen),
            )
        }
        return options
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun externalVolumeNames(context: Context): List<String> = runCatching {
        MediaStore.getExternalVolumeNames(context)
            .filterNot { it == MediaStore.VOLUME_EXTERNAL || it == MediaStore.VOLUME_EXTERNAL_PRIMARY }
            .filter { mediaProviderAttached(context, it) }
            .sorted()
    }.getOrDefault(emptyList())

    /**
     * Whether MediaProvider will actually take files on [volumeName].
     *
     * [MediaStore.getExternalVolumeNames] is not that answer. It reads the mounted volumes off
     * StorageManager on the caller's side, while MediaProvider only attaches the ones the platform
     * marks visible to apps — and a USB drive usually is not. On the real TV (Android 14) a stick
     * mounted with `mountFlags=0` was listed, offered as a save location and passed [isReachable],
     * and the export then transcoded the whole episode before the insert failed with "Volume … not
     * found". Asking the provider itself, with a query that can match nothing, is what it will
     * later be asked for real.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaProviderAttached(context: Context, volumeName: String): Boolean = runCatching {
        context.contentResolver.query(
            MediaStore.Downloads.getContentUri(volumeName),
            arrayOf(MediaStore.MediaColumns._ID),
            "0",
            null,
            null,
        )?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * This app's folders on every volume but the first. Index 0 is the primary volume, which is
     * already covered by [DownloadLocation.PrimaryDownloads]; a null entry is a volume that is
     * mounted but not currently usable.
     */
    private fun secondaryAppDirectories(context: Context): List<File> =
        context.getExternalFilesDirs(null)
            .drop(1)
            .filterNotNull()
            .filter { directory ->
                runCatching {
                    Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED
                }.getOrDefault(false)
            }

    /** The platform's own name for a card — "SD card", "USB drive" — when it will give one. */
    private fun volumeTitle(context: Context, volumeName: String): String =
        storageVolumeDescription(context) { uuid -> uuid.equals(volumeName, ignoreCase = true) }
            ?: "Memory card (${volumeName.uppercase()})"

    private fun volumeTitle(context: Context, volumeId: String?, directory: File): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val manager = context.getSystemService(StorageManager::class.java)
                manager?.getStorageVolume(directory)?.getDescription(context)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return volumeId?.let { "Memory card (${it.uppercase()})" } ?: "Removable storage"
    }

    private fun storageVolumeDescription(
        context: Context,
        matches: (String) -> Boolean,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            context.getSystemService(StorageManager::class.java)
                ?.storageVolumes
                ?.firstOrNull { volume -> volume.uuid?.let(matches) == true }
                ?.getDescription(context)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun treeTitle(context: Context, location: DownloadLocation.DocumentTree): String =
        runCatching {
            val uri = Uri.parse(location.treeUri)
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            val path = documentId.substringAfter(':', "").trim('/')
            val volumeId = documentId.substringBefore(':', "")
            val volume = when {
                volumeId.equals("primary", ignoreCase = true) -> "Internal storage"
                volumeId.isNotBlank() -> volumeTitle(context, volumeId)
                else -> "Chosen folder"
            }
            if (path.isBlank()) volume else "$volume — $path"
        }.getOrDefault("Chosen folder")

    /** Whether the target is plugged in, mounted, and still ours to write to. */
    fun isReachable(context: Context, location: DownloadLocation): Boolean = when (location) {
        is DownloadLocation.PrimaryDownloads ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
                DeviceDownloadAccess.legacyStorageReady()
        is DownloadLocation.MediaStoreVolume ->
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                location.volumeName in externalVolumeNames(context)
        is DownloadLocation.AppDirectory -> runCatching {
            val directory = File(location.path)
            Environment.getExternalStorageState(directory) == Environment.MEDIA_MOUNTED &&
                (directory.isDirectory || directory.mkdirs())
        }.getOrDefault(false)
        is DownloadLocation.DocumentTree -> holdsTreeGrant(context, location)
    }

    /**
     * Whether writing here needs Android 6–9's runtime storage permission.
     *
     * Only the public Downloads folder does. A picked folder carries its own grant and this app's
     * own folders were never covered by the permission, so choosing a card is also the way an
     * Android 9 viewer avoids the permission prompt entirely.
     */
    fun requiresLegacyPermission(location: DownloadLocation): Boolean =
        location is DownloadLocation.PrimaryDownloads &&
            DeviceDownloadAccess.requiresLegacyWritePermission()

    /**
     * Whether saving to the device would have to ask for the runtime storage permission first, for
     * the location that is actually chosen. A card or a picked folder needs none, so an Android 9
     * viewer who chose one should never see the prompt.
     */
    fun needsLegacyPermission(context: Context): Boolean = current(context).let { location ->
        requiresLegacyPermission(location) && !hasWriteAccess(context, location)
    }

    fun hasWriteAccess(context: Context, location: DownloadLocation): Boolean =
        when (location) {
            is DownloadLocation.PrimaryDownloads -> DeviceDownloadAccess.hasWriteAccess(context)
            is DownloadLocation.DocumentTree -> holdsTreeGrant(context, location)
            else -> true
        }

    private fun holdsTreeGrant(context: Context, location: DownloadLocation.DocumentTree): Boolean =
        runCatching {
            val uri = Uri.parse(location.treeUri)
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
        }.getOrDefault(false)

    /** Why an export cannot start here, in words a viewer can act on; null when it can. */
    /**
     * Why this location cannot be written to, as a catalogue entry rather than a sentence.
     *
     * Returning the code rather than prose is what lets the same answer be a one-line label in a
     * downloads row, a full explanation in Settings, and a greppable event in a diagnostics bundle
     * without three separate wordings that drift apart.
     */
    fun unavailableReason(context: Context, location: DownloadLocation): AnililiError? = when {
        !hasWriteAccess(context, location) -> when (location) {
            is DownloadLocation.DocumentTree -> AnililiError.EXPORT_FOLDER_ACCESS_LOST
            else -> AnililiError.EXPORT_PERMISSION_REQUIRED
        }
        !isReachable(context, location) -> when (location) {
            is DownloadLocation.PrimaryDownloads -> AnililiError.EXPORT_STORAGE_UNAVAILABLE
            else -> AnililiError.EXPORT_STORAGE_DISCONNECTED
        }
        else -> null
    }

    /**
     * Free space where the finished file will land.
     *
     * Null means unknown rather than empty — a picked folder can sit on a provider with no path to
     * measure at all, and refusing an export because a number could not be read would be worse than
     * letting the write itself report that it ran out of room.
     */
    fun freeBytes(context: Context, location: DownloadLocation): Long? {
        val path = volumePath(context, location) ?: return null
        return runCatching {
            val stat = StatFs(path.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        }.getOrNull()
    }

    /**
     * Somewhere on the destination's own volume to build the MP4 before publishing it.
     *
     * Transformer writes to a path, so an export always needs room for a second copy somewhere.
     * Putting that copy on the card the file is heading for is the difference between a card-bound
     * download working and failing on the device this feature exists for: a phone with a full
     * internal disk and a 128 GB card. Falls back to the internal cache when the volume has no
     * usable app folder.
     */
    fun scratchDirectory(context: Context, location: DownloadLocation): File {
        val app = context.applicationContext
        val fallback = File(app.cacheDir, SCRATCH_DIRECTORY)
        val volumeId = volumePath(app, location)?.absolutePath?.let(::volumeIdOfPath)
            ?: return fallback.apply { mkdirs() }
        val onVolume = app.externalCacheDirs
            .filterNotNull()
            .firstOrNull { volumeIdOfPath(it.absolutePath) == volumeId }
            ?: return fallback.apply { mkdirs() }
        val scratch = File(onVolume, SCRATCH_DIRECTORY)
        return if (scratch.isDirectory || scratch.mkdirs()) scratch else fallback.apply { mkdirs() }
    }

    /**
     * Whether [directory] sits on the same volume the finished file will. Unknown counts as the
     * same volume: over-stating the space an export needs only delays it, while under-stating it
     * starts a write that runs the disk dry part-way through.
     */
    fun sharesVolume(context: Context, location: DownloadLocation, directory: File): Boolean {
        val destination = volumePath(context, location) ?: return true
        return volumeIdOfPath(destination.absolutePath) == volumeIdOfPath(directory.absolutePath)
    }

    /** A real filesystem path on the destination's volume, when one can be found. */
    private fun volumePath(context: Context, location: DownloadLocation): File? = when (location) {
        is DownloadLocation.PrimaryDownloads -> context.filesDir
        is DownloadLocation.AppDirectory -> File(location.path).takeIf { it.isDirectory }
        is DownloadLocation.MediaStoreVolume -> appDirectoryOnVolume(context, location.volumeName)
        is DownloadLocation.DocumentTree -> runCatching {
            val documentId = DocumentsContract.getTreeDocumentId(Uri.parse(location.treeUri))
            when (val volumeId = documentId.substringBefore(':', "").lowercase()) {
                "", "primary" -> context.filesDir
                else -> appDirectoryOnVolume(context, volumeId)
            }
        }.getOrNull()
    }

    private fun appDirectoryOnVolume(context: Context, volumeName: String): File? =
        context.getExternalFilesDirs(null)
            .filterNotNull()
            .firstOrNull { volumeIdOfPath(it.absolutePath).equals(volumeName, ignoreCase = true) }

    /** Whether this device has a system folder picker to offer at all. */
    fun canPickFolder(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)
                .isNotEmpty()
        }.getOrDefault(false)
    }

    /** Keeps the grant on a folder the viewer just picked, so the next launch can still write it. */
    fun rememberPickedFolder(context: Context, treeUri: Uri): Boolean {
        val app = context.applicationContext
        return runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            setLocation(app, DownloadLocation.DocumentTree(treeUri.toString()))
            true
        }.onFailure {
            DiagnosticsLog.throwable("Download folder grant could not be kept", it)
        }.getOrDefault(false)
    }

    /** A short name for the destination, for the rows and sheets that say where episodes go. */
    fun label(context: Context, location: DownloadLocation): String = when (location) {
        is DownloadLocation.PrimaryDownloads ->
            "Downloads/${EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY}"
        is DownloadLocation.MediaStoreVolume ->
            "${volumeTitle(context, location.volumeName)}/" +
                EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY
        is DownloadLocation.AppDirectory -> volumeTitle(
            context,
            volumeIdOfPath(location.path),
            File(location.path),
        )
        is DownloadLocation.DocumentTree -> treeTitle(context, location)
    }

    /**
     * The disk without the folder on it — "SD card" where [label] gives "SD card/Anilili".
     *
     * Free space belongs to a volume rather than to a folder, and "40 GB free on SD card/Anilili"
     * reads as though that folder had a quota of its own. Lower case because every use of it is
     * mid-sentence.
     */
    fun volumeLabel(context: Context, location: DownloadLocation): String = when (location) {
        is DownloadLocation.PrimaryDownloads -> "internal storage"
        is DownloadLocation.MediaStoreVolume -> volumeTitle(context, location.volumeName)
        is DownloadLocation.AppDirectory ->
            volumeTitle(context, volumeIdOfPath(location.path), File(location.path))
        // treeTitle is "<volume> — <path>" when the picker gave up a path; the volume is the half
        // the free-space figure is about.
        is DownloadLocation.DocumentTree -> treeTitle(context, location).substringBefore(" — ")
    }

    private fun describeForLog(location: DownloadLocation): String = when (location) {
        is DownloadLocation.PrimaryDownloads -> "primary"
        is DownloadLocation.MediaStoreVolume -> "volume ${location.volumeName}"
        is DownloadLocation.AppDirectory -> "app directory"
        is DownloadLocation.DocumentTree -> "picked folder"
    }

    private const val PREFS_NAME = "download_location"
    private const val KEY_LOCATION = "location"
    private const val SCRATCH_DIRECTORY = "episode-exports"
}
