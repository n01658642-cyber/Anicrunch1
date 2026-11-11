package com.anilili.data

import android.content.Context
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import okio.buffer
import okio.sink

/**
 * Settings → Re-download metadata: everything the app holds from the metadata tree, fetched again.
 *
 * What a viewer reaches for when a fix to the tree has been published and their app still shows
 * the old data. Run only from that button — nothing here runs on its own. Four layers can each keep
 * old data, and this goes through all of them:
 *
 *  1. The HTTP cache. jsDelivr marks the tree's files fresh for a week, so a refetch that went
 *     through OkHttp's cache could be handed the very copy being replaced. Emptied first, and the
 *     downloads below bypass it besides.
 *  2. The files the APK bundles: the offline catalogue and the id map. Downloaded whole and kept
 *     beside it ([MetadataOverrides]), which is what the app then reads.
 *  3. The dub overlays, which otherwise top up once a day ([DubIndex.refreshNow]).
 *  4. The metadata cache — title pages, episode lists, filler, backdrops, skip times — emptied, so
 *     each refills from the tree the next time it is asked for.
 *
 * The caller restarts the app afterwards: the id map and the catalogue are parsed once per process,
 * and a restart is the one way to be sure nothing still holds the old copies.
 *
 * Nothing is put in place until both large files have arrived whole, so a download that fails or
 * is abandoned halfway leaves the app as it was.
 */
object MetadataRefresh {
    /** What the Settings row shows: how far along, and doing what. */
    data class Progress(val fraction: Float, val label: String)

    private data class Download(val asset: String, val label: String)

    private val DOWNLOADS = listOf(
        Download("index.json", "catalogue"),
        Download("id-map.json", "title IDs"),
    )

    /** The share of the bar the two large files take; the dub files and the clearing are the rest. */
    private const val DOWNLOAD_SHARE = .9f

    suspend fun run(context: Context, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val konoha = AppGraph.konoha
        onProgress(Progress(0f, "Starting…"))
        runCatching { AppGraph.httpClient.cache?.evictAll() }
            .onFailure { DiagnosticsLog.throwable("Re-download metadata: HTTP cache not cleared", it) }

        val directory = MetadataOverrides.directory(app).apply { mkdirs() }
        // jsDelivr compresses these and streams them without a length, so the bar measures what has
        // arrived against the size of the copy already here — the tree only grows a few percent a
        // month, so it is close. It holds short of each file's end until that file is done.
        val expected = DOWNLOADS.map { expectedSize(app, it.asset) }
        val total = expected.sum().toFloat()
        var finished = 0L
        val parts = DOWNLOADS.mapIndexed { index, download ->
            val part = File(directory, "${download.asset}.part")
            fetch(konoha.dataUrl(download.asset), part) { received ->
                // The read loop blocks; this is where leaving Settings mid-download stops it.
                ensureActive()
                val within = received.coerceAtMost((expected[index] * .98).toLong())
                onProgress(
                    Progress(
                        fraction = DOWNLOAD_SHARE * (finished + within) / total,
                        label = "Downloading ${download.label} · ${megabytes(received)}",
                    ),
                )
            }
            requireWholeJson(part, download.asset)
            finished += expected[index]
            download.asset to part
        }

        onProgress(Progress(DOWNLOAD_SHARE, "Downloading dub info…"))
        DubIndex.refreshNow(app, konoha)

        ensureActive()
        // Every file is here and whole: from this point the new data is what the app reads.
        parts.forEach { (asset, part) ->
            val target = File(directory, asset)
            if (target.exists() && !target.delete()) throw IOException("Could not replace $asset")
            if (!part.renameTo(target)) throw IOException("Could not save $asset")
        }
        onProgress(Progress(.96f, "Clearing old data…"))
        AppGraph.metadataCache.clearAll()
        DiagnosticsLog.event("Re-download metadata done ${parts.joinToString { (asset, _) -> "$asset=${File(directory, asset).length()}" }}")
        onProgress(Progress(1f, "Restarting…"))
    }

    private fun fetch(url: String, target: File, onBytes: (Long) -> Unit) {
        val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
        AppGraph.httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${target.name.removeSuffix(".part")}")
            val source = checkNotNull(response.body).source()
            target.sink().buffer().use { sink ->
                var received = 0L
                var reported = 0L
                while (true) {
                    val read = source.read(sink.buffer, CHUNK_BYTES)
                    if (read == -1L) break
                    sink.emitCompleteSegments()
                    received += read
                    if (received - reported >= REPORT_EVERY_BYTES) {
                        reported = received
                        onBytes(received)
                    }
                }
                onBytes(received)
            }
        }
    }

    /**
     * Cheap proof the whole file arrived: big enough, and closing the bracket it opened. A dropped
     * connection mid-stream is the failure this is for; the readers parse it in full regardless and
     * fall back to the bundled copy if that fails.
     */
    private fun requireWholeJson(file: File, asset: String) {
        if (file.length() < MIN_FILE_BYTES) throw IOException("$asset arrived incomplete (${file.length()} bytes)")
        RandomAccessFile(file, "r").use { raf ->
            fun charAt(position: Long): Char {
                raf.seek(position)
                return raf.read().toChar()
            }
            var start = 0L
            while (start < raf.length() && charAt(start).isWhitespace()) start++
            var end = raf.length() - 1
            while (end > start && charAt(end).isWhitespace()) end--
            val pair = charAt(start) to charAt(end)
            if (pair != ('[' to ']') && pair != ('{' to '}')) throw IOException("$asset arrived incomplete")
        }
    }

    /** The downloaded copy's size when there is one, else the bundled one's, plus a little growth. */
    private fun expectedSize(context: Context, asset: String): Long {
        MetadataOverrides.file(context, asset)?.let { return it.length() }
        val bundled = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull()
            ?: runCatching { context.assets.open(asset).use { it.available().toLong() } }.getOrDefault(0L)
        return (bundled * 1.1).toLong().coerceAtLeast(MIN_FILE_BYTES)
    }

    private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

    private const val CHUNK_BYTES = 64L * 1024
    private const val REPORT_EVERY_BYTES = 128L * 1024
    private const val MIN_FILE_BYTES = 256L * 1024
}
