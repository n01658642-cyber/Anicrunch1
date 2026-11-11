package com.anilili.playback

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/** A file that reached its destination, under whatever name the destination gave it. */
internal data class PublishedFile(val uri: Uri, val fileName: String)

/**
 * Writing episode files to whichever [DownloadLocation] the viewer chose.
 *
 * Three storage APIs sit behind one set of calls here — MediaStore, the Storage Access Framework,
 * and plain files — because the destination decides which one is even legal, and the export has no
 * business knowing which. Names are checked for collisions before writing and read back after:
 * both MediaStore and the document providers reserve the right to rename a file on the way in, and
 * a record holding a name the disk does not use is a file the library later fails to find.
 */
internal object DownloadLocationFiles {

    /** A name not already taken in the destination's series folder. */
    fun uniqueFileName(
        context: Context,
        location: DownloadLocation,
        seriesFolder: String,
        stem: String,
        extension: String,
    ): String = when (location) {
        is DownloadLocation.PrimaryDownloads ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                uniqueMediaStoreName(context, location, seriesFolder, stem, extension)
            } else {
                uniqueDirectoryName(
                    DeviceDownloadAccess.legacySeriesDirectory(seriesFolder),
                    stem,
                    extension,
                )
            }
        is DownloadLocation.MediaStoreVolume ->
            uniqueMediaStoreName(context, location, seriesFolder, stem, extension)
        is DownloadLocation.AppDirectory ->
            uniqueDirectoryName(appSeriesDirectory(location, seriesFolder), stem, extension)
        is DownloadLocation.DocumentTree ->
            uniqueTreeName(context, location, seriesFolder, stem, extension)
    }

    /** Copies [source] into the destination and returns where it landed. */
    fun publish(
        context: Context,
        source: File,
        location: DownloadLocation,
        seriesFolder: String,
        fileName: String,
        mimeType: String,
    ): PublishedFile = when (location) {
        is DownloadLocation.PrimaryDownloads ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishToMediaStore(context, source, location, seriesFolder, fileName, mimeType)
            } else {
                check(DeviceDownloadAccess.hasWriteAccess(context)) { LEGACY_PERMISSION_ERROR }
                check(DeviceDownloadAccess.legacyStorageReady()) {
                    "Device storage is not currently available."
                }
                publishToDirectory(
                    context = context,
                    source = source,
                    directory = DeviceDownloadAccess.legacySeriesDirectory(seriesFolder),
                    fileName = fileName,
                    mimeType = mimeType,
                )
            }
        is DownloadLocation.MediaStoreVolume ->
            publishToMediaStore(context, source, location, seriesFolder, fileName, mimeType)
        is DownloadLocation.AppDirectory -> publishToDirectory(
            context = context,
            source = source,
            directory = appSeriesDirectory(location, seriesFolder),
            fileName = fileName,
            mimeType = mimeType,
        )
        is DownloadLocation.DocumentTree ->
            publishToTree(context, source, location, seriesFolder, fileName, mimeType)
    }

    /**
     * Whether a published file is still there — `null` when the question cannot be answered.
     *
     * Unknown is its own answer on purpose. A card that is unplugged, or a folder grant that has
     * not been re-taken, makes every file on it unreadable without any of them being gone, and the
     * caller prunes its index on a `false`. Forgetting a hundred episodes because a card was
     * ejected is not a recoverable mistake; showing them until the card is back is.
     */
    fun exists(context: Context, uri: Uri): Boolean? = when {
        uri.scheme == "file" -> {
            val file = uri.path?.let(::File)
            when {
                file == null -> false
                !volumeMounted(file) -> null
                !readableWithoutGrant(context, file) -> null
                else -> file.isFile
            }
        }
        DocumentsContract.isDocumentUri(context, uri) ->
            if (!holdsGrant(context, uri)) null else queryExists(context, uri, DOCUMENT_PROJECTION)
        else ->
            if (!mediaVolumeMounted(context, uri)) null else queryExists(context, uri, MEDIA_PROJECTION)
    }

    /**
     * Where a file already published lives, in words.
     *
     * Read off the record's own URI rather than off the current setting: an episode saved to a card
     * last week is still on that card after the viewer switches the setting back, and a row that
     * claims otherwise sends them looking in the wrong folder.
     */
    fun describeLocation(context: Context, uri: Uri?): String = when (placeOf(context, uri)) {
        PublishedPlace.DOWNLOADS -> "In Downloads folder"
        PublishedPlace.REMOVABLE -> "On removable storage"
        PublishedPlace.PICKED_FOLDER -> "In the folder you chose"
    }

    /**
     * The same answer as [describeLocation], phrased as the outcome of an export.
     *
     * This used to be a fixed "Saved to Downloads", which an episode written to a USB drive through
     * the folder picker also printed — the one row whose whole job is to say where the file went.
     */
    fun describeSaved(context: Context, uri: Uri?): String = when (placeOf(context, uri)) {
        PublishedPlace.DOWNLOADS -> "Saved to Downloads"
        PublishedPlace.REMOVABLE -> "Saved to removable storage"
        PublishedPlace.PICKED_FOLDER -> "Saved to the folder you chose"
    }

    private enum class PublishedPlace { DOWNLOADS, REMOVABLE, PICKED_FOLDER }

    private fun placeOf(context: Context, uri: Uri?): PublishedPlace = when {
        uri == null -> PublishedPlace.DOWNLOADS
        uri.scheme == "file" ->
            if (volumeIdOfPath(uri.path.orEmpty()) == null) {
                PublishedPlace.DOWNLOADS
            } else {
                PublishedPlace.REMOVABLE
            }
        runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false) ->
            PublishedPlace.PICKED_FOLDER
        else -> when (uri.pathSegments.firstOrNull()?.lowercase()) {
            null, MediaStore.VOLUME_EXTERNAL, MediaStore.VOLUME_EXTERNAL_PRIMARY ->
                PublishedPlace.DOWNLOADS
            else -> PublishedPlace.REMOVABLE
        }
    }

    /** Removes a published file, throwing with a viewer-facing message when it cannot. */
    fun delete(context: Context, uri: Uri) {
        when {
            uri.scheme == "file" -> {
                val file = uri.path?.let(::File) ?: error("Downloaded file path is invalid.")
                check(!file.exists() || file.delete()) { "${file.name} could not be deleted." }
            }
            DocumentsContract.isDocumentUri(context, uri) ->
                check(DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                    "The file could not be deleted from the chosen folder."
                }
            else -> context.contentResolver.delete(uri, null, null)
        }
    }

    // ---- MediaStore ---------------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreCollection(location: DownloadLocation): Uri = when (location) {
        is DownloadLocation.MediaStoreVolume -> MediaStore.Downloads.getContentUri(location.volumeName)
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun uniqueMediaStoreName(
        context: Context,
        location: DownloadLocation,
        seriesFolder: String,
        stem: String,
        extension: String,
    ): String {
        val relativePath = EpisodeDownloads.deviceRelativePath(seriesFolder)
        val collection = mediaStoreCollection(location)
        return uniqueName(stem, extension) { candidate ->
            runCatching {
                context.contentResolver.query(
                    collection,
                    MEDIA_PROJECTION,
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(candidate, relativePath),
                    null,
                )?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToMediaStore(
        context: Context,
        source: File,
        location: DownloadLocation,
        seriesFolder: String,
        fileName: String,
        mimeType: String,
    ): PublishedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                EpisodeDownloads.deviceRelativePath(seriesFolder),
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val target = resolver.insert(mediaStoreCollection(location), values)
            ?: error("The download folder rejected the file")
        runCatching {
            resolver.openOutputStream(target)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("The download folder could not be opened for writing")
        }.onFailure {
            resolver.delete(target, null, null)
            throw it
        }
        resolver.update(
            target,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return PublishedFile(target, displayName(context, target, MEDIA_PROJECTION) ?: fileName)
    }

    // ---- Plain files --------------------------------------------------------------------------

    private fun appSeriesDirectory(
        location: DownloadLocation.AppDirectory,
        seriesFolder: String,
    ): File = File(
        File(location.path, EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY),
        seriesFolder,
    )

    private fun uniqueDirectoryName(directory: File, stem: String, extension: String): String =
        uniqueName(stem, extension) { candidate -> File(directory, candidate).exists() }

    private fun publishToDirectory(
        context: Context,
        source: File,
        directory: File,
        fileName: String,
        mimeType: String,
    ): PublishedFile {
        check(directory.isDirectory || directory.mkdirs()) {
            "${directory.name} could not be created."
        }
        val target = File(directory, fileName)
        check(!target.exists()) { "$fileName already exists in ${directory.name}." }
        val partial = File(directory, ".$fileName.part")
        check(!partial.exists() || partial.delete()) { "An old partial export could not be removed." }
        runCatching {
            source.inputStream().use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            check(partial.renameTo(target)) { "The completed file could not be published." }
        }.onFailure {
            partial.delete()
            throw it
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return PublishedFile(Uri.fromFile(target), fileName)
    }

    // ---- Storage Access Framework -------------------------------------------------------------

    private fun uniqueTreeName(
        context: Context,
        location: DownloadLocation.DocumentTree,
        seriesFolder: String,
        stem: String,
        extension: String,
    ): String {
        // Read the folder once rather than per candidate: every child query crosses into the
        // document provider, and a long-runner's folder is asked about on every single episode.
        val taken = runCatching {
            findSeriesDocument(context, location, seriesFolder)
                ?.let { series -> childNames(context, Uri.parse(location.treeUri), series) }
                .orEmpty()
        }.getOrDefault(emptySet())
        return uniqueName(stem, extension) { candidate -> candidate in taken }
    }

    private fun publishToTree(
        context: Context,
        source: File,
        location: DownloadLocation.DocumentTree,
        seriesFolder: String,
        fileName: String,
        mimeType: String,
    ): PublishedFile {
        val series = requireSeriesDocument(context, location, seriesFolder)
        val target = DocumentsContract.createDocument(
            context.contentResolver,
            series,
            mimeType,
            fileName,
        ) ?: error("The chosen folder would not accept $fileName.")
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("The chosen folder could not be opened for writing")
        }.onFailure {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw it
        }
        return PublishedFile(target, displayName(context, target, DOCUMENT_PROJECTION) ?: fileName)
    }

    /** The `Anilili/<series>` folder inside the picked tree, created if it is not there yet. */
    private fun requireSeriesDocument(
        context: Context,
        location: DownloadLocation.DocumentTree,
        seriesFolder: String,
    ): Uri {
        val tree = Uri.parse(location.treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val library = findOrCreateDirectory(
            context,
            tree,
            root,
            EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY,
        )
        return findOrCreateDirectory(context, tree, library, seriesFolder)
    }

    private fun findSeriesDocument(
        context: Context,
        location: DownloadLocation.DocumentTree,
        seriesFolder: String,
    ): Uri? {
        val tree = Uri.parse(location.treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val library = findChild(
            context,
            tree,
            root,
            EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY,
        ) ?: return null
        return findChild(context, tree, library, seriesFolder)
    }

    private fun findOrCreateDirectory(
        context: Context,
        tree: Uri,
        parent: Uri,
        name: String,
    ): Uri = findChild(context, tree, parent, name)
        ?: DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )
        ?: error("$name could not be created in the chosen folder.")

    private fun findChild(context: Context, tree: Uri, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(parent),
        )
        return context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(
                        tree,
                        cursor.getString(0),
                    )
                }
            }
            null
        }
    }

    private fun childNames(context: Context, tree: Uri, parent: Uri): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getDocumentId(parent),
        )
        return context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
            }
        }.orEmpty()
    }

    // ---- Shared -------------------------------------------------------------------------------

    private fun displayName(context: Context, uri: Uri, projection: Array<String>): String? =
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun queryExists(context: Context, uri: Uri, projection: Array<String>): Boolean? =
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)
                ?.use { it.moveToFirst() } == true
        }.getOrElse { return null }

    /**
     * Whether some grant this app still holds covers [uri]. Checked per entry rather than in one
     * block: a persisted grant on a single document is not a tree and asking it for a tree id
     * throws, which would otherwise report every folder as lost because of one unrelated grant.
     */
    private fun holdsGrant(context: Context, uri: Uri): Boolean = runCatching {
        val documentId = DocumentsContract.getDocumentId(uri)
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission &&
                permission.uri.authority == uri.authority &&
                runCatching {
                    documentId.startsWith(DocumentsContract.getTreeDocumentId(permission.uri))
                }.getOrDefault(false)
        }
    }.getOrDefault(false)

    /**
     * Whether this app could read [file] right now without asking for anything.
     *
     * Android 6–9's storage permission can be revoked long after the file was written, and a
     * revoked permission makes a file that is still on disk look absent. Its own folders are exempt
     * from the permission, so those stay answerable either way.
     */
    private fun readableWithoutGrant(context: Context, file: File): Boolean =
        DeviceDownloadAccess.hasWriteAccess(context) ||
            file.absolutePath.contains("/Android/data/${context.packageName}/")

    private fun volumeMounted(file: File): Boolean = runCatching {
        // A path inside the app's own storage is on the always-mounted internal volume; asking the
        // Environment about it answers for the primary *external* volume instead.
        volumeIdOfPath(file.absolutePath) == null ||
            Environment.getExternalStorageState(file) == Environment.MEDIA_MOUNTED
    }.getOrDefault(true)

    private fun mediaVolumeMounted(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val volume = uri.pathSegments.firstOrNull()?.lowercase() ?: return true
        if (volume == MediaStore.VOLUME_EXTERNAL || volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            return true
        }
        return runCatching {
            MediaStore.getExternalVolumeNames(context).any { it.equals(volume, ignoreCase = true) }
        }.getOrDefault(true)
    }

    private inline fun uniqueName(
        stem: String,
        extension: String,
        exists: (String) -> Boolean,
    ): String {
        var copy = 1
        while (copy <= 999) {
            val suffix = if (copy == 1) "" else " ($copy)"
            val candidate = "$stem$suffix.$extension"
            if (!exists(candidate)) return candidate
            copy += 1
        }
        return "$stem-${System.currentTimeMillis()}.$extension"
    }

    private val MEDIA_PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
    )
    private val DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )

    internal const val LEGACY_PERMISSION_ERROR =
        "Storage permission is needed to save files in Downloads on Android 9 and earlier."
}
