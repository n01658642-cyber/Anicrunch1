package com.anilili.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pulls a still from a local (or reachable) video at a fixed offset so offline episode rows
 * have a real picture when catalogue stills never arrived.
 *
 * Uses [MediaMetadataRetriever.getFrameAtTime] — no FFmpeg, works on every API we ship.
 * Prefer ~12s in so intros/black frames are skipped; fall back to an earlier frame if needed.
 */
object OfflineVideoThumbnail {
    private const val PREFERRED_TIME_US = 12_000_000L // 12 seconds
    private const val FALLBACK_TIME_US = 2_000_000L
    private const val MAX_EDGE_PX = 640

    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val memory = ConcurrentHashMap<String, String>()

    fun cachedPath(downloadId: String): String? = memory[downloadId]

    fun fileFor(context: Context, downloadId: String): File {
        val dir = File(context.filesDir, "episode_thumbs").apply { mkdirs() }
        return File(dir, "${downloadId.hashCode().toUInt()}.jpg")
    }

    /**
     * Returns an absolute file path to a JPEG still, generating one if missing.
     * Safe to call from a background dispatcher; no-ops if another call is already working.
     */
    suspend fun ensure(
        context: Context,
        downloadId: String,
        videoUri: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (videoUri.isNullOrBlank()) return@withContext null
        memory[downloadId]?.let { path ->
            if (File(path).exists()) return@withContext path
        }
        val out = fileFor(context, downloadId)
        if (out.exists() && out.length() > 1_000L) {
            memory[downloadId] = out.absolutePath
            return@withContext out.absolutePath
        }
        if (!inFlight.add(downloadId)) return@withContext memory[downloadId]
        try {
            val bitmap = grabFrame(context, videoUri) ?: return@withContext null
            val scaled = scaleDown(bitmap)
            if (scaled !== bitmap) bitmap.recycle()
            FileOutputStream(out).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos)
            }
            scaled.recycle()
            memory[downloadId] = out.absolutePath
            DiagnosticsLog.event("Offline video thumb saved id=$downloadId bytes=${out.length()}")
            out.absolutePath
        } catch (e: Exception) {
            DiagnosticsLog.throwable("Offline video thumb failed id=$downloadId", e)
            null
        } finally {
            inFlight.remove(downloadId)
        }
    }

    private fun grabFrame(context: Context, videoUri: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(videoUri)
            when (uri.scheme?.lowercase()) {
                "content", "file", "android.resource" ->
                    retriever.setDataSource(context, uri)
                "http", "https" ->
                    // Online stream — may fail without headers; best-effort only
                    retriever.setDataSource(videoUri, HashMap())
                else ->
                    retriever.setDataSource(videoUri)
            }
            retriever.getFrameAtTime(PREFERRED_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(FALLBACK_TIME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= MAX_EDGE_PX && h <= MAX_EDGE_PX) return src
        val scale = MAX_EDGE_PX.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
