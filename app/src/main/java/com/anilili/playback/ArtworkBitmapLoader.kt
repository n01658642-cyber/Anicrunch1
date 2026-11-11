package com.anilili.playback

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.common.util.BitmapLoader
import androidx.media3.session.CacheBitmapLoader
import com.anilili.diagnostics.DiagnosticsLog
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/**
 * Hands every artwork consumer — the platform media session and the media notification — its own
 * private, already-downscaled copy of the artwork bitmap.
 *
 * Media3 passes the decoded artwork straight through to the platform
 * `android.media.session.MediaSession`, and `MediaMetadata.Builder.build()` rescales anything over
 * its internal size cap. That rescale runs on the main thread, some time after the bitmap was
 * loaded — and if anything recycled the source in between, `createBitmap` throws
 * `IllegalArgumentException: cannot use a recycled source in createBitmap` and kills the process
 * the instant playback starts. It was reported from the field on 0.1.62, ~240ms after the first
 * media item was set.
 *
 * 0.1.84 copied the bitmap, but below Media3's [CacheBitmapLoader]: the session and the
 * notification provider both load artwork through the session's loader, so the cache handed them
 * one shared copy, and on a crDroid (Android 15) ROM whose framework recycles a bitmap it has
 * scaled down, that shared copy was dead by the time the session rescaled it — the same crash,
 * every time the internal player started. So the copy now sits above the cache, where each call
 * gets an instance nobody else holds, and it is scaled to the platform's cap first, so the
 * framework's own rescale (the call that threw) never has anything to do.
 *
 * If the source is already gone by the time this runs, the failure lands inside the returned
 * future — which Media3 already treats as "no artwork" — instead of on the main thread as a fatal.
 */
@UnstableApi
internal class ArtworkBitmapLoader(
    private val delegate: BitmapLoader,
    /** Largest edge, in pixels, the platform session keeps without rescaling. */
    private val maxDimensionPx: Int,
) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        privateCopyOf(delegate.decodeBitmap(data))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        privateCopyOf(delegate.loadBitmap(uri))

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? =
        delegate.loadBitmapFromMetadata(metadata)?.let(::privateCopyOf)

    private fun privateCopyOf(source: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> =
        Futures.transform(
            source,
            { bitmap ->
                // A recycled source here would have crashed the main thread further down; failing
                // the future instead degrades to a notification without artwork.
                if (bitmap == null || bitmap.isRecycled) {
                    DiagnosticsLog.event("Artwork bitmap was recycled before the session could copy it")
                    throw IllegalStateException("Artwork bitmap was already recycled")
                }
                privateCopy(bitmap)
            },
            MoreExecutors.directExecutor(),
        )

    /**
     * A new instance, never [bitmap] itself: scaled down to [maxDimensionPx] when larger (which the
     * platform would otherwise do on the main thread), else a straight copy.
     */
    private fun privateCopy(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest > maxDimensionPx) {
            val scale = maxDimensionPx.toFloat() / largest
            val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, /* filter= */ true)
            if (scaled !== bitmap) return scaled
        }
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        return bitmap.copy(config, /* isMutable= */ false)
            ?: throw IllegalStateException("Artwork bitmap could not be copied")
    }

    companion object {
        /**
         * The default Media3 chain, with the copy step above its cache: the cache keeps the one
         * decoded original to itself, and every caller gets a fresh copy of it.
         */
        @UnstableApi
        fun default(context: Context): BitmapLoader =
            ArtworkBitmapLoader(
                CacheBitmapLoader(DataSourceBitmapLoader(context)),
                platformMetadataBitmapCap(context),
            )

        /**
         * The framework's `config_mediaMetadataBitmapMaxSize` (320dp in AOSP), which
         * `MediaSession.setMetadata` scales artwork down to.
         */
        private fun platformMetadataBitmapCap(context: Context): Int {
            val system = Resources.getSystem()
            val id = system.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android")
            val fromFramework = if (id != 0) {
                runCatching { system.getDimensionPixelSize(id) }.getOrNull()
            } else {
                null
            }
            return fromFramework?.takeIf { it > 0 }
                ?: (320 * context.resources.displayMetrics.density).toInt()
        }
    }
}
