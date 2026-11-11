package com.anilili.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Pulls a progressive MP4's index into the media cache before the player asks for it.
 *
 * A file muxed without `faststart` puts its `moov` — the sample table, without which not one frame
 * can be decoded — *after* the media data, at the very end. Lili's are all like this; one measured
 * episode is `ftyp@0(32) free@32(8) mdat@40(571763357) moov@571763397(738576)`, a 546 MB file whose
 * 721 KB index is the last thing in it. So opening one costs three round trips before a picture:
 * read the front, discover the `mdat` runs for half a gigabyte, range-seek to the tail for the
 * index, then range-seek back to the position being played.
 *
 * Measured on a tester's onn 4K pro, one sitting: Lili joined in 2.8 / 4.7 / 5.4 / 8.3 / 8.6 and
 * once 20.0 seconds, while every segmented provider in the same session joined in 0.9–2.0s. The
 * middle round trip is the one nothing else needs, and it is the one this removes — the index is
 * fetched as soon as the stream URL is known, which is seconds before the player is built, and
 * `CacheDataSource` then serves it off disk.
 *
 * Nothing here is load-bearing. A failure, a redirect this cannot follow, a server that will not
 * honour a range, a file that turns out to be faststart after all — every one of them just leaves
 * the player to do what it does today, having cost one 8 KB request. Each outcome is one
 * `ProgressiveIndexWarm …` line naming itself, so a bundle says which happened rather than leaving
 * a reader to infer it from a join time.
 *
 * Verified on the TV emulator, 2026-09-15: a cold episode logged
 * `cached bytes=739024 atByte=518250179 ofBytes=518989203`, and re-opening the same episode — a
 * seek to 54.9s, so squarely the case that needs the index — went from **9.9s to 1.2s** between
 * prepare and READY.
 */
@OptIn(UnstableApi::class)
object ProgressiveIndexWarm {

    /**
     * Caches [stream]'s trailing `moov` atom, if it has one and it is not cached already.
     *
     * Safe to call for any stream: everything that is not a progressive MP4 returns immediately.
     */
    fun warm(context: Context, stream: StreamItem) {
        if (!stream.isProgressive) return
        // With the cache off the player never reads what this would write.
        if (!MediaCache.enabled) return
        val uri = runCatching { Uri.parse(stream.url) }.getOrNull() ?: return
        if (uri.scheme?.startsWith("http") != true) return
        // One at a time per URL. The watch screen re-runs this whenever its data changes, and two
        // writers racing for the same span means one of them fetches 700 KB for nothing.
        synchronized(inFlight) { if (!inFlight.add(stream.url)) return }
        val application = context.applicationContext
        val started = System.currentTimeMillis()
        // Deliberately not the caller's scope. This has to outlive the composable that started it —
        // the whole point is to land before the player opens the file, and the watch screen is
        // being torn down and rebuilt for fullscreen over exactly that window. Run as a child of a
        // cancelled screen it simply stopped, silently, every time.
        warmScope.launch {
            val outcome = runCatching { warmBlocking(application, stream, uri) }
                .getOrElse { error -> "failed:${error.javaClass.simpleName}" }
            synchronized(inFlight) { inFlight.remove(stream.url) }
            DiagnosticsLog.event(
                "ProgressiveIndexWarm $outcome host=${uri.host} tookMs=${System.currentTimeMillis() - started}",
            )
        }
    }

    /** What happened, in a word, for the one line [warm] logs per stream. */
    private fun warmBlocking(context: Context, stream: StreamItem, uri: Uri): String {
        val headers = mediaRequestHeaders(
            referer = stream.referer?.takeIf(String::isNotBlank),
            userAgent = NATIVE_PLAYBACK_USER_AGENT,
            extraHeaders = stream.headers,
        )
        val head = readHead(stream.url, headers)
        val headBytes = head.bytes ?: return "noRanges ${head.why}"
        val moovStart = mp4IndexOffset(headBytes, head.totalLength) ?: return "noIndexFound"
        // Already faststart: the player reads the index out of the opening bytes it has to fetch
        // anyway, and there is no second round trip to save.
        if (moovStart <= headBytes.size) return "alreadyAtFront"
        val length = head.totalLength - moovStart
        // The cache key has to be the one the player will use. `DefaultMediaSourceFactory` leaves
        // `customCacheKey` null for these items, so both sides fall back to the URI.
        val key = stream.url
        val cache = MediaCache.get(context)
        if (cache.isCached(key, moovStart, length)) return "alreadyCached"

        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent(NATIVE_PLAYBACK_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .createDataSourceForDownloading()
        val spec = DataSpec.Builder().setUri(uri).setPosition(moovStart).setLength(length).build()
        CacheWriter(source, spec, null, null).cache()
        return "cached bytes=$length atByte=$moovStart ofBytes=${head.totalLength}"
    }

    /**
     * Where the fetch runs, so that a warm survives the screen that asked for it.
     *
     * [SupervisorJob] because these are independent of each other and of everything else: one
     * stream's index failing is not a reason to stop warming the next one's.
     */
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stream URLs with a warm running, so a recomposition does not start a second one. */
    private val inFlight = mutableSetOf<String>()

    private class Head(val why: String, val bytes: ByteArray?, val totalLength: Long)

    /**
     * The opening atoms and the file's real length, from one ranged read.
     *
     * `Content-Range` carries both, which is why this asks for a range rather than issuing a HEAD:
     * one request instead of two. Anything without one is refused — a server that answers 200 has
     * ignored the range, and warming a tail through a transport that would then *skip* half a
     * gigabyte to reach it is far worse than not warming at all.
     *
     * Read through Media3's own data source because that is the one whose answer matters: whatever
     * it is told here is what the player will be told when it seeks.
     *
     * And it is told no surprisingly often. `play.xfvod.pro` answers a ranged request with a plain
     * `200` and `Accept-Ranges: bytes` in the same breath — the range dropped by a server saying it
     * supports ranges. Measured on the TV emulator across seven episodes: two came back 206 and
     * five 200, the two being exactly the objects a shell had fetched minutes earlier, while from
     * that same shell every one of the seven returned 206. So it reads as an edge-cache state
     * rather than anything about the client — the transport, the encoding and the headers were all
     * ruled out one at a time first. A cold object simply cannot be range-read here, by this or by
     * the player, which is worth remembering the next time a Lili resume stalls with 48 bytes.
     */
    private fun readHead(url: String, headers: Map<String, String>): Head {
        val source = DefaultHttpDataSource.Factory()
            .setUserAgent(NATIVE_PLAYBACK_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
            .createDataSource()
        val spec = DataSpec.Builder()
            .setUri(url)
            .setPosition(0)
            .setLength(HEAD_BYTES.toLong())
            .build()
        try {
            source.open(spec)
            val contentRange = source.responseHeaders["Content-Range"]?.firstOrNull()
                ?: source.responseHeaders["content-range"]?.firstOrNull()
            val total = contentRange?.substringAfter('/')?.toLongOrNull()
            if (total == null || total <= 0L) {
                // Everything a future report needs to tell "this host cannot seek" from "this one
                // request did not come back seekable", without a second round trip to find out.
                val ranges = source.responseHeaders["Accept-Ranges"]?.firstOrNull() ?: "unstated"
                return Head("noContentRange ranges=$ranges", null, 0L)
            }
            val bytes = ByteArray(HEAD_BYTES)
            var filled = 0
            while (filled < HEAD_BYTES) {
                val read = source.read(bytes, filled, HEAD_BYTES - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            return Head("ok", bytes.copyOf(filled), total)
        } finally {
            runCatching { source.close() }
        }
    }

    /**
     * Enough to carry `ftyp`, a `free` or two and the `mdat` header of any muxer seen here, and
     * small enough that a file which turns out not to need warming has cost almost nothing.
     */
    private const val HEAD_BYTES = 8 * 1024
}

/**
 * Where an MP4's sample table starts, by walking the top-level boxes [head] describes.
 *
 * Only the box headers are read — eight bytes of size and type each, sixteen when the size is the
 * 64-bit form — so an `mdat` half a gigabyte long is stepped over without fetching any of it.
 *
 * Two answers count. If `moov` is named inside [head] the file is faststart and its offset is
 * returned, which tells the caller there is nothing to fetch. Otherwise the walk eventually steps
 * to a box whose header lies past [head]: that offset is where the trailing box begins, and on a
 * file laid out `ftyp free mdat …` it is the index. The type is not confirmed, and it does not need
 * to be — the cache stores bytes at an offset, so a wrong guess wastes a fetch and cannot corrupt a
 * playback.
 *
 * Returns null whenever the walk stops making sense: a truncated header, a size that would run past
 * the end of the file, a box small enough to not contain its own header, an `mdat` declaring size
 * zero (meaning "to the end", so nothing follows it), or a trailing box too large to be an index.
 * A guessed offset would spend exactly the bandwidth this exists to save.
 *
 * Kept out of [ProgressiveIndexWarm] so it can be tested against byte arrays rather than a server.
 */
internal fun mp4IndexOffset(head: ByteArray, totalLength: Long): Long? {
    var position = 0L
    while (true) {
        if (position > head.size - 8L) {
            // Past the bytes we hold. Whatever starts here is the rest of the file.
            val trailing = totalLength - position
            return position.takeIf { trailing in 8..MAX_INDEX_BYTES }
        }
        val index = position.toInt()
        val declared = readUInt32(head, index)
        val type = String(head, index + 4, 4, Charsets.US_ASCII)
        if (type == "moov") return position
        val size = when {
            declared == 1L -> {
                if (position > head.size - 16L) return null
                readUInt64(head, index + 8)
            }
            // Zero means "to the end of the file". Only the last box may say it, so whatever this
            // is, nothing follows it — including the index we came for.
            declared == 0L -> return null
            else -> declared
        }
        if (size < 8L || position + size > totalLength) return null
        position += size
    }
}

/**
 * The largest trailing box worth pulling down sight unseen.
 *
 * A sample table scales with the number of frames: the 24-minute episode this was measured on
 * carries 721 KB, so a three-hour film is still comfortably inside this. Past it the thing at the
 * end of the file is not an index, and downloading it in the background would cost more than the
 * round trip it saves.
 */
private const val MAX_INDEX_BYTES = 32L * 1024 * 1024

private fun readUInt32(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (offset in 0 until 4) value = (value shl 8) or (bytes[at + offset].toLong() and 0xFF)
    return value
}

private fun readUInt64(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (offset in 0 until 8) value = (value shl 8) or (bytes[at + offset].toLong() and 0xFF)
    return value
}
