package com.anilili.diagnostics

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Puts a copy of a diagnostics report somewhere the viewer can actually reach.
 *
 * The report itself lives in `cacheDir/diagnostics` behind a `FileProvider`, which means the share
 * sheet is the only door out of the app — and on a television that door does not always open. A
 * viewer sent a photograph of Android's chooser on a TV box listing SmartTube, Cx File Explorer,
 * LocalSend, Bluetooth and CloudStream, pressing every one of them and having nothing happen. The
 * chooser is system UI and most of those targets do nothing with a zip, so there was no bug to fix
 * in the intent; the problem was that when it failed there was no second route, and an internal
 * cache file cannot be reached by a file manager, a USB stick or anything else short of root.
 *
 * So every report is also copied into public Downloads, and the path is handed back so the UI can
 * say where it went. That turns the chooser from the only way of sending a report into a
 * convenience, which is what it should have been.
 *
 * Deliberately self-contained rather than routed through [com.anilili.playback.DownloadLocations]:
 * that machinery is about episode files and carries a location picker, a SAF branch and a
 * series-folder scheme this does not want, and coupling a diagnostics copy to it would make
 * reporting a bug depend on the download subsystem working.
 */
internal object DiagnosticsExport {

    /** Same folder the episode exports use, so a viewer has one place to look. */
    private const val FOLDER = "Anilili"

    /**
     * Copies [report] into `Downloads/Anilili`, returning a viewer-readable path or null.
     *
     * Never throws. A report that cannot be copied is not a failed report — the share sheet is
     * still opening behind this — so every failure is logged and swallowed rather than surfaced as
     * "couldn't prepare diagnostics".
     */
    fun saveToDownloads(context: Context, report: File): String? = runCatching {
        val name = report.name
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertViaMediaStore(context, report, name)
        } else {
            writeDirectly(context, report, name)
        }
        if (path != null) {
            DiagnosticsLog.event(
                "share",
                "report.saved_to_downloads",
                mapOf("path" to path, "bytes" to report.length()),
            )
        }
        path
    }.getOrElse { error ->
        DiagnosticsLog.throwable("Diagnostics copy to Downloads failed", error)
        null
    }

    /**
     * Q and up: MediaStore owns Downloads, and `IS_PENDING` keeps the row hidden until the bytes
     * are there so a file manager cannot show a half-written zip.
     */
    private fun insertViaMediaStore(context: Context, report: File, name: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            report.inputStream().buffered().use { input -> input.copyTo(output) }
        } ?: run {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return "Downloads/$FOLDER/$name"
    }

    /**
     * Below Q there is no MediaStore path for an arbitrary file, so the public directory is written
     * directly — when that is allowed. `WRITE_EXTERNAL_STORAGE` is declared with
     * `maxSdkVersion="28"` and is a runtime grant from 23 up, and a television is exactly the device
     * that never prompted for it, so the common case here is *not granted*.
     *
     * Falling back to the app's own external directory rather than giving up matters more than the
     * nicer path does: it needs no permission at all, and a file manager can still reach
     * `Android/data` on these releases (Android 11 locked that down, but nothing below Q is
     * affected). A copy somewhere awkward beats no copy on the boxes this whole thing exists for.
     */
    private fun writeDirectly(context: Context, report: File, name: String): String? {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        val target = if (granted) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                FOLDER,
            )
        } else {
            DiagnosticsLog.event("Diagnostics copy has no storage permission; using app storage")
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        }
        if (!target.exists() && !target.mkdirs()) return null
        val destination = File(target, name)
        report.inputStream().buffered().use { input ->
            destination.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        // The absolute path when it is somewhere a viewer would never guess, the friendly one when
        // it is the folder they already know from episode exports.
        return if (granted) "Downloads/$FOLDER/$name" else destination.absolutePath
    }
}
