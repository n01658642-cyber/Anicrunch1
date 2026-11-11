package com.anilili.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.anilili.data.model.StreamItem
import com.anilili.diagnostics.DiagnosticsLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder

/**
 * Spells out the AES-128 IV that Kwik's playlists leave implicit, because the value it means is
 * not the one the standard prescribes.
 *
 * Kwik derives each segment's IV from **the number in its filename** — `segment-135-v1-a1.jpg`
 * decrypts with IV 135 — while a playlist with no `IV=` attribute tells every compliant player to
 * use the media sequence number instead. Those agree only for the first segment, because the names
 * skip (`segment-1`, `segment-5`, `segment-6`, …) while the sequence counts.
 *
 * The damage from the mismatch is one byte: CBC corrupts only the first block, and byte 15 of a TS
 * packet holds the high bits of the PMT PID inside the PAT. So the segment still sniffs as valid
 * TS, still loads with a clean 200, and still reads to the end — while announcing a PMT PID that
 * is not in the file. `TsExtractor` then emits no tracks at all, every chunk "succeeds", and
 * playback dies on `ParserException: Loading finished before preparation is complete` with no
 * cause attached. Measured on a captured segment: media-sequence IV gives 0 tracks, filename IV
 * gives 3 in three reads.
 *
 * Starting at 00:00 hides all of this — the first segment's PAT is correct and Media3 keeps using
 * it — so the failure only shows up when resuming mid-episode, which is what this app does.
 *
 * Writing an explicit `IV=` per segment is ordinary HLS (RFC 8216 §4.3.2.4) and needs no decryption
 * of our own: Media3 keeps doing the AES, just with the right IV. The key itself is untouched and
 * still fetched once, since every emitted tag keeps the original `URI=`.
 *
 * Sitting outermost and reading each playlist whole also makes this the only place that can see
 * what a stream really points at, so it reports that — several providers serve their media from a
 * host other than the playlist's, and knowing which is the difference between a stream that is
 * broken and a CDN that is refusing us.
 */
@UnstableApi
internal class KwikPlaylistDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var buffered: ByteArray? = null
    private var bufferedPosition = 0
    private var upstreamOpen = false
    private var resolvedUri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        buffered = null
        bufferedPosition = 0
        resolvedUri = null
        responseHeaders = emptyMap()
        releaseUpstream()

        val length = upstream.open(dataSpec)
        upstreamOpen = true
        val isPlaylist = dataSpec.position == 0L &&
            dataSpec.uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        if (!isPlaylist) return length

        val raw = try {
            readBounded(length)
        } catch (error: Exception) {
            if (upstreamOpen) {
                upstreamOpen = false
                runCatching { upstream.close() }
            }
            throw error
        }

        val text = raw.toString(Charsets.UTF_8)
        val host = dataSpec.uri.host.orEmpty()
        // Every playlist passes through here already read, so this is the one place that can see
        // where a stream keeps its media. Several providers keep it on another host entirely, and
        // when those segments are refused the episode dies with nothing naming the host that said
        // no — which is exactly how bonk's CDN was mistaken for an ad splice.
        val foreign = playlistForeignHosts(text, host)
        if (foreign.isNotEmpty()) {
            val (uris, strangers) = playlistSegmentCensus(text, host)
            DiagnosticsLog.event(
                "Playlist serves media elsewhere host=${host.ifBlank { "unknown" }} " +
                    "from=${foreign.take(5).joinToString(",")} hosts=${foreign.size} " +
                    "uris=$uris foreignUris=$strangers",
            )
        }

        val rewritten = rewriteKwikPlaylistIvs(text)?.toByteArray(Charsets.UTF_8)
        if (rewritten == null) {
            // Not a Kwik playlist. Serve the bytes already read rather than re-fetching them.
            buffered = raw
            return raw.size.toLong()
        }

        resolvedUri = upstream.uri
        responseHeaders = upstream.responseHeaders
        upstream.close()
        upstreamOpen = false
        DiagnosticsLog.event(
            "Kwik playlist IVs made explicit host=${host.ifBlank { "unknown" }} " +
                "bytes=${raw.size} rewrittenBytes=${rewritten.size}",
        )
        buffered = rewritten
        return rewritten.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val data = buffered ?: return upstream.read(buffer, offset, length)
        if (bufferedPosition >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - bufferedPosition)
        data.copyInto(buffer, offset, bufferedPosition, bufferedPosition + count)
        bufferedPosition += count
        return count
    }

    override fun getUri(): Uri? = resolvedUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (responseHeaders.isNotEmpty()) responseHeaders else upstream.responseHeaders

    override fun close() {
        buffered = null
        bufferedPosition = 0
        resolvedUri = null
        responseHeaders = emptyMap()
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    /**
     * Lets the upstream go before opening it again — unconditionally, without consulting the flag.
     *
     * Media3 hands one chunk's initialisation segment and then its media to the *same* DataSource
     * instance, so a wrapper is reopened without an intervening close. Opening an upstream that is
     * already open trips DefaultDataSource's own `checkState`, which arrives as a bare
     * IllegalStateException inside `Loader$UnexpectedLoaderException` and reaches the viewer as an
     * unexplained "Source error" a couple of seconds into an episode, on any provider, most often
     * when resuming partway in.
     *
     * Guarding this on `upstreamOpen` was tried first and measured still failing, with the same
     * stack: the flag had drifted false while the delegate was genuinely open, so the guard skipped
     * exactly the close it existed to perform. Whatever lets it drift, tracking open-ness in a
     * second place is the thing that can be wrong. `DataSource.close()` is specified to be safe on
     * one that is not open — DefaultDataSource null-checks and clears in a finally — so asking
     * unconditionally cannot be wrong in the way asking conditionally was.
     */
    private fun releaseUpstream() {
        upstreamOpen = false
        runCatching { upstream.close() }
    }

    private fun readBounded(reportedLength: Long): ByteArray {
        if (reportedLength > MAX_PLAYLIST_BYTES) {
            throw IOException("Playlist exceeds the $MAX_PLAYLIST_BYTES byte safety limit")
        }
        return ByteArrayOutputStream(32 * 1_024).use { output ->
            val chunk = ByteArray(16 * 1_024)
            while (true) {
                val read = upstream.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read > 0) {
                    if (output.size() + read > MAX_PLAYLIST_BYTES) {
                        throw IOException("Playlist exceeds the $MAX_PLAYLIST_BYTES byte safety limit")
                    }
                    output.write(chunk, 0, read)
                }
            }
            output.toByteArray()
        }
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 2 * 1_024 * 1_024
    }
}

/**
 * Returns the playlist with an explicit per-segment `IV=`, or null when it is not Kwik's shape and
 * must be passed through untouched.
 *
 * Deliberately strict: a playlist is only rewritten when it is encrypted with AES-128, leaves the
 * IV implicit, and every one of its segments is named `segment-<n>-…`. A stream that already
 * states its IV is standard and correct, and a half-rewritten playlist would be worse than no
 * rewrite at all.
 */
internal fun rewriteKwikPlaylistIvs(playlist: String): String? {
    if (!playlist.startsWith("#EXTM3U")) return null
    val lines = playlist.split("\n")
    val keyLine = lines.map(String::trim).firstOrNull { it.startsWith(KEY_TAG) } ?: return null
    if (!keyLine.contains("METHOD=AES-128", ignoreCase = true)) return null
    // An explicit IV means the stream says what it means; only the implicit form is ambiguous.
    if (keyLine.contains(",IV=", ignoreCase = true)) return null

    val sequences = HashMap<Int, Long>()
    lines.forEachIndexed { index, line ->
        val uri = line.trim()
        if (uri.isEmpty() || uri.startsWith("#")) return@forEachIndexed
        val sequence = SEGMENT_NAME.find(uri)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        sequences[index] = sequence
    }
    if (sequences.isEmpty()) return null

    // Split/join on "\n" leaves any "\r" attached to the line it came from, so each original line
    // keeps the ending it arrived with and the inserted tags carry a matching one.
    val eol = if (playlist.contains("\r\n")) "\r\n" else "\n"
    return lines.mapIndexed { index, line ->
        val sequence = sequences[index] ?: return@mapIndexed line
        "$keyLine,IV=${hlsIv(sequence)}$eol$line"
    }.joinToString("\n")
}

/**
 * The same Kwik stream as a single progressive MP4, or null when the URL is not one of theirs.
 *
 * Kwik's CDN serves every stream twice: as the segmented playlist its player uses, and as one MP4
 * behind the same path with `/stream/` swapped for `/mp4/`. For *downloading* that is strictly the
 * better shape — one ranged request instead of a playlist, a key and ~140 encrypted segments, and
 * the result is already the MP4 the export step would otherwise have to rewrap it into.
 *
 * Playback deliberately does not use this. The `/mp4/` path appears nowhere in Kwik's own player,
 * so it can be withdrawn without warning, and there it would be the only thing between the viewer
 * and a black screen. A download that fails is retried; a playback that fails is the evening.
 *
 * Verified on a Galaxy S25 on 2026-08-11: plays, and seeks cleanly, so the CDN honours ranges.
 */
internal fun kwikProgressiveMp4(stream: StreamItem, fileName: String): StreamItem? {
    if (!stream.isHls) return null
    // Deliberately parsed by hand rather than with android.net.Uri, which is an inert stub under
    // plain JVM unit tests — routing this through it made the whole function untestable.
    val withoutScheme = stream.url.substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isEmpty()) return null
    val scheme = stream.url.substringBefore("://")
    val authority = withoutScheme.substringBefore('/')
    val path = withoutScheme.substring(authority.length).substringBefore('?').substringBefore('#')
    // Strict on purpose: only Kwik's own vault hosts, and only their `/stream/…/uwu.m3u8` shape.
    if (!authority.lowercase().startsWith("vault-")) return null
    if (!path.startsWith("/stream/") || !path.endsWith("/uwu.m3u8")) return null

    val mp4Path = path.removeSuffix("/uwu.m3u8").replaceFirst("/stream/", "/mp4/")
    val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    return stream.copy(url = "$scheme://$authority$mp4Path?file=$encodedName", type = "mp4")
}

/** HLS wants the IV as a 128-bit hex literal, big-endian, `0x`-prefixed. */
internal fun hlsIv(sequence: Long): String =
    "0x" + "%032x".format(sequence)

private const val KEY_TAG = "#EXT-X-KEY:"
private val SEGMENT_NAME = Regex("""(?:^|/)segment-(\d+)-""", RegexOption.IGNORE_CASE)
