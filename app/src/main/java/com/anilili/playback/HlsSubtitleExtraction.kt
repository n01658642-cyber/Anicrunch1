package com.anilili.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import com.anilili.diagnostics.DiagnosticsLog
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.anilili.util.cached

/** One in-manifest subtitle rendition, flattened into a single self-contained WebVTT document. */
internal class ExtractedSubtitle(
    val label: String,
    val language: String,
    val webvtt: String,
)

/**
 * Reads a finished HLS download's in-manifest subtitle renditions back out as WebVTT files.
 *
 * Sidecar subtitles — a provider handing over a `.vtt` URL in `sources.subtitles` — are downloaded
 * beside the episode and exported as-is. Subtitles that ride *inside* the manifest as
 * `EXT-X-MEDIA:TYPE=SUBTITLES` renditions have no such URL, and Media3's MP4 muxer will not take a
 * text track, so without this they survive the download (see `addAllTextTrackSelections`) but not
 * the rewrap: the MP4 in the Downloads folder and anything handed to an external player would be
 * raw video.
 *
 * Everything is read through the export data source, which is cache-first, so a download whose
 * subtitle segments are on disk flattens entirely offline. A rendition whose segments are missing
 * falls back to the network like the rest of the export does, and one that cannot be read at all is
 * skipped rather than failing the export — an MP4 without one subtitle track is worth more than no
 * MP4.
 */
@OptIn(UnstableApi::class)
internal suspend fun extractHlsSubtitles(
    context: Context,
    download: EpisodeDownload,
): List<ExtractedSubtitle> = withContext(Dispatchers.IO) {
    if (!download.isAdaptive) return@withContext emptyList()
    val factory = EpisodeDownloads.exportDataSourceFactory(context)
    val uri = Uri.parse(download.uri)
    val multivariant = runCatching {
        ParsingLoadable.load(factory.createDataSource(), HlsPlaylistParser(), uri, C.DATA_TYPE_MANIFEST)
            as? HlsMultivariantPlaylist
    }.onFailure {
        DiagnosticsLog.throwable("Episode export could not read manifest id=${download.id}", it)
    }.getOrNull() ?: return@withContext emptyList()

    if (multivariant.subtitles.isEmpty()) return@withContext emptyList()

    val extracted = multivariant.subtitles.mapNotNull { rendition ->
        coroutineContext.ensureActive()
        // A rendition without a URI of its own is one muxed into a variant stream, which for
        // subtitles does not happen — but the field is nullable, and there is nothing to fetch.
        val playlistUrl = rendition.url ?: return@mapNotNull null
        runCatching {
            val media = ParsingLoadable.load(
                factory.createDataSource(),
                HlsPlaylistParser(multivariant, /* previousMediaPlaylist= */ null),
                playlistUrl,
                C.DATA_TYPE_MANIFEST,
            ) as HlsMediaPlaylist
            val segments = media.segments.map { UriUtil.resolveToUri(media.baseUri, it.url) }
            val webvtt = mergeWebvttSegments(
                segments.map { segmentUri ->
                    coroutineContext.ensureActive()
                    readFully(factory, segmentUri).decodeToString()
                },
            )
            webvtt?.let {
                ExtractedSubtitle(
                    label = rendition.format.label ?: rendition.name,
                    language = rendition.format.language.orEmpty(),
                    webvtt = it,
                )
            }
        }.onFailure { error ->
            DiagnosticsLog.throwable(
                "Episode export subtitle rendition unreadable id=${download.id} " +
                    "language=${rendition.format.language}",
                error,
            )
        }.getOrNull()
    }
    DiagnosticsLog.event(
        "Episode export flattened in-manifest subtitles id=${download.id} " +
            "renditions=${multivariant.subtitles.size} extracted=${extracted.size}",
    )
    extracted
}

private fun readFully(factory: DataSource.Factory, uri: Uri): ByteArray {
    val source = factory.createDataSource()
    // Closed unconditionally rather than on a success flag: Media3's cache chain reopens a source
    // it believes it already closed, and a wrapper that skips the delegate's close on its own
    // bookkeeping is where the bare IllegalStateException comes from.
    return try {
        source.open(DataSpec(uri))
        DataSourceUtil.readToEnd(source)
    } finally {
        DataSourceUtil.closeQuietly(source)
    }
}

/**
 * Joins a rendition's WebVTT segments into one document on the presentation timeline.
 *
 * Each segment is its own WebVTT file with its own `WEBVTT` header, and HLS puts cue times on the
 * MPEG-TS timeline via `X-TIMESTAMP-MAP:MPEGTS:<pts>,LOCAL:<time>`: the media time of a cue is
 * `local + (MPEGTS / 90000 - LOCAL)`. Subtracting the first segment's adjustment rebases the whole
 * document onto zero, which is where the rewrapped MP4 starts.
 *
 * That one formula covers both conventions in the wild — every segment repeating one map while cue
 * times run continuously, and each segment carrying its own increasing map while cue times restart
 * — and degrades to a plain concatenation when there is no map at all, which is what a subtitle-only
 * rendition typically ships.
 *
 * Returns null when no segment yielded a cue, so an empty rendition is skipped rather than exported
 * as a file with nothing in it.
 */
internal fun mergeWebvttSegments(segments: List<String>): String? {
    val body = StringBuilder()
    var base: Double? = null
    var cues = 0
    segments.forEach { segment ->
        val adjustment = timestampMapAdjustmentSeconds(segment)
        if (base == null) base = adjustment
        val delta = adjustment - base
        var inHeader = true
        var inCue = false
        // The line a cue identifier would be on, if the next line turns out to be a timing. Held
        // rather than written because until that timing arrives it is indistinguishable from the
        // tail of a NOTE or STYLE block, which is dropped.
        var identifier: String? = null
        segment.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            // The WEBVTT line and the blocks attached to it (X-TIMESTAMP-MAP, and any header
            // metadata) belong to the segment, not to the document being built.
            if (inHeader) {
                if (line.isBlank()) inHeader = false
                return@forEach
            }
            val timing = CUE_TIMING.matchEntire(line)
            val start = timing?.let { parseVttTime(it.groupValues[1]) }
            val end = timing?.let { parseVttTime(it.groupValues[2]) }
            when {
                start != null && end != null -> {
                    body.append('\n')
                    identifier?.let { body.append(it).append('\n') }
                    identifier = null
                    body.append(formatVttTime(start + delta))
                        .append(" --> ")
                        .append(formatVttTime(end + delta))
                        .append(timing!!.groupValues[3])
                        .append('\n')
                    inCue = true
                    cues++
                }
                // Cue payload, kept verbatim: only timings move.
                inCue -> {
                    if (line.isBlank()) inCue = false else body.append(line).append('\n')
                }
                // Between blocks. A blank line ends whatever was building; anything else is a
                // candidate identifier, and a block that never reaches a timing line — a NOTE, a
                // repeated STYLE or REGION — is discarded with it.
                else -> identifier = line.takeIf(String::isNotBlank)
            }
        }
    }
    if (cues == 0) return null
    return buildString {
        append("WEBVTT\n")
        append(body)
    }
}

/** `MPEGTS / 90000 - LOCAL`, or zero when the segment carries no timestamp map. */
private fun timestampMapAdjustmentSeconds(segment: String): Double {
    val line = segment.lineSequence()
        .take(WEBVTT_HEADER_SCAN_LINES)
        .firstOrNull { it.startsWith("X-TIMESTAMP-MAP", ignoreCase = true) }
        ?: return 0.0
    var mpegts: Double? = null
    var local: Double? = null
    line.substringAfter('=').split(',').forEach { part ->
        val entry = part.trim()
        when {
            entry.startsWith("MPEGTS:", ignoreCase = true) ->
                mpegts = entry.substringAfter(':').trim().toDoubleOrNull()
            entry.startsWith("LOCAL:", ignoreCase = true) ->
                local = parseVttTime(entry.substringAfter(':').trim())
        }
    }
    val ts = mpegts ?: return 0.0
    return ts / MPEGTS_TIMESCALE - (local ?: 0.0)
}

internal fun parseVttTime(value: String): Double? {
    val parts = value.trim().replace(',', '.').split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    val minutes = parts[parts.size - 2].toIntOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].toIntOrNull() ?: return null else 0
    return hours * 3600.0 + minutes * 60.0 + seconds
}

/** Always `hh:mm:ss.mmm`: the long form is valid everywhere the short form is, and never ambiguous. */
internal fun formatVttTime(seconds: Double): String {
    val clamped = seconds.coerceAtLeast(0.0)
    val totalMillis = Math.round(clamped * 1000.0)
    return "%02d:%02d:%02d.%03d".format(
        totalMillis / 3_600_000,
        (totalMillis / 60_000) % 60,
        (totalMillis / 1000) % 60,
        totalMillis % 1000,
    )
}

/**
 * Compiled once at class level. `Regex` on Android is ICU, and re-compiling one per call is the
 * difference between a parse that keeps up and one that times out on a television.
 */
private val CUE_TIMING =
    Regex.cached("""^((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s+-->\s+((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})(.*)$""")

private const val MPEGTS_TIMESCALE = 90_000.0

/** The map sits in the segment's header block; nothing is gained by reading past it. */
private const val WEBVTT_HEADER_SCAN_LINES = 8
