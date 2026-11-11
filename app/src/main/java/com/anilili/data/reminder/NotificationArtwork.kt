package com.anilili.data.reminder

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.withTimeoutOrNull

/** Android downscales large icons anyway, so a full-size cover is never worth decoding. */
private const val LARGE_ICON_PX = 256

/** A broadcast receiver waits on this, so a stalled CDN must not hold the notification back. */
private const val FETCH_TIMEOUT_MS = 8_000L

/**
 * Loads an anime cover for a notification's large icon, reusing the app's Coil caches so an
 * already-seen poster costs nothing. Returns null for a missing URL, a slow fetch, or a failed
 * one — the notification is still posted, just without artwork.
 */
internal suspend fun notificationPoster(context: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(LARGE_ICON_PX)
        // Hardware bitmaps cannot be parcelled across to the system's notification process.
        .allowHardware(false)
        .build()
    return runCatching {
        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            context.imageLoader.execute(request).drawable?.toBitmap()
        }
    }.getOrNull()
}
