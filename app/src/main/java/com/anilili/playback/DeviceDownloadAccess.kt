package com.anilili.playback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/** Android-version-specific access rules for the public Downloads/Anilili folder. */
object DeviceDownloadAccess {
    /** The app's minimum API is 22, so every supported device has a public Downloads folder. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1

    /** Android 6–9 require the legacy dangerous permission; Android 10+ uses scoped MediaStore. */
    fun requiresLegacyWritePermission(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt in Build.VERSION_CODES.M..Build.VERSION_CODES.P

    fun hasWriteAccess(context: Context): Boolean =
        !requiresLegacyWritePermission() ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED

    fun needsWritePermission(context: Context): Boolean =
        requiresLegacyWritePermission() && !hasWriteAccess(context)

    fun legacyStorageReady(): Boolean =
        Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED

    @Suppress("DEPRECATION")
    internal fun legacySeriesDirectory(seriesFolder: String): File =
        File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                EpisodeDownloads.PUBLIC_DOWNLOAD_SUBDIRECTORY,
            ),
            seriesFolder,
        )
}
