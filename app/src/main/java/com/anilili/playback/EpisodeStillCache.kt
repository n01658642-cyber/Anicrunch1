package com.anilili.playback

import android.content.Context
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads a remote episode still (TMDB/Konoha) into app storage so offline lists keep the
 * Anilili-style thumbnail after the network is gone.
 */
object EpisodeStillCache {
    suspend fun cacheRemoteStill(
        context: Context,
        downloadId: String,
        remoteUrl: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (remoteUrl.isNullOrBlank()) return@withContext null
        if (remoteUrl.startsWith("file://") || remoteUrl.startsWith("/")) return@withContext remoteUrl
        val dir = File(context.filesDir, "episode_stills").apply { mkdirs() }
        val out = File(dir, "${downloadId.hashCode().toUInt()}.jpg")
        if (out.exists() && out.length() > 500L) return@withContext out.absolutePath
        try {
            val conn = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            if (out.length() < 500L) {
                out.delete()
                return@withContext null
            }
            DiagnosticsLog.event("Episode still cached id=$downloadId bytes=${out.length()}")
            out.absolutePath
        } catch (e: Exception) {
            DiagnosticsLog.throwable("Episode still cache failed id=$downloadId", e)
            null
        }
    }
}
