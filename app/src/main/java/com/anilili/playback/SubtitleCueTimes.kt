package com.anilili.playback

import android.content.Context
import android.net.Uri
import com.anilili.data.AppGraph
import com.anilili.data.model.SubtitleItem
import okhttp3.Request
import java.io.IOException
import com.anilili.util.cached

/**
 * Fetches a subtitle track and reads its timings, for sync-by-capture.
 *
 * Only sidecar files can be read this way — a track muxed into the video is inside the container and
 * media3 is the only thing that has it — so an embedded subtitle returns nothing rather than an
 * error, and the panel above simply does not offer sync for it.
 *
 * Runs on a background thread; the caller is responsible for that.
 */
fun loadSubtitleCueTimes(context: Context, subtitle: SubtitleItem): List<SubtitleCueTime> =
    loadSubtitleText(context, subtitle)?.let(::parseSubtitleCueTimes).orEmpty()

/**
 * A sidecar subtitle file's text, fetched the way the player fetches it, or null when the track has
 * no URL. Shared by sync-by-capture and the signs-plus-dialogue merge. Runs on a background thread.
 */
internal fun loadSubtitleText(context: Context, subtitle: SubtitleItem): String? {
    val url = subtitle.url.trim()
    if (url.isEmpty()) return null
    return when {
        url.startsWith("http://", true) || url.startsWith("https://", true) -> {
            val builder = Request.Builder().url(url).get()
            // The same profile the player fetches it with; a CDN that gates the subtitle on a
            // Referer would otherwise hand back a challenge page instead of the file.
            mediaRequestHeaders(
                referer = subtitle.referer,
                userAgent = NATIVE_PLAYBACK_USER_AGENT,
                extraHeaders = subtitle.headers,
            ).forEach { (key, value) -> builder.header(key, value) }
            AppGraph.httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Subtitle HTTP ${response.code}")
                val length = response.body?.contentLength()?.takeIf { it >= 0 }
                if (length != null && length > MAX_SUBTITLE_BYTES) {
                    throw IOException("That subtitle file is larger than 8 MB.")
                }
                val bytes = response.body?.byteStream()?.use { it.readSubtitleBytes() }
                    ?: throw IOException("The server returned an empty subtitle file.")
                decodeSubtitleBytes(bytes, languageHint = subtitle.language)
            }
        }
        else -> {
            val uri = Uri.parse(url)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readSubtitleBytes() }
                ?: throw IOException("That subtitle file could not be opened.")
            decodeSubtitleBytes(bytes, languageHint = subtitle.language)
        }
    }
}

/** One line of a subtitle file, reduced to what picking it out of a list needs. */
data class SubtitleCueTime(
    val startMs: Long,
    val text: String,
)

/**
 * Reads the timings out of a subtitle file so a viewer can point at the line they just heard.
 *
 * This exists for the sync-by-capture flow and nothing else, so it is deliberately not a subtitle
 * renderer: it wants start times and enough text to recognise a line by, and it throws away styling,
 * positioning, end times and everything else media3's own parsers care about. That is why it can
 * afford to read all three formats in one pass and to be forgiving about malformed entries — a file
 * with a broken cue in it is still perfectly good for choosing a line from.
 */
fun parseSubtitleCueTimes(content: String): List<SubtitleCueTime> {
    val text = content.removePrefix("﻿")
    val cues = if (text.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }) {
        parseAssCueTimes(text)
    } else {
        parseArrowCueTimes(text)
    }
    return cues.sortedBy(SubtitleCueTime::startMs)
}

/** WebVTT and SubRip both put `start --> end` on a line of its own, with the text below it. */
private fun parseArrowCueTimes(content: String): List<SubtitleCueTime> {
    val lines = content.lines()
    val cues = ArrayList<SubtitleCueTime>()
    for ((index, line) in lines.withIndex()) {
        val arrow = line.indexOf("-->")
        if (arrow < 0) continue
        val startMs = parseTimestampMs(line.substring(0, arrow)) ?: continue
        // The text is every line under the timing until the blank line that ends the cue. A cue
        // whose text runs to the end of the file still counts; some writers omit the final newline.
        val body = StringBuilder()
        var cursor = index + 1
        while (cursor < lines.size && lines[cursor].isNotBlank()) {
            if (body.isNotEmpty()) body.append(' ')
            body.append(lines[cursor].trim())
            cursor++
        }
        cues += SubtitleCueTime(startMs, stripMarkup(body.toString()))
    }
    return cues
}

/**
 * ASS and SSA keep everything on the `Dialogue:` line, comma-separated.
 *
 * The text is the tenth field and may itself contain commas, so the split is limited rather than
 * greedy — splitting on every comma would truncate any line with a comma in the dialogue, which is
 * most of them.
 */
private fun parseAssCueTimes(content: String): List<SubtitleCueTime> {
    val cues = ArrayList<SubtitleCueTime>()
    for (line in content.lineSequence()) {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
        val fields = trimmed.removePrefix("Dialogue:").removePrefix("dialogue:").split(",", limit = 10)
        if (fields.size < 10) continue
        val startMs = parseTimestampMs(fields[1]) ?: continue
        cues += SubtitleCueTime(startMs, stripMarkup(fields[9]))
    }
    return cues
}

/**
 * `[[HH:]MM:]SS[.,]fff` in any of the three formats.
 *
 * ASS writes centiseconds and one-digit hours, WebVTT may omit the hour entirely, and SubRip uses a
 * comma for the decimal point. The fraction is scaled by its own length rather than assumed to be
 * milliseconds, which is what keeps ASS's two digits from being read as thousandths.
 */
internal fun parseTimestampMs(raw: String): Long? {
    val value = raw.trim().replace(',', '.')
    if (value.isEmpty()) return null
    val parts = value.split(':')
    if (parts.size !in 2..3) return null

    val seconds = parts.last()
    val dot = seconds.indexOf('.')
    val wholeSeconds = (if (dot < 0) seconds else seconds.substring(0, dot)).toLongOrNull() ?: return null
    val fractionMs = if (dot < 0) {
        0L
    } else {
        val digits = seconds.substring(dot + 1).takeWhile(Char::isDigit)
        if (digits.isEmpty()) {
            0L
        } else {
            val scaled = digits.take(3).padEnd(3, '0')
            scaled.toLongOrNull() ?: return null
        }
    }
    val minutes = parts[parts.size - 2].trim().toLongOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].trim().toLongOrNull() ?: return null else 0L
    if (minutes < 0 || wholeSeconds < 0 || hours < 0) return null
    return ((hours * 3600 + minutes * 60 + wholeSeconds) * 1000) + fractionMs
}

/**
 * Reduces a cue to plain words.
 *
 * The list this feeds is read at a glance while an episode plays, so ASS override blocks and the
 * handful of tags WebVTT allows are noise — and an override block can be longer than the line it
 * decorates, which would leave nothing recognisable on screen.
 */
private fun stripMarkup(raw: String): String = raw
    .replace(Regex.cached("\\{[^}]*\\}"), "")
    .replace(Regex.cached("<[^>]*>"), "")
    .replace("\\N", " ", ignoreCase = true)
    .replace("\\n", " ", ignoreCase = true)
    .replace(Regex.cached("\\s+"), " ")
    .trim()

/**
 * The shift that puts [cue] where the viewer said it belonged.
 *
 * They pressed capture at [capturedPositionMs] because a line should have been on screen then, and
 * picked the line that should have been showing. Positive means the file runs early and its lines
 * have to be held back, which is the same sign [SubtitleDelay] uses.
 */
fun subtitleSyncDelayMs(capturedPositionMs: Long, cue: SubtitleCueTime): Long =
    capturedPositionMs - cue.startMs

/** The [count] cues nearest to [positionMs], nearest first, for the viewer to choose between. */
fun nearestSubtitleCues(
    cues: List<SubtitleCueTime>,
    positionMs: Long,
    count: Int = 5,
): List<SubtitleCueTime> = cues
    .filter { it.text.isNotBlank() }
    .sortedBy { kotlin.math.abs(it.startMs - positionMs) }
    .take(count)
    .sortedBy(SubtitleCueTime::startMs)
