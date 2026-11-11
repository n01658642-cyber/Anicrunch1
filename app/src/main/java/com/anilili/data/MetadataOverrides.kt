package com.anilili.data

import android.content.Context
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import java.io.InputStream

/**
 * Newer copies of the metadata files the APK bundles, fetched by Settings → Re-download metadata.
 *
 * The bundled `index.json` (the offline catalogue) and `id-map.json` (MAL, TMDB and TVDB ids per
 * title) are fixed at release time, and the metadata tree keeps correcting them daily: on
 * 2026-10-03 the copies shipped a month earlier were 60 titles and 51 TMDB-id corrections behind.
 * A copy downloaded here is read instead of the bundled one, until the app is updated — an APK
 * installed after the download carries files at least as new, so from then on it wins.
 *
 * Kept out of the profile-swapped files: it is the same data whichever profile is in use.
 */
object MetadataOverrides {
    private const val DIRECTORY = "metadata-override"

    fun directory(context: Context): File = File(context.applicationContext.filesDir, DIRECTORY)

    /** The downloaded copy of [asset], or null when there is none or the installed APK is newer. */
    fun file(context: Context, asset: String): File? {
        val file = File(directory(context), asset)
        if (!file.isFile || file.length() == 0L) return null
        return file.takeIf { it.lastModified() >= installedAt(context) }
    }

    /**
     * Reads [asset] from the downloaded copy, falling back to the bundled one when there is none or
     * when the copy cannot be read — a bad download must never cost a viewer the bundled data too.
     */
    fun <T> read(context: Context, asset: String, block: (InputStream) -> T): T {
        file(context, asset)?.let { downloaded ->
            runCatching { downloaded.inputStream().use(block) }
                .onSuccess { return it }
                .onFailure { DiagnosticsLog.throwable("Downloaded $asset unreadable; using the bundled copy", it) }
        }
        return context.assets.open(asset).use(block)
    }

    @Suppress("DEPRECATION")
    private fun installedAt(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)
}
